package com.gmch.ide.api;

import com.gmch.ide.service.BuildRunner;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/run")
public class RunApi {

    private final BuildRunner runner;
    /** 每个构建任务一个虚拟线程，阻塞在进程读取上不占用平台线程 */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public RunApi(BuildRunner runner) {
        this.runner = runner;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(
            @RequestParam(defaultValue = "run") String task,
            @RequestParam(required = false) String startupPath
    ) {
        SseEmitter emitter = new SseEmitter(0L);

        executor.submit(() -> {
            try {
                send(emitter, "start", task);
                int code = runner.run(task, startupPath, line -> send(emitter, "log", line));
                send(emitter, "exit", Integer.toString(code));
                emitter.complete();
            } catch (Exception error) {
                send(emitter, "log", "ERROR " + error.getMessage());
                send(emitter, "exit", "-1");
                emitter.complete();
            }
        });

        return emitter;
    }

    @PostMapping("/stop")
    public Map<String, Object> stop() {
        return Map.of("stopped", runner.stop());
    }

    private void send(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.TEXT_PLAIN));
        } catch (Exception ignored) {
            // 客户端已断开：忽略，任务会由 stop/超时清理
        }
    }
}
