package com.gmch.ide.api;

import com.gmch.ide.config.Workspace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 新建 Java 工程：生成一个带 Gradle Wrapper 的最小工程（Hello World），
 * 创建成功后把工作区切换到该工程。
 */
@RestController
@RequestMapping("/api/project")
public class ProjectApi {

    private static final Pattern PROJECT_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final List<String> GRADLE_VERSIONS = List.of("8.12", "8.5", "7.6");
    private static final String DEFAULT_NAME = "hello-java";
    private static final String DEFAULT_GRADLE_VERSION = "8.12";

    private static final String BUILD_GRADLE = """
            plugins {
                id 'application'
            }

            repositories {
                mavenCentral()
            }

            application {
                mainClass = 'Main'
            }
            """;

    private static final String MAIN_JAVA = """
            public class Main {

                public static void main(String[] args) {
                    System.out.println("Hello, GMCH IDE!");
                }
            }
            """;

    private static final String GITIGNORE = """
            .gradle/
            build/
            out/
            .idea/
            *.iml
            """;

    private final Workspace workspace;
    private final String configuredRoot;

    public ProjectApi(Workspace workspace, @Value("${app.default-project-root:}") String configuredRoot) {
        this.workspace = workspace;
        this.configuredRoot = configuredRoot;
    }

    /** 默认工程目录、默认工程名与可选 Gradle 版本 */
    @GetMapping("/defaults")
    public Map<String, Object> defaults() {
        Path parent = defaultParent();
        return Map.of(
                "parentDir", parent.toString(),
                "name", DEFAULT_NAME,
                "gradleVersions", GRADLE_VERSIONS
        );
    }

    @PostMapping
    public Map<String, String> create(@RequestBody CreateProjectRequest request) throws IOException {
        String name = request.name() == null ? "" : request.name().trim();
        if (!PROJECT_NAME.matcher(name).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "工程名只能包含字母、数字、点、下划线、中划线，且不能以符号开头"
            );
        }

        String gradleVersion = request.gradleVersion() == null || request.gradleVersion().isBlank()
                ? DEFAULT_GRADLE_VERSION
                : request.gradleVersion().trim();
        if (!GRADLE_VERSIONS.contains(gradleVersion)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的 Gradle 版本: " + gradleVersion);
        }

        Path parent = resolveParent(request.parentDir());
        Files.createDirectories(parent);
        Path target = parent.resolve(name).normalize();
        if (!target.startsWith(parent)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工程路径越界");
        }
        if (Files.exists(target) && !isEmptyDirectory(target)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "目录已存在且不为空: " + target);
        }

        Files.createDirectories(target);
        writeProject(target, name, gradleVersion);
        workspace.switchTo(target.toString());

        return Map.of("path", target.toString(), "name", name);
    }

    private Path resolveParent(String requested) {
        if (requested == null || requested.isBlank()) {
            return defaultParent();
        }
        Path path = Paths.get(requested).toAbsolutePath().normalize();
        if (Files.exists(path) && !Files.isDirectory(path)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "父目录不是文件夹: " + path);
        }
        return path;
    }

    private Path defaultParent() {
        if (configuredRoot != null && !configuredRoot.isBlank()) {
            return Paths.get(configuredRoot).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.home")).resolve("GMCHProjects");
    }

    private boolean isEmptyDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(path)) {
            return entries.findAny().isEmpty();
        }
    }

    private void writeProject(Path root, String name, String gradleVersion) throws IOException {
        Path javaDir = root.resolve("src/main/java");
        Files.createDirectories(javaDir);

        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = '" + name + "'\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("build.gradle"), BUILD_GRADLE, StandardCharsets.UTF_8);
        Files.writeString(javaDir.resolve("Main.java"), MAIN_JAVA, StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".gitignore"), GITIGNORE, StandardCharsets.UTF_8);
        Files.writeString(
                root.resolve("README.md"),
                "# " + name + "\n\n由 GMCH IDE 创建的 Gradle Java 工程。\n",
                StandardCharsets.UTF_8
        );

        writeWrapper(root, gradleVersion);
    }

    private void writeWrapper(Path root, String gradleVersion) throws IOException {
        Path wrapperDir = root.resolve("gradle/wrapper");
        Files.createDirectories(wrapperDir);

        copyTemplate("project-template/gradlew", root.resolve("gradlew"));
        copyTemplate("project-template/gradlew.bat", root.resolve("gradlew.bat"));
        copyTemplate(
                "project-template/gradle/wrapper/gradle-wrapper.jar",
                wrapperDir.resolve("gradle-wrapper.jar")
        );
        Files.writeString(
                wrapperDir.resolve("gradle-wrapper.properties"),
                wrapperProperties(gradleVersion),
                StandardCharsets.UTF_8
        );

        // Windows 上 setPosixFilePermissions 会抛 UnsupportedOperationException，忽略即可
        try {
            Files.setPosixFilePermissions(
                    root.resolve("gradlew"),
                    PosixFilePermissions.fromString("rwxr-xr-x")
            );
        } catch (UnsupportedOperationException ignored) {
            // Windows 不需要可执行位
        }
    }

    private String wrapperProperties(String gradleVersion) {
        List<String> lines = new ArrayList<>();
        lines.add("distributionBase=GRADLE_USER_HOME");
        lines.add("distributionPath=wrapper/dists");
        lines.add("distributionUrl=https\\://services.gradle.org/distributions/gradle-" + gradleVersion + "-bin.zip");
        lines.add("networkTimeout=10000");
        lines.add("validateDistributionUrl=true");
        lines.add("zipStoreBase=GRADLE_USER_HOME");
        lines.add("zipStorePath=wrapper/dists");
        return String.join("\n", lines) + "\n";
    }

    private void copyTemplate(String resource, Path target) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("缺少内置模板资源: " + resource);
            }
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 请求体：父目录、工程名、Gradle 版本 */
    public record CreateProjectRequest(String parentDir, String name, String gradleVersion) {
    }
}
