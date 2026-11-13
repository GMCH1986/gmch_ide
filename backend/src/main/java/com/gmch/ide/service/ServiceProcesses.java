package com.gmch.ide.service;

import com.gmch.ide.config.Workspace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 把运行配置转换为真实进程。这里集中处理 Windows 的 {@code .cmd} 启动、
 * JDK 环境注入、输出按行读取和完整进程树停止，避免编排层关心平台差异。
 */
@Service
public class ServiceProcesses {

    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase()
            .contains("win");

    private final Workspace workspace;
    private final String javaHome;
    private final HttpClient httpClient = HttpClient.newBuilder()
            // Vite 的开发服务器不能可靠处理 JDK HttpClient 默认的 h2c 探测请求。
            // 就绪检查只需要普通 HTTP/1.1 GET，固定协议可以覆盖 Vite 等 Node 服务。
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ServiceProcesses(Workspace workspace, @Value("${app.java-home:}") String javaHome) {
        this.workspace = workspace;
        this.javaHome = javaHome == null || javaHome.isBlank()
                ? System.getenv().getOrDefault("JAVA_HOME", "")
                : javaHome;
    }

    public Path workingDirectory(RunConfiguration.Service service) {
        Path root = workspace.root();
        String relative = service.cwd() == null || service.cwd().isBlank() ? "." : service.cwd();
        Path directory = workspace.resolve(relative);
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException(
                    "服务 " + service.id() + " 的工作目录不存在: " + directory
            );
        }
        if (!directory.startsWith(root)) {
            throw new IllegalStateException("服务工作目录超出项目根目录: " + directory);
        }
        return directory;
    }

    public Process start(
            RunConfiguration.Service service,
            RunConfiguration.Command command,
            Consumer<String> onLine
    ) throws IOException {
        Path directory = workingDirectory(service);
        List<String> executable = executable(service, command, directory);
        String display = executable.stream().map(ServiceProcesses::displayArgument)
                .collect(Collectors.joining(" "));
        onLine.accept("$ " + display);

        ProcessBuilder builder = new ProcessBuilder(platformCommand(executable));
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        builder.redirectInput(ProcessBuilder.Redirect.PIPE);

        if (!javaHome.isBlank()) {
            builder.environment().put("JAVA_HOME", javaHome);
            String pathKey = WINDOWS ? "Path" : "PATH";
            String currentPath = builder.environment().getOrDefault(pathKey, "");
            builder.environment().put(pathKey, Path.of(javaHome, "bin") + java.io.File.pathSeparator + currentPath);
        }
        if (service.env() != null) {
            builder.environment().putAll(service.env());
        }

        Process process = builder.start();
        // 不给子进程留下可交互的标准输入，避免 Maven / pnpm 等待控制台输入。
        process.getOutputStream().close();
        Thread.ofVirtual()
                .name("gmch-service-log-" + service.id())
                .start(() -> readOutput(process, onLine));
        return process;
    }

    /** 等待 HTTP 就绪；未配置 ready 时给长驻进程一个短暂的启动观察窗口。 */
    public boolean awaitReady(
            RunConfiguration.Service service,
            Process process,
            Consumer<String> onLine
    ) throws InterruptedException {
        RunConfiguration.Ready ready = service.ready();
        if (ready == null || ready.url() == null || ready.url().isBlank()) {
            TimeUnit.MILLISECONDS.sleep(600);
            return process.isAlive();
        }

        long timeoutMs = ready.timeoutMs() == null || ready.timeoutMs() <= 0
                ? 120_000L
                : ready.timeoutMs();
        long deadline = System.currentTimeMillis() + timeoutMs;
        onLine.accept("等待就绪: " + ready.url());

        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) return false;
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(ready.url()))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                HttpResponse<Void> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.discarding()
                );
                // 有 HTTP 响应就说明监听已经建立；401/404 也可能是受保护的健康端点。
                if (response.statusCode() >= 200 && response.statusCode() < 500) {
                    return true;
                }
            } catch (IOException | IllegalArgumentException ignored) {
                // 端口尚未监听或 URL 暂不可用，继续轮询
            }
            TimeUnit.MILLISECONDS.sleep(500);
        }
        return false;
    }

    public void stopProcessTree(Process process) {
        if (process == null || !process.isAlive()) return;
        if (WINDOWS) {
            stopWindowsTree(process, false);
            try {
                if (process.waitFor(1500, TimeUnit.MILLISECONDS)) return;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            stopWindowsTree(process, true);
            try {
                process.waitFor(1500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            return;
        }

        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private List<String> executable(
            RunConfiguration.Service service,
            RunConfiguration.Command command,
            Path directory
    ) {
        if (command == null || command.kind() == null || command.kind().isBlank()) {
            throw new IllegalStateException("服务 " + service.id() + " 的步骤缺少 kind");
        }
        List<String> args = new ArrayList<>(
                command.args() == null ? List.of() : command.args()
        );
        String kind = command.kind().toLowerCase();
        return switch (kind) {
            case "maven" -> {
                Path wrapper = directory.resolve(WINDOWS ? "mvnw.cmd" : "mvnw");
                if (!Files.isRegularFile(wrapper)) {
                    throw new IllegalStateException("未找到 Maven Wrapper: " + wrapper);
                }
                args.add(0, wrapper.toString());
                yield args;
            }
            case "pnpm" -> {
                String pnpm = command.executable() == null || command.executable().isBlank()
                        ? (WINDOWS ? "pnpm.cmd" : "pnpm")
                        : command.executable();
                args.add(0, pnpm);
                yield args;
            }
            case "java" -> {
                if (command.jar() == null || command.jar().isBlank()) {
                    throw new IllegalStateException("java 步骤缺少 jar: " + service.id());
                }
                Path java = javaHome.isBlank()
                        ? Path.of(WINDOWS ? "java.exe" : "java")
                        : Path.of(javaHome, "bin", WINDOWS ? "java.exe" : "java");
                Path jar = directory.resolve(command.jar()).normalize();
                if (!jar.startsWith(workspace.root())) {
                    throw new IllegalStateException("jar 路径超出项目根目录: " + jar);
                }
                if (!Files.isRegularFile(jar)) {
                    throw new IllegalStateException("未找到 jar 产物: " + jar);
                }
                List<String> result = new ArrayList<>();
                result.add(java.toString());
                result.addAll(List.of("-Dfile.encoding=UTF-8", "-jar", jar.toString()));
                result.addAll(args);
                yield result;
            }
            case "command" -> {
                if (command.executable() == null || command.executable().isBlank()) {
                    throw new IllegalStateException("command 步骤缺少 executable: " + service.id());
                }
                args.add(0, command.executable());
                yield args;
            }
            default -> throw new IllegalStateException("不支持的步骤 kind: " + command.kind());
        };
    }

    /** Windows 只有经由 cmd.exe 才能可靠启动 .cmd/.bat，java 进程则直接执行。 */
    private List<String> platformCommand(List<String> executable) {
        if (!WINDOWS) return executable;
        String first = executable.getFirst();
        if (!first.toLowerCase().endsWith(".cmd") && !first.toLowerCase().endsWith(".bat")) {
            return executable;
        }
        String line = executable.stream()
                .map(ServiceProcesses::quoteForCmd)
                .collect(Collectors.joining(" "));
        return List.of("cmd.exe", "/d", "/s", "/c", "\"" + line + "\"");
    }

    private void stopWindowsTree(Process process, boolean force) {
        List<String> command = new ArrayList<>(List.of(
                "taskkill.exe", "/PID", Long.toString(process.pid()), "/T"
        ));
        if (force) command.add("/F");
        try {
            Process killer = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            killer.waitFor(3, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException ignored) {
            if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
            if (!force) process.destroy();
        }
    }

    private void readOutput(Process process, Consumer<String> onLine) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(),
                StandardCharsets.UTF_8
        ))) {
            String line;
            while ((line = reader.readLine()) != null) {
                onLine.accept(line);
            }
        } catch (IOException ignored) {
            // 进程被停止时流会关闭，这是正常路径
        }
    }

    private static String displayArgument(String value) {
        return value.contains(" ") ? "\"" + value + "\"" : value;
    }

    private static String quoteForCmd(String value) {
        if (value.isEmpty()) return "\"\"";
        if (value.matches("[A-Za-z0-9_./:=+,-]+")) return value;
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }
}
