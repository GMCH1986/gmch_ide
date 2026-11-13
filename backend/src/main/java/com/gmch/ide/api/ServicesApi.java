package com.gmch.ide.api;

import com.gmch.ide.service.ServicesRunner;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/** 多服务运行配置与事件流。旧的单任务 {@code /api/run} 保持不变。 */
@RestController
@RequestMapping("/api/services")
public class ServicesApi {

    private final ServicesRunner runner;

    public ServicesApi(ServicesRunner runner) {
        this.runner = runner;
    }

    @GetMapping
    public Map<String, Object> status() {
        return runner.status();
    }

    @PostMapping("/start")
    public Map<String, Object> start() {
        return runner.start();
    }

    @PostMapping("/stop")
    public Map<String, Object> stop() {
        return runner.stop();
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return runner.events();
    }
}
