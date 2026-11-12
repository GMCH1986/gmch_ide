package com.gmch.ide.service;

import com.gmch.ide.config.Workspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 执行工作区自带的 Gradle Wrapper。
 * 同一时刻只允许一个任务；停止时连同子进程树一起销毁，避免 daemon 之外残留 java 进程。
 */
@Service
public class GradleRunner {

    private static final Logger log = LoggerFactory.getLogger(GradleRunner.class);

    /** 只允许白名单任务，避免把任意命令注入到构建进程 */
    private static final Set<String> ALLOWED_TASKS = Set.of("run", "compileJava", "test", "build");

    private final Workspace workspace;
    private final AtomicReference<Process> current = new AtomicReference<>();

    public GradleRunner(Workspace workspace) {
        this.workspace = workspace;
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "gradle-shutdown"));
    }

    public int run(String task, Consumer<String> onLine) throws IOException, InterruptedException {
        if (!ALLOWED_TASKS.contains(task)) {
            throw new IllegalArgumentException("不支持的任务: " + task);
        }
        stop();

        Path root = workspace.root();
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path wrapper = root.resolve(windows ? "gradlew.bat" : "gradlew");
        if (!Files.isRegularFile(wrapper)) {
            throw new IllegalStateException("工作区缺少 Gradle Wrapper（gradlew / gradlew.bat）");
        }

        List<String> command = windows
                ? List.of("cmd.exe", "/c", "gradlew.bat", task, "--console=plain", "-q")
                : List.of(wrapper.toString(), task, "--console=plain", "-q");

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true);
        builder.environment().putIfAbsent(
                "JAVA_TOOL_OPTIONS",
                "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
        );

        Process process = builder.start();
        current.set(process);
        log.info("Gradle started: task={}, pid={}", task, process.pid());

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            reader.lines().forEach(onLine);
            return process.waitFor();
        } catch (RuntimeException error) {
            // 客户端断开等异常：确保构建进程被终止
            stop();
            throw error;
        } finally {
            current.compareAndSet(process, null);
        }
    }

    public boolean stop() {
        Process process = current.getAndSet(null);
        if (process == null) {
            return false;
        }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        log.info("Gradle stopped: pid={}", process.pid());
        return true;
    }
}
