package com.gmch.ide.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 工作区根目录。所有文件 API 都必须经过 {@link #resolve(String)}，
 * 保证规范化后的路径仍位于根目录内，避免目录穿越。
 */
@Component
public class Workspace {

    private volatile Path root;

    public Workspace(@Value("${app.workspace:}") String dir) {
        switchTo(dir);
    }

    public synchronized void switchTo(String dir) {
        if (dir == null || dir.isBlank()) {
            root = null;
            return;
        }
        Path path = Path.of(dir).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("工作区不存在: " + path);
        }
        root = path;
    }

    public Path root() {
        Path current = root;
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "尚未打开工作区");
        }
        return current;
    }

    public boolean isOpen() {
        return root != null;
    }

    public Path resolve(String relative) {
        Path base = root();
        Path target = base.resolve(relative).normalize();
        if (!target.startsWith(base)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "路径越界");
        }
        return target;
    }
}
