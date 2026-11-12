package com.gmch.ide.api;

import com.gmch.ide.config.Workspace;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/workspace")
public class WorkspaceApi {

    private final Workspace workspace;

    public WorkspaceApi(Workspace workspace) {
        this.workspace = workspace;
    }

    @GetMapping
    public Map<String, String> current() {
        if (!workspace.isOpen()) {
            return Map.of();
        }
        return Map.of(
                "path", workspace.root().toString(),
                "name", workspace.root().getFileName().toString()
        );
    }

    @PutMapping
    public Map<String, String> switchTo(@RequestBody Map<String, String> body) {
        workspace.switchTo(body.get("path"));
        return current();
    }
}
