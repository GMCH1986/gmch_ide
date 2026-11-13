package com.gmch.ide.service;

import com.gmch.ide.config.Workspace;
import com.gmch.ide.service.StartupClassDetector.StartupTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 使用 Maven Wrapper 编译或启动当前识别的 Java 模块。
 *
 * <p>运行入口会从启动类所在模块向上查找最近的 {@code mvnw} / {@code mvnw.cmd}，
 * 因此 monorepo 中无需把 IDE 工作区固定为 Maven 根目录。多模块工程通过 Maven
 * reactor 构建目标模块；Spring Boot 直接启动打包后的可执行 jar，普通 main 类
 * 使用 exec-maven-plugin。</p>
 */
@Service
public class MavenRunner {

    private static final Logger log = LoggerFactory.getLogger(MavenRunner.class);
    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase()
            .contains("win");

    private final Workspace workspace;
    private final String javaHome;
    private final AtomicReference<Process> current = new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean stopRequested =
            new java.util.concurrent.atomic.AtomicBoolean();

    public MavenRunner(Workspace workspace, @Value("${app.java-home:}") String javaHome) {
        this.workspace = workspace;
        String environmentJavaHome = System.getenv("JAVA_HOME");
        this.javaHome = javaHome == null || javaHome.isBlank()
                ? (environmentJavaHome == null ? "" : environmentJavaHome)
                : javaHome;
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "maven-shutdown"));
    }

    public int run(String task, StartupTarget target, Consumer<String> onLine)
            throws IOException, InterruptedException {
        if (!"run".equals(task) && !"compileJava".equals(task)) {
            throw new IllegalArgumentException("Maven 不支持的任务: " + task);
        }
        stop();
        stopRequested.set(false);

        Path moduleDir = resolveModuleDirectory(target);
        Path wrapper = findWrapper(moduleDir);
        if (wrapper == null) {
            throw new IllegalStateException(
                    "当前 Maven 项目缺少 Maven Wrapper（mvnw / mvnw.cmd），请从模块目录到项目根目录检查"
            );
        }
        ensureJdk();

        Path mavenRoot = wrapper.getParent().normalize();
        Path relativeModule = mavenRoot.relativize(moduleDir);
        if ("compileJava".equals(task)) {
            List<String> args = commonArgs();
            addReactorSelection(args, relativeModule);
            args.add("compile");
            return runMaven(wrapper, mavenRoot, task, target, args, onLine);
        }

        /*
         * 子模块无法单独解析父 BOM 和同仓库兄弟模块，因此构建阶段必须从
         * Wrapper 根目录通过 reactor 选择目标模块及其依赖，再从模块产物启动。
         */
        List<String> prepareArgs = commonArgs();
        addReactorSelection(prepareArgs, relativeModule);
        prepareArgs.add("package");
        int prepareCode = runMaven(wrapper, mavenRoot, task, target, prepareArgs, onLine);
        if (prepareCode != 0 || stopRequested.get()) return prepareCode == 0 ? 130 : prepareCode;

        /*
         * Maven 多模块工程的 BOM 通常不在目标模块的依赖图中，单独进入子模块
         * 调用 spring-boot:run 仍可能解析失败。Spring Boot 打包后的 jar 已包含
         * 全部依赖，直接从 reactor 产物启动最稳定，也保持运行/停止语义一致。
         */
        if (target != null && target.springBoot()) {
            return runJavaJar(findRunnableJar(moduleDir), moduleDir, target, onLine);
        }

        List<String> runArgs = commonArgs();
        if (target != null) {
            runArgs.add("org.codehaus.mojo:exec-maven-plugin:3.5.0:java");
            runArgs.add("-Dexec.mainClass=" + target.fullyQualifiedName());
        } else {
            runArgs.add("spring-boot:run");
        }
        return runMaven(wrapper, moduleDir, task, target, runArgs, onLine);
    }

    /** 执行 Maven 构建工具导航中的白名单生命周期任务。 */
    public int runBuildCommand(
            Path projectRoot,
            Path moduleRoot,
            String goal,
            Consumer<String> onLine
    ) throws IOException, InterruptedException {
        if (!Set.of("clean", "validate", "compile", "test", "package", "verify", "install")
                .contains(goal)) {
            throw new IllegalArgumentException("不支持的 Maven 任务: " + goal);
        }
        Path workspaceRoot = workspace.root();
        Path project = projectRoot.toAbsolutePath().normalize();
        Path module = moduleRoot.toAbsolutePath().normalize();
        if (!project.startsWith(workspaceRoot) || !Files.isDirectory(project)) {
            throw new IllegalStateException("Maven 项目目录无效: " + project);
        }
        if (!module.startsWith(project) || !Files.isDirectory(module)) {
            throw new IllegalStateException("Maven 模块目录无效: " + module);
        }

        Path wrapper = findWrapper(project);
        if (wrapper == null || !wrapper.getParent().normalize().equals(project)) {
            throw new IllegalStateException(
                    "当前 Maven 项目缺少 Maven Wrapper（mvnw / mvnw.cmd）: " + project
            );
        }

        stop();
        stopRequested.set(false);
        ensureJdk();

        List<String> args = new ArrayList<>();
        args.add("-q");
        args.add("-B");
        args.add("-Dstyle.color=never");
        String relativeModule = project.relativize(module).toString().replace('\\', '/');
        if (!relativeModule.isBlank()) {
            args.add("-pl");
            args.add(relativeModule);
            if (!"clean".equals(goal) && !"validate".equals(goal)) {
                args.add("-am");
            }
        }
        args.add(goal);
        return runMaven(wrapper, project, goal, null, args, onLine);
    }

    private static List<String> commonArgs() {
        List<String> args = new ArrayList<>();
        args.add("-q");
        args.add("-DskipTests");
        args.add("-Dstyle.color=never");
        return args;
    }

    private static void addReactorSelection(List<String> args, Path relativeModule) {
        String module = relativeModule.toString();
        if (module.isBlank()) return;
        args.add("-pl");
        args.add(module.replace('\\', '/'));
        args.add("-am");
    }

    private int runMaven(
            Path wrapper,
            Path directory,
            String task,
            StartupTarget target,
            List<String> args,
            Consumer<String> onLine
    ) throws IOException, InterruptedException {
        if (stopRequested.get()) return 130;

        String display = displayCommand(wrapper, args);
        onLine.accept("$ " + display);
        log.info("Maven build start: task={}, dir={}, target={}",
                task, directory, target == null ? "auto" : target.fullyQualifiedName());

        ProcessBuilder builder = new ProcessBuilder(platformCommand(wrapper, args));
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        builder.redirectInput(ProcessBuilder.Redirect.PIPE);
        if (!javaHome.isBlank()) {
            builder.environment().put("JAVA_HOME", javaHome);
            String pathKey = WINDOWS ? "Path" : "PATH";
            String currentPath = builder.environment().getOrDefault(pathKey, "");
            builder.environment().put(
                    pathKey,
                    Path.of(javaHome, "bin") + java.io.File.pathSeparator + currentPath
            );
        }

        Process process = builder.start();
        process.getOutputStream().close();
        current.set(process);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(),
                StandardCharsets.UTF_8
        ))) {
            String line;
            while ((line = reader.readLine()) != null) {
                onLine.accept(line);
            }
            return process.waitFor();
        } finally {
            current.compareAndSet(process, null);
            if (process.isAlive()) stopProcessTree(process);
        }
    }

    /** 在模块的 target 目录中选择带 Main-Class 的最新可执行 jar。 */
    private static Path findRunnableJar(Path moduleDir) throws IOException {
        Path target = moduleDir.resolve("target");
        if (!Files.isDirectory(target)) {
            throw new IllegalStateException("Maven 打包成功，但未找到 target 目录: " + target);
        }
        Path selected = null;
        long selectedTime = Long.MIN_VALUE;
        try (var files = Files.list(target)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString().toLowerCase();
                if (!name.endsWith(".jar") || name.contains("-sources")
                        || name.contains("-javadoc")) {
                    continue;
                }
                try (var jar = new java.util.jar.JarFile(file.toFile())) {
                    var manifest = jar.getManifest();
                    String mainClass = manifest == null
                            ? null
                            : manifest.getMainAttributes().getValue("Main-Class");
                    if (mainClass == null || mainClass.isBlank()) continue;
                    long modified = Files.getLastModifiedTime(file).toMillis();
                    if (modified > selectedTime) {
                        selected = file;
                        selectedTime = modified;
                    }
                } catch (IOException ignored) {
                    // 非可读或非标准 jar 跳过，继续查找其他产物。
                }
            }
        }
        if (selected == null) {
            throw new IllegalStateException(
                    "未在 " + target + " 找到带 Main-Class 的可执行 jar，请检查打包插件配置"
            );
        }
        return selected;
    }

    private int runJavaJar(
            Path jar,
            Path moduleDir,
            StartupTarget target,
            Consumer<String> onLine
    ) throws IOException, InterruptedException {
        if (stopRequested.get()) return 130;
        Path javaExecutable = Path.of(javaHome, "bin", WINDOWS ? "java.exe" : "java");
        List<String> command = List.of(javaExecutable.toString(), "-jar", jar.toString());
        String display = "java -jar " + moduleDir.relativize(jar).toString().replace('\\', '/');
        onLine.accept("$ " + display);
        log.info("Maven jar start: dir={}, target={}, jar={}",
                moduleDir, target == null ? "auto" : target.fullyQualifiedName(), jar);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(moduleDir.toFile());
        builder.redirectErrorStream(true);
        builder.redirectInput(ProcessBuilder.Redirect.PIPE);
        builder.environment().put("JAVA_HOME", javaHome);
        String pathKey = WINDOWS ? "Path" : "PATH";
        String currentPath = builder.environment().getOrDefault(pathKey, "");
        builder.environment().put(
                pathKey,
                Path.of(javaHome, "bin") + java.io.File.pathSeparator + currentPath
        );

        Process process = builder.start();
        process.getOutputStream().close();
        current.set(process);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(),
                StandardCharsets.UTF_8
        ))) {
            String line;
            while ((line = reader.readLine()) != null) {
                onLine.accept(line);
            }
            return process.waitFor();
        } finally {
            current.compareAndSet(process, null);
            if (process.isAlive()) stopProcessTree(process);
        }
    }

    public boolean stop() {
        stopRequested.set(true);
        Process process = current.getAndSet(null);
        if (process == null || !process.isAlive()) return false;
        stopProcessTree(process);
        return true;
    }

    private Path resolveModuleDirectory(StartupTarget target) {
        Path root = workspace.root();
        String modulePath = target == null ? "." : target.modulePath();
        Path module = workspace.resolve(modulePath == null || modulePath.isBlank() ? "." : modulePath)
                .normalize();
        if (!module.startsWith(root) || !Files.isDirectory(module)) {
            throw new IllegalStateException("启动类所在模块目录无效: " + modulePath);
        }
        return module;
    }

    /** 从模块目录逐级向上查找 Maven Wrapper，兼容 apps/backend/ruoyi-admin 这类 monorepo 布局。 */
    private Path findWrapper(Path moduleDir) {
        Path currentDir = moduleDir;
        Path root = workspace.root();
        while (currentDir != null && currentDir.startsWith(root)) {
            Path cmd = currentDir.resolve("mvnw.cmd");
            Path unix = currentDir.resolve("mvnw");
            if (WINDOWS && Files.isRegularFile(cmd)) return cmd;
            if (!WINDOWS && Files.isRegularFile(unix)) return unix;
            if (Files.isRegularFile(cmd)) return cmd;
            if (Files.isRegularFile(unix)) return unix;
            if (currentDir.equals(root)) break;
            currentDir = currentDir.getParent();
        }
        return null;
    }

    private void ensureJdk() {
        Path javac = javaHome == null || javaHome.isBlank()
                ? null
                : Path.of(javaHome, "bin", WINDOWS ? "javac.exe" : "javac");
        if (javac == null || !Files.isRegularFile(javac)) {
            throw new IllegalStateException(
                    "未找到带 javac 的 JDK，请安装 JDK 17+ 或设置 JAVA_HOME。当前值: " + javaHome
            );
        }
    }

    private static List<String> platformCommand(Path wrapper, List<String> args) {
        List<String> executable = new ArrayList<>();
        executable.add(wrapper.toString());
        executable.addAll(args);
        if (!WINDOWS) return executable;

        String line = executable.stream()
                .map(MavenRunner::quoteForCmd)
                .collect(Collectors.joining(" "));
        return List.of("cmd.exe", "/d", "/s", "/c", "\"" + line + "\"");
    }

    private static String displayCommand(Path wrapper, List<String> args) {
        return wrapper.getFileName() + " " + String.join(" ", args);
    }

    private static String quoteForCmd(String value) {
        if (value.isEmpty()) return "\"\"";
        if (value.matches("[A-Za-z0-9_./:=+,-]+")) return value;
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private static void stopProcessTree(Process process) {
        if (!WINDOWS) {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
            return;
        }
        try {
            Process killer = new ProcessBuilder(
                    "taskkill.exe", "/PID", Long.toString(process.pid()), "/T", "/F"
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            killer.waitFor(5, TimeUnit.SECONDS);
        } catch (IOException ignored) {
            process.destroy();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroy();
        }
    }
}
