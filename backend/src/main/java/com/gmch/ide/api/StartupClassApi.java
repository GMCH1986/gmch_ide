package com.gmch.ide.api;

import com.gmch.ide.service.StartupClassDetector;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/** 启动类识别：只扫描源码，不执行 Gradle/Maven，也不修改项目配置。 */
@RestController
@RequestMapping("/api/startup-class")
public class StartupClassApi {

    private final StartupClassDetector detector;

    public StartupClassApi(StartupClassDetector detector) {
        this.detector = detector;
    }

    @GetMapping
    public Map<String, Object> detect() throws IOException {
        return detector.detect();
    }
}
