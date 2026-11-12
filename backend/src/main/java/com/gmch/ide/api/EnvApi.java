package com.gmch.ide.api;

import com.gmch.ide.config.Workspace;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.util.Map;

@RestController
public class EnvApi {

    private final Workspace workspace;

    public EnvApi(Workspace workspace) {
        this.workspace = workspace;
    }

    @GetMapping("/api/env")
    public Map<String, Object> env() {
        return Map.of(
                "javaVersion", System.getProperty("java.version", "unknown"),
                "javaHome", System.getProperty("java.home", "unknown"),
                "os", System.getProperty("os.name", "unknown"),
                "gradleWrapper", hasWrapper()
        );
    }

    private boolean hasWrapper() {
        if (!workspace.isOpen()) {
            return false;
        }
        return Files.isRegularFile(workspace.root().resolve("gradlew"))
                || Files.isRegularFile(workspace.root().resolve("gradlew.bat"));
    }
}
