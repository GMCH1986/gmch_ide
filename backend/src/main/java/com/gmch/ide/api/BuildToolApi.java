package com.gmch.ide.api;

import com.gmch.ide.service.BuildRunner;
import com.gmch.ide.service.BuildToolDetector;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Maven / Gradle 工程探测与任务执行入口。
 *
 * <p>前端只传工程、模块和任务标识；服务端会重新扫描工作区并验证目标来自当前
 * 探测结果，避免构建工具面板成为任意命令执行入口。</p>
 */
@RestController
@RequestMapping("/api/build-tools")
public class BuildToolApi {

    private final BuildToolDetector detector;
    private final BuildRunner runner;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public BuildToolApi(BuildToolDetector detector, BuildRunner runner) {
        this.detector = detector;
        this.runner = runner;
    }

    @GetMapping
    public BuildToolDetector.BuildToolsResult list() throws Exception {
        return detector.detect();
    }

    @GetMapping(value = "/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(
            @RequestParam String system,
            @RequestParam String projectPath,
            @RequestParam String modulePath,
            @RequestParam String task
    ) {
        SseEmitter emitter = new SseEmitter(0L);
        executor.submit(() -> {
            try {
                BuildToolDetector.BuildCommandTarget target = detector.requireCommand(
                        system,
                        projectPath,
                        modulePath,
                        task
                );
                send(emitter, "start", target.commandLine());
                int code = runner.runBuildCommand(
                        target,
                        line -> send(emitter, "log", line)
                );
                send(emitter, "exit", Integer.toString(code));
                emitter.complete();
            } catch (Exception error) {
                send(emitter, "log", "ERROR " + message(error));
                send(emitter, "exit", "-1");
                emitter.complete();
            }
        });
        return emitter;
    }

    private static String message(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message;
    }

    private void send(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.TEXT_PLAIN));
        } catch (Exception ignored) {
            // 客户端已断开时由停止接口统一终止构建进程。
        }
    }
}