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
import java.nio.file.LinkOption;
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

    /**
     * 这些目录属于工具缓存或版本库元数据，不属于工程内容，避免文件树被噪声淹没。
     * build/out/target/dist 等构建目录不再排除，由前端按“构建产物”展示。
     */
    private static final Set<String> IGNORED_DIRS =
            Set.of(".git", ".gradle", ".idea", "node_modules", ".tmp", ".cache");

    /** 常见二进制文件只展示、不读取，避免把乱码送进编辑器。 */
    private static final Set<String> BINARY_SUFFIXES = Set.of(
            ".jar", ".class", ".bin", ".zip", ".gz", ".tar", ".7z",
            ".exe", ".dll", ".so", ".dylib", ".png", ".jpg", ".jpeg",
            ".gif", ".ico", ".webp", ".pdf", ".woff", ".woff2", ".ttf",
            ".otf", ".mp3", ".mp4", ".db", ".lock"
    );

    /** 防止误选大目录（如用户主目录）时遍历过深。 */
    private static final int MAX_DEPTH = 12;
    private static final int MAX_FILES = 5000;

    private final Workspace workspace;

    public FileApi(Workspace workspace) {
        this.workspace = workspace;
    }

    /**
     * 返回工作区中的全部可见文件。前端根据 path 构造目录树，因此这里保持扁平结构，
     * 同时附带 binary 标记，供界面决定是否允许打开。
     */
    @GetMapping("/files")
    public List<Map<String, Object>> list() throws IOException {
        Path root = workspace.root();
        List<Map<String, Object>> result = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(root, MAX_DEPTH)) {
            // Files.walk 默认不跟随目录链接；这里也排除指向工作区外的文件链接
            stream.filter(path -> !path.equals(root))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !isIgnored(root, path))
                    .limit(MAX_FILES)
                    .forEach(path -> {
                        String relative = root.relativize(path).toString().replace('\\', '/');
                        result.add(Map.<String, Object>of(
                                "path", relative,
                                "name", path.getFileName().toString(),
                                "binary", isBinary(relative)
                        ));
                    });
        }

        result.sort(Comparator.comparing(
                entry -> (String) entry.get("path"),
                String.CASE_INSENSITIVE_ORDER
        ));
        return result;
    }

    @GetMapping(value = "/file", produces = MediaType.TEXT_PLAIN_VALUE)
    public String read(@RequestParam String path) throws IOException {
        Path file = workspace.resolve(path);
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在");
        }
        String relative = path.replace('\\', '/');
        if (isBinary(relative)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "二进制文件不支持预览");
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

    private boolean isIgnored(Path root, Path path) {
        String relative = root.relativize(path).toString().replace('\\', '/');
        String[] parts = relative.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (IGNORED_DIRS.contains(parts[i])) {
                return true;
            }
        }
        return false;
    }

    private boolean isBinary(String relative) {
        String lower = relative.toLowerCase();
        return BINARY_SUFFIXES.stream().anyMatch(lower::endsWith);
    }
}
