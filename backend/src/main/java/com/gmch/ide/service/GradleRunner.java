package com.gmch.ide.service;

import com.gmch.ide.config.Workspace;
import org.gradle.tooling.BuildCancelledException;
import org.gradle.tooling.BuildException;
import org.gradle.tooling.BuildLauncher;
import org.gradle.tooling.CancellationTokenSource;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通过 Gradle Tooling API 执行工作区任务。
 *
 * <p>不用 ProcessBuilder 调 gradlew：Electron 以无控制台方式启动 sidecar 后，
 * cmd.exe / gradle wrapper 会为新控制台创建 conhost 窗口，表现为点「运行」时闪黑框。
 * Tooling API 在进程内驱动 Gradle daemon，不产生控制台窗口，同时保留流式输出与取消能力。</p>
 */
@Service
public class GradleRunner {

    private static final Logger log = LoggerFactory.getLogger(GradleRunner.class);

    /** 只允许白名单任务，避免把任意命令注入到构建进程 */
    private static final Set<String> ALLOWED_TASKS = Set.of(
            "run", "bootRun", "compileJava", "test", "build",
            "clean", "assembleDebug", "assembleRelease"
    );
    private static final Pattern GRADLE_ARCHIVE = Pattern.compile(
            "gradle-(\\d+(?:\\.\\d+)+(?:-[A-Za-z0-9.]+)?)-(?:bin|all)\\.zip",
            Pattern.CASE_INSENSITIVE
    );

    private final Workspace workspace;
    private final String javaHome;
    private final AtomicReference<CancellationTokenSource> current = new AtomicReference<>();

