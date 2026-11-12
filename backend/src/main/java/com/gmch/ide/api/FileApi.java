package com.gmch.ide.api;

import com.gmch.ide.config.Workspace;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api")
public class FileApi {

    /** 工作区里允许展示/编辑的扩展名，避免把二进制与构建产物带进列表 */
    private static final Set<String> EDITABLE_SUFFIXES =
            Set.of(".java", ".gradle", ".properties", ".md", ".kts", ".yml", ".yaml", ".xml");

    private static final Set<String> IGNORED_DIRS =
            Set.of("build", ".gradle", ".git", "out", "node_modules", ".idea", "target");

    /** 防止误选大目录（如用户主目录）时遍历过深 */
    private static final int MAX_DEPTH = 12;
    private static final int MAX_FILES = 5000;

    private final Workspace workspace;

    public FileApi(Workspace workspace) {
        this.workspace = workspace;
    }

    @GetMapping("/files")
    public List<Map<String, String>> list() throws IOException {
        Path root = workspace.root();
        List<Map<String, String>> result = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(root, MAX_DEPTH)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> isEditable(root, path))
                    .limit(MAX_FILES)
                    .forEach(path -> {
                        String relative = root.relativize(path).toString().replace('\\', '/');
                        result.add(Map.of("path", relative, "name", path.getFileName().toString()));
                    });
        }

        result.sort(Comparator.comparing(entry -> entry.get("path")));
        return result;
    }

    @GetMapping(value = "/file", produces = MediaType.TEXT_PLAIN_VALUE)
    public String read(@RequestParam String path) throws IOException {
        Path file = workspace.resolve(path);
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在");
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @PutMapping(value = "/file", consumes = MediaType.TEXT_PLAIN_VALUE)
    public void write(@RequestParam String path, @RequestBody String body) throws IOException {
        Path file = workspace.resolve(path);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, body, StandardCharsets.UTF_8);
    }

    private boolean isEditable(Path root, Path path) {
        String relative = root.relativize(path).toString().replace('\\', '/');
        int slash = relative.indexOf('/');
        if (slash > 0 && IGNORED_DIRS.contains(relative.substring(0, slash))) {
            return false;
        }
        String lower = relative.toLowerCase();
        return EDITABLE_SUFFIXES.stream().anyMatch(lower::endsWith);
    }
}
