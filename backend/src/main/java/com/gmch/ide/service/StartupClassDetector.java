package com.gmch.ide.service;

import com.gmch.ide.config.Workspace;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自动扫描工作区中的 Java 启动类。
 *
 * <p>优先识别带 {@code @SpringBootApplication} 的类，其次识别包含
 * {@code public static void main(String[])} 的普通 Java 类。扫描只读取源码，
 * 不执行构建脚本；构建目录、依赖目录和版本库目录会被排除。</p>
 */
@Service
public class StartupClassDetector {

    private static final int MAX_DEPTH = 18;
    private static final int MAX_SOURCE_FILES = 20_000;
    private static final long MAX_SOURCE_BYTES = 2L * 1024 * 1024;
    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", ".gradle", ".idea", ".cache", ".tmp", "node_modules",
            "build", "target", "out", "dist", "bin", "generated"
    );
    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "\\bpackage\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;"
    );
    private static final Pattern TYPE_PATTERN = Pattern.compile(
            "\\b(?:public\\s+)?(?:final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+)*"
                    + "(?:class|record|enum)\\s+([A-Za-z_$][\\w$]*)"
    );
    private static final Pattern MAIN_PATTERN = Pattern.compile(
            "\\bpublic\\s+static\\s+(?:final\\s+)?void\\s+main\\s*\\(\\s*"
                    + "String\\s*(?:\\[\\s*\\]\\s*[A-Za-z_$][\\w$]*"
                    + "|[A-Za-z_$][\\w$]*\\s*\\[\\s*\\]|\\.\\.\\.\\s*[A-Za-z_$][\\w$]*)"
    );
    private static final Pattern SPRING_BOOT_PATTERN = Pattern.compile(
            "@\\s*(?:org\\.springframework\\.boot\\.autoconfigure\\.)?SpringBootApplication\\b"
    );

    private final Workspace workspace;

    public StartupClassDetector(Workspace workspace) {
        this.workspace = workspace;
    }

    public Map<String, Object> detect() throws IOException {
        if (!workspace.isOpen()) {
            return result(List.of());
        }

        return result(detectCandidates());
    }

    /**
     * 为构建入口解析启动目标。前端选中候选时按相对路径精确匹配；否则使用
     * 排序后的主候选，避免运行按钮再硬编码某一种构建工具。
     */
    public StartupTarget target(String requestedPath) throws IOException {
        if (!workspace.isOpen()) return null;
        List<Candidate> candidates = detectCandidates();
        if (candidates.isEmpty()) return null;

        Candidate selected = null;
        if (requestedPath != null && !requestedPath.isBlank()) {
            String normalized = requestedPath.replace('\\', '/');
            selected = candidates.stream()
                    .filter(candidate -> candidate.path().equals(normalized))
                    .findFirst()
                    .orElse(null);
        }
        if (selected == null) selected = candidates.getFirst();
        return new StartupTarget(
                selected.className(),
                selected.packageName(),
                selected.path(),
                selected.springBoot(),
                selected.buildSystem().wireName(),
                selected.modulePath()
        );
    }

    private List<Candidate> detectCandidates() throws IOException {
        Path root = workspace.root();
        List<Path> sources = collectSources(root);
        List<Candidate> candidates = new ArrayList<>();
        for (Path source : sources) {
            Candidate candidate = inspect(root, source);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }

        candidates.sort(Comparator
                .comparingInt(Candidate::score).reversed()
                .thenComparing(Candidate::className, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Candidate::path, String.CASE_INSENSITIVE_ORDER));
        return candidates;
    }

    /** 目录在进入前剪枝，避免大工程的 node_modules/build/.git 拖慢启动类扫描。 */
    private static List<Path> collectSources(Path root) throws IOException {
        List<Path> sources = new ArrayList<>();
        Files.walkFileTree(root, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && isIgnoredDirectory(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (sources.size() >= MAX_SOURCE_FILES) {
                    return FileVisitResult.TERMINATE;
                }
                if (attrs.isRegularFile()
                        && file.getFileName().toString().endsWith(".java")
                        && isSmallEnough(file)) {
                    sources.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) {
                // 权限不足或扫描期间被删除的条目不影响其他源码识别。
                return FileVisitResult.CONTINUE;
            }
        });
        return sources;
    }

    private Candidate inspect(Path root, Path source) {
        try {
            String code = stripComments(Files.readString(source, StandardCharsets.UTF_8));
            if (!MAIN_PATTERN.matcher(code).find()) {
                return null;
            }

            String fileName = source.getFileName().toString();
            String fileStem = fileName.substring(0, fileName.length() - ".java".length());
            String className = declaredType(code, fileStem);
            if (className == null) {
                return null;
            }

            Matcher packageMatcher = PACKAGE_PATTERN.matcher(code);
            String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
            String relativePath = root.relativize(source).toString().replace('\\', '/');
            BuildInfo build = findBuild(root, source.getParent());
            boolean springBoot = SPRING_BOOT_PATTERN.matcher(code).find();
            String normalizedPath = relativePath.toLowerCase(Locale.ROOT);

            int score = 0;
            if (springBoot) score += 1_000;
            if (className.endsWith("Application")) score += 100;
            if ("Application".equals(className)) score += 25;
            if (build.system() != BuildSystem.UNKNOWN) score += 30;
            score -= Math.min(40, relativePath.split("/").length * 2);
            if (normalizedPath.contains("/test/") || normalizedPath.contains("/demo/")) score -= 40;
            if (normalizedPath.contains("/extend/")) score -= 20;

            return new Candidate(
                    className,
                    packageName,
                    relativePath,
                    springBoot,
                    build.system(),
                    build.modulePath(),
                    score
            );
        } catch (IOException ignored) {
            // 单个文件不可读时继续扫描，避免整个工程识别失败。
            return null;
        }
    }

    private static String declaredType(String code, String expectedName) {
        Matcher matcher = TYPE_PATTERN.matcher(code);
        String first = null;
        while (matcher.find()) {
            String name = matcher.group(1);
            if (first == null) first = name;
            if (name.equals(expectedName)) return name;
        }
        return first;
    }

    private static String stripComments(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("//[^\\r\\n]*", "");
    }

    private static boolean isSmallEnough(Path path) {
        try {
            return Files.size(path) <= MAX_SOURCE_BYTES;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean isIgnoredDirectory(String name) {
        return IGNORED_DIRS.contains(name.toLowerCase(Locale.ROOT));
    }

    private static BuildInfo findBuild(Path root, Path start) {
        Path current = start;
        while (current != null && current.startsWith(root)) {
            if (Files.isRegularFile(current.resolve("pom.xml"))) {
                return new BuildInfo(BuildSystem.MAVEN, modulePath(root, current));
            }
            if (Files.isRegularFile(current.resolve("build.gradle"))
                    || Files.isRegularFile(current.resolve("build.gradle.kts"))) {
                return new BuildInfo(BuildSystem.GRADLE, modulePath(root, current));
            }
            if (current.equals(root)) break;
            current = current.getParent();
        }
        return new BuildInfo(BuildSystem.UNKNOWN, ".");
    }

    private static String modulePath(Path root, Path module) {
        if (module.equals(root)) return ".";
        return root.relativize(module).toString().replace('\\', '/');
    }

    private static Map<String, Object> result(List<Candidate> candidates) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", !candidates.isEmpty());
        result.put("primary", candidates.isEmpty() ? null : toMap(candidates.getFirst()));
        result.put("candidates", candidates.stream().map(StartupClassDetector::toMap).toList());
        return result;
    }

    private static Map<String, Object> toMap(Candidate candidate) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("className", candidate.className());
        map.put("packageName", candidate.packageName());
        map.put("path", candidate.path());
        map.put("springBoot", candidate.springBoot());
        map.put("buildSystem", candidate.buildSystem().wireName());
        map.put("modulePath", candidate.modulePath());
        return map;
    }

    private record Candidate(
            String className,
            String packageName,
            String path,
            boolean springBoot,
            BuildSystem buildSystem,
            String modulePath,
            int score
    ) {
    }

    public record StartupTarget(
            String className,
            String packageName,
            String path,
            boolean springBoot,
            String buildSystem,
            String modulePath
    ) {
        public String fullyQualifiedName() {
            return packageName == null || packageName.isBlank()
                    ? className
                    : packageName + "." + className;
        }
    }

    private record BuildInfo(BuildSystem system, String modulePath) {
    }

    private enum BuildSystem {
        GRADLE("gradle"),
        MAVEN("maven"),
        UNKNOWN("unknown");

        private final String wireName;

        BuildSystem(String wireName) {
            this.wireName = wireName;
        }

        private String wireName() {
            return wireName;
        }
    }
}