    public GradleRunner(Workspace workspace, @Value("${app.java-home:}") String javaHome) {
        this.workspace = workspace;
        String environmentJavaHome = System.getenv("JAVA_HOME");
        this.javaHome = javaHome == null || javaHome.isBlank()
                ? (environmentJavaHome == null ? "" : environmentJavaHome)
                : javaHome;
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "gradle-shutdown"));
    }

    public int run(String task, Consumer<String> onLine) throws IOException, InterruptedException {
        return run(task, workspace.root(), onLine);
    }

    public int run(String task, Path projectDir, Consumer<String> onLine)
            throws IOException, InterruptedException {
        if (!isAllowedTask(task)) {
            throw new IllegalArgumentException("不支持的任务: " + task);
        }
        stop();

        Path root = workspace.root();
        Path gradleProject = projectDir == null ? root : projectDir.normalize();
        if (!gradleProject.startsWith(root) || !Files.isDirectory(gradleProject)) {
            throw new IllegalStateException("Gradle 项目目录无效: " + gradleProject);
        }
        if (!Files.isRegularFile(gradleProject.resolve("gradlew"))
                && !Files.isRegularFile(gradleProject.resolve("gradlew.bat"))) {
            throw new IllegalStateException(
                    "当前 Gradle 模块缺少 Gradle Wrapper（gradlew / gradlew.bat）: " + gradleProject
            );
        }
        Path javac = javaHome == null || javaHome.isBlank()
                ? null
                : Path.of(javaHome, "bin", "javac");
        if (javac == null
                || (!Files.isRegularFile(javac) && !Files.isRegularFile(Path.of(javaHome, "bin", "javac.exe")))) {
            throw new IllegalStateException(
                    "未找到带 javac 的 JDK，请安装 JDK 17+ 或设置 JAVA_HOME。当前值: " + javaHome
            );
        }

        log.info("Gradle build start: task={}, dir={}", task, gradleProject);
        // 让 Gradle 使用带 javac 的 JDK 启动 daemon（sidecar 自带的 jlink 运行时没有编译器）
        System.setProperty("org.gradle.java.home", javaHome);
        CancellationTokenSource cancellation = GradleConnector.newCancellationTokenSource();
        // 在连接 Gradle 之前注册取消令牌，避免连接阶段点「停止」无效。
        current.set(cancellation);
        LineOutputStream output = new LineOutputStream(onLine);
        GradleConnector connector = GradleConnector.newConnector()
                .forProjectDirectory(gradleProject.toFile());
        String fallbackVersion = unavailableWrapperVersion(gradleProject);
        if (fallbackVersion == null) {
            connector.useBuildDistribution();
        } else {
            log.warn(
                    "Gradle Wrapper distribution unavailable, falling back to Gradle {}: dir={}",
                    fallbackVersion,
                    gradleProject
            );
            onLine.accept("Gradle Wrapper 分发地址不可用，回退到 Gradle " + fallbackVersion);
            connector.useGradleVersion(fallbackVersion);
        }

        try (ProjectConnection connection = connector.connect()) {
            if (current.get() != cancellation) {
                log.info("Gradle build cancelled during connection");
                return 130;
            }
            BuildLauncher launcher = connection.newBuild();
            launcher.forTasks(task);
            List<String> arguments = new ArrayList<>(List.of("--console=plain", "-q"));
            if (javaHome != null && !javaHome.isBlank()) {
                // 工程可能从 macOS 等机器复制而来，gradle.properties 中的 org.gradle.java.home
                // 在本机通常无效；命令行覆盖保证 IDE 选中的 JDK 真正用于 daemon。
                arguments.add("-Dorg.gradle.java.home=" + javaHome);
            }
            launcher.withArguments(arguments);
            launcher.withCancellationToken(cancellation.token());
            launcher.setStandardOutput(output);
            launcher.setStandardError(output);
            launcher.run();
            return 0;
        } catch (BuildCancelledException cancelled) {
            log.info("Gradle build cancelled");
            return 130;
        } catch (BuildException failure) {
            log.warn("Gradle build failed: {}", failure.getMessage());
            return 1;
        } finally {
            // Tooling API 未必关闭输出流；主动冲刷，避免丢失最后一条无换行输出
            output.flush();
            current.compareAndSet(cancellation, null);
        }
    }

    /** Gradle 支持 :app:task 形式的模块任务，但只允许白名单叶子任务。 */
    private static boolean isAllowedTask(String task) {
        if (task == null || !task.matches(":?[A-Za-z][A-Za-z0-9_-]*(?::[A-Za-z][A-Za-z0-9_-]*)*")) {
            return false;
        }
        String leaf = task.substring(task.lastIndexOf(':') + 1);
        return ALLOWED_TASKS.contains(leaf);
    }

    /**
     * 识别指向本机不存在文件的 Wrapper 分发地址，并从归档名提取 Gradle 版本。
     *
     * <p>跨平台复制工程时经常残留 file:///Users/... 或 file:///E:/... 地址。
     * Tooling API 对这类地址无法下载，因此改为按相同版本从官方分发源获取。</p>
     */
    private static String unavailableWrapperVersion(Path projectDir) {
        Path propertiesFile = projectDir.resolve("gradle/wrapper/gradle-wrapper.properties");
        if (!Files.isRegularFile(propertiesFile)) return null;

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(propertiesFile)) {
            properties.load(input);
        } catch (IOException error) {
            log.warn("Unable to read Gradle Wrapper properties: {}", propertiesFile, error);
            return null;
        }

        String distributionUrl = properties.getProperty("distributionUrl", "").trim();
        if (!distributionUrl.regionMatches(true, 0, "file:", 0, 5)) return null;
        if (localDistributionExists(distributionUrl)) return null;
        return gradleVersion(distributionUrl);
    }

    private static boolean localDistributionExists(String distributionUrl) {
        try {
            URI uri = URI.create(distributionUrl);
            return Files.isRegularFile(Path.of(uri));
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static String gradleVersion(String distributionUrl) {
        Matcher matcher = GRADLE_ARCHIVE.matcher(distributionUrl.replace('\\', '/'));
        return matcher.find() ? matcher.group(1) : null;
    }

    public boolean stop() {
        CancellationTokenSource cancellation = current.getAndSet(null);
        if (cancellation == null) {
            return false;
        }
        cancellation.cancel();
        return true;
    }

    /** 把 Tooling API 的字节流按行切分并回调，避免整段缓冲 */
    private static final class LineOutputStream extends OutputStream {

        private final Consumer<String> onLine;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        private LineOutputStream(Consumer<String> onLine) {
            this.onLine = onLine;
        }

        @Override
        public synchronized void write(int value) {
            buffer.write(value);
            drain();
        }

        @Override
        public synchronized void write(byte[] data, int offset, int length) {
            buffer.write(data, offset, length);
            drain();
        }

        @Override
        public synchronized void flush() {
            if (buffer.size() > 0) {
                onLine.accept(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
                buffer.reset();
            }
        }

        private void drain() {
            byte[] data = buffer.toByteArray();
            int start = 0;
            for (int i = 0; i < data.length; i++) {
                if (data[i] != '\n') {
                    continue;
                }
                String line = new String(data, start, i - start, StandardCharsets.UTF_8);
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                onLine.accept(line);
                start = i + 1;
            }
            buffer.reset();
            if (start < data.length) {
                buffer.write(data, start, data.length - start);
            }
        }
    }
}
