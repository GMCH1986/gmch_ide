package com.gmch.ide.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthApi {

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "version", "0.1.0");
    }
}
