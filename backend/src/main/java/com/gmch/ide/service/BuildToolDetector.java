package com.gmch.ide.service;

import com.gmch.ide.config.Workspace;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 扫描工作区中的 Maven / Gradle 工程、模块和常用任务。
 *
 * <p>探测只读取目录与构建脚本，不启动 Gradle/Maven daemon，也不需要先安装工程。
 * 前端刷新文件树时同步调用本服务，因此 Maven 与 Gradle 工程会自动出现在构建工具导航中。</p>
 */
@Service
public class BuildToolDetector {

    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .contains("win");
    private static final int MAX_DEPTH = 14;
    private static final int MAX_DIRECTORIES = 2_000;
    private static final long MAX_BUILD_FILE_BYTES = 512L * 1024;
    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", ".gradle", ".idea", ".cache", ".tmp", "node_modules",
            "build", "target", "out", "dist", "bin", "generated"
    );
    private static final Set<String> MAVEN_GOALS = Set.of(
            "clean", "validate", "compile", "test", "package", "verify", "install"
    );
    private static final Set<String> GRADLE_TASKS = Set.of(
            "clean", "build", "test", "compileJava", "assembleDebug", "assembleRelease",
            "run", "bootRun"
    );

    private final Workspace workspace;

    public BuildToolDetector(Workspace workspace) {
        this.workspace = workspace;
    }

    public BuildToolsResult detect() throws IOException {
        if (!workspace.isOpen()) {
            return new BuildToolsResult(List.of());
        }

        Path root = workspace.root();
        List<Path> directories = collectDirectories(root);
        List<Path> wrapperRoots = new ArrayList<>();
        List<Path> mavenCandidates = new ArrayList<>();
        List<Path> gradleCandidates = new ArrayList<>();

        for (Path directory : directories) {
            if (hasMavenWrapper(directory) || hasGradleWrapper(directory)) {
                wrapperRoots.add(directory);
            }
            if (Files.isRegularFile(directory.resolve("pom.xml"))) {
                mavenCandidates.add(directory);
            }
            if (hasGradleBuildFile(directory)) {
                gradleCandidates.add(directory);
            }
        }

        List<Path> mavenRoots = wrapperRoots.stream().filter(BuildToolDetector::hasMavenWrapper).toList();
        List<Path> gradleRoots = wrapperRoots.stream().filter(BuildToolDetector::hasGradleWrapper).toList();
        List<Path> allMavenRoots = withTopLevelCandidates(mavenRoots, mavenCandidates);
        List<Path> allGradleRoots = withTopLevelCandidates(gradleRoots, gradleCandidates);

        List<BuildToolProject> projects = new ArrayList<>();
        for (Path projectRoot : allMavenRoots) {
            projects.add(mavenProject(root, projectRoot, directories, allMavenRoots));
        }
        for (Path projectRoot : allGradleRoots) {
            projects.add(gradleProject(root, projectRoot, directories, allGradleRoots));
        }

        projects.sort(Comparator
                .comparingInt((BuildToolProject project) -> "maven".equals(project.system()) ? 0 : 1)
                .thenComparing(BuildToolProject::rootPath, String.CASE_INSENSITIVE_ORDER));
        return new BuildToolsResult(List.copyOf(projects));
    }

    /** 重新扫描并只返回探测结果中真实存在且启用的命令，防止前端传入任意任务。 */
    public BuildCommandTarget requireCommand(
            String system,
            String projectPath,
            String modulePath,
            String task
    ) throws IOException {
        String normalizedSystem = normalize(system);
        String normalizedProject = normalize(projectPath);
        String normalizedModule = normalize(modulePath);
        String normalizedTask = normalizeTask(task);

        BuildToolsResult result = detect();
        BuildToolProject project = result.projects().stream()
                .filter(item -> item.system().equals(normalizedSystem))
                .filter(item -> item.rootPath().equalsIgnoreCase(normalizedProject))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("构建工程不存在或已变更: " + projectPath));
        BuildToolModule module = project.modules().stream()
                .filter(item -> item.path().equalsIgnoreCase(normalizedModule))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("构建模块不存在或已变更: " + modulePath));
        BuildToolCommand command = module.commands().stream()
                .filter(item -> item.task().equals(normalizedTask))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的任务: " + task));
        if (!command.executable()) {
            throw new IllegalStateException(command.disabledReason());
        }
        return new BuildCommandTarget(
                project.system(),
                project.rootPath(),
                module.path(),
                command.task(),
                command.commandLine()
        );
    }

    private static List<Path> withTopLevelCandidates(List<Path> roots, List<Path> candidates) {
        Set<Path> existing = new HashSet<>(roots);
        candidates.stream()
                .sorted(Comparator.comparingInt(Path::getNameCount))
                .forEach(candidate -> {
                    boolean nested = existing.stream().anyMatch(root ->
                            !root.equals(candidate) && candidate.startsWith(root));
                    if (!nested) existing.add(candidate);
                });
        return existing.stream()
                .sorted(Comparator.comparing(Path::toString))
                .toList();
    }

    private BuildToolProject mavenProject(
            Path workspaceRoot,
            Path projectRoot,
            List<Path> directories,
            List<Path> projectRoots
    ) {
        Path wrapper = wrapperFile(projectRoot, "mvnw", "mvnw.cmd");
        boolean executable = wrapper != null;
        List<BuildToolModule> modules = new ArrayList<>();
        List<Path> moduleRoots = directories.stream()
                .filter(path -> path.startsWith(projectRoot))
                .filter(path -> !insideNestedProject(projectRoot, path, projectRoots))
                .filter(path -> Files.isRegularFile(path.resolve("pom.xml")))
                .sorted(Comparator.comparing((Path path) -> projectRoot.relativize(path).toString()))
                .toList();

        for (Path moduleRoot : moduleRoots) {
            String relative = workspaceRelative(workspaceRoot, moduleRoot);
            String projectRelative = relative(projectRoot, moduleRoot);
            String taskPath = projectRelative.isBlank() ? "." : projectRelative;
            String name = mavenArtifactName(moduleRoot.resolve("pom.xml"), moduleRoot.getFileName().toString());
            List<BuildToolCommand> commands = mavenCommands(
                    wrapper,
                    projectRelative,
                    executable,
                    relative
            );
            modules.add(new BuildToolModule(
                    "maven:" + normalize(relative),
                    name,
                    relative,
                    taskPath,
                    commands
            ));
        }

        String projectPath = workspaceRelative(workspaceRoot, projectRoot);
        return new BuildToolProject(
                "maven:" + projectPath,
                "maven",
                projectRoot.getFileName().toString(),
                projectPath,
                wrapper == null ? null : workspaceRelative(workspaceRoot, wrapper),
                executable,
                List.copyOf(modules)
        );
    }

    private BuildToolProject gradleProject(
            Path workspaceRoot,
            Path projectRoot,
            List<Path> directories,
            List<Path> projectRoots
    ) {
        Path wrapper = wrapperFile(projectRoot, "gradlew", "gradlew.bat");
        boolean executable = wrapper != null;
        List<BuildToolModule> modules = new ArrayList<>();
        List<Path> moduleRoots = directories.stream()
                .filter(path -> path.startsWith(projectRoot))
                .filter(path -> !insideNestedProject(projectRoot, path, projectRoots))
                .filter(BuildToolDetector::hasGradleBuildFile)
                .sorted(Comparator.comparing((Path path) -> projectRoot.relativize(path).toString()))
                .toList();

        for (Path moduleRoot : moduleRoots) {
            String relative = workspaceRelative(workspaceRoot, moduleRoot);
            String projectRelative = relative(projectRoot, moduleRoot);
            String taskPath = projectRelative.isBlank()
                    ? ":"
                    : ":" + projectRelative.replace('/', ':');
            String name = moduleRoot.getFileName().toString();
            List<BuildToolCommand> commands = gradleCommands(
                    wrapper,
                    taskPath,
                    projectRelative.isBlank(),
                    executable,
                    moduleRoot,
                    relative
            );
            modules.add(new BuildToolModule(
                    "gradle:" + normalize(relative),
                    name,
                    relative,
                    taskPath,
                    commands
            ));
        }

        String projectPath = workspaceRelative(workspaceRoot, projectRoot);
        return new BuildToolProject(
                "gradle:" + projectPath,
                "gradle",
                projectRoot.getFileName().toString(),
                projectPath,
                wrapper == null ? null : workspaceRelative(workspaceRoot, wrapper),
                executable,
                List.copyOf(modules)
        );
    }

    private List<BuildToolCommand> mavenCommands(
            Path wrapper,
            String moduleRelative,
            boolean executable,
            String modulePath
    ) {
        String executableName = wrapper == null
                ? "mvn"
                : (WINDOWS && wrapper.getFileName().toString().equalsIgnoreCase("mvnw.cmd")
                ? "mvnw.cmd"
                : (WINDOWS ? "mvnw.cmd" : "mvnw"));
        String disabledReason = executable ? null : "未找到 Maven Wrapper（mvnw / mvnw.cmd）";
        List<BuildToolCommand> commands = new ArrayList<>();
        for (String goal : List.of("clean", "validate", "compile", "test", "package", "verify", "install")) {
            List<String> args = new ArrayList<>();
            args.add(executableName);
            args.add("-q");
            args.add("-B");
            args.add("-Dstyle.color=never");
            if (!moduleRelative.isBlank()) {
                args.add("-pl");
                args.add(moduleRelative);
                if (!"clean".equals(goal) && !"validate".equals(goal)) {
                    args.add("-am");
                }
            }
            args.add(goal);
            commands.add(new BuildToolCommand(
                    "maven:" + normalize(modulePath) + ":" + goal,
                    goal,
                    goal,
                    mavenGoalDescription(goal),
                    executable,
                    disabledReason,
                    String.join(" ", args)
            ));
        }
        return List.copyOf(commands);
    }

    private List<BuildToolCommand> gradleCommands(
            Path wrapper,
            String taskPath,
            boolean rootProject,
            boolean executable,
            Path moduleRoot,
            String modulePath
    ) {
        String buildText = readBuildText(moduleRoot);
        boolean android = buildText.contains("com.android.application")
                || buildText.contains("com.android.library");
        boolean springBoot = buildText.contains("org.springframework.boot")
                || buildText.contains("spring-boot");
        boolean javaProject = springBoot
                || android
                || buildText.contains("java-library")
                || buildText.contains("plugin: 'java'")
                || buildText.contains("plugin \"java\"")
                || buildText.contains("plugins {")
                || buildText.contains("org.jetbrains.kotlin.jvm");
        boolean application = buildText.contains("plugin: 'application'")
                || buildText.contains("plugin \"application\"")
                || buildText.contains("application {");

        LinkedHashSet<String> tasks = new LinkedHashSet<>();
        tasks.add("clean");
        if (javaProject) {
            tasks.add("build");
            tasks.add("test");
            tasks.add("compileJava");
        }
        if (android) {
            tasks.add("assembleDebug");
            tasks.add("assembleRelease");
        }
        if (springBoot) tasks.add("bootRun");
        if (application) tasks.add("run");

        String executableName = wrapper == null
                ? "gradle"
                : (WINDOWS ? "gradlew.bat" : "gradlew");
        String disabledReason = executable ? null : "未找到 Gradle Wrapper（gradlew / gradlew.bat）";
        List<BuildToolCommand> commands = new ArrayList<>();
        for (String task : tasks) {
            String invocation = rootProject ? task : taskPath + ":" + task;
            String commandLine = executableName + " " + invocation + " --console=plain -q";
            commands.add(new BuildToolCommand(
                    "gradle:" + normalize(modulePath) + ":" + task,
                    task,
                    invocation,
                    gradleTaskDescription(task),
                    executable,
                    disabledReason,
                    commandLine
            ));
        }
        return List.copyOf(commands);
    }

    /** 项目根只读取自身构建脚本，避免把依赖中的插件声明误判为当前模块能力。 */
    private String readBuildText(Path moduleRoot) {
        for (String name : List.of("build.gradle", "build.gradle.kts")) {
            Path file = moduleRoot.resolve(name);
            if (!Files.isRegularFile(file)) continue;
            try {
                if (Files.size(file) > MAX_BUILD_FILE_BYTES) return "";
                return Files.readString(file, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            } catch (IOException ignored) {
                return "";
            }
        }
        return "";
    }

    private static String mavenGoalDescription(String goal) {
        return switch (goal) {
            case "clean" -> "清理当前模块输出";
            case "validate" -> "校验项目配置";
            case "compile" -> "编译主源码";
            case "test" -> "编译并运行测试";
            case "package" -> "生成可发布产物";
            case "verify" -> "执行验证和集成检查";
            case "install" -> "安装到本地 Maven 仓库";
            default -> goal;
        };
    }

    private static String gradleTaskDescription(String task) {
        return switch (task) {
            case "clean" -> "清理构建输出";
            case "build" -> "编译、测试并打包";
            case "test" -> "运行测试";
            case "compileJava" -> "编译 Java 源码";
            case "assembleDebug" -> "生成 Debug 安装包";
            case "assembleRelease" -> "生成 Release 安装包";
            case "run" -> "运行 Java 应用";
            case "bootRun" -> "运行 Spring Boot 应用";
            default -> task;
        };
    }

    private static String mavenArtifactName(Path pom, String fallback) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder().parse(pom.toFile());
            Element project = document.getDocumentElement();
            NodeList children = project.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node instanceof Element element && "artifactId".equals(element.getTagName())) {
                    String value = element.getTextContent().trim();
                    if (!value.isBlank()) return value;
                }
            }
        } catch (Exception ignored) {
            // POM 可能包含 IDE 无法解析的表达式；展示目录名即可。
        }
        return fallback;
    }

    private static List<Path> collectDirectories(Path root) throws IOException {
        List<Path> directories = new ArrayList<>();
        Files.walkFileTree(root, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (directories.size() >= MAX_DIRECTORIES) return FileVisitResult.TERMINATE;
                if (!dir.equals(root) && isIgnoredDirectory(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                directories.add(dir);
                return FileVisitResult.CONTINUE;
            }
        });
        return directories;
    }

    private static boolean hasMavenWrapper(Path directory) {
        return Files.isRegularFile(directory.resolve("mvnw"))
                || Files.isRegularFile(directory.resolve("mvnw.cmd"));
    }

    private static boolean hasGradleWrapper(Path directory) {
        return Files.isRegularFile(directory.resolve("gradlew"))
                || Files.isRegularFile(directory.resolve("gradlew.bat"));
    }

    private static boolean hasGradleBuildFile(Path directory) {
        return Files.isRegularFile(directory.resolve("build.gradle"))
                || Files.isRegularFile(directory.resolve("build.gradle.kts"));
    }

    private static Path wrapperFile(Path directory, String unixName, String windowsName) {
        if (WINDOWS && Files.isRegularFile(directory.resolve(windowsName))) {
            return directory.resolve(windowsName);
        }
        if (!WINDOWS && Files.isRegularFile(directory.resolve(unixName))) {
            return directory.resolve(unixName);
        }
        if (Files.isRegularFile(directory.resolve(windowsName))) {
            return directory.resolve(windowsName);
        }
        if (Files.isRegularFile(directory.resolve(unixName))) {
            return directory.resolve(unixName);
        }
        return null;
    }

    private static boolean insideNestedProject(Path projectRoot, Path path, List<Path> allRoots) {
        return allRoots.stream().anyMatch(other ->
                !other.equals(projectRoot)
                        && other.startsWith(projectRoot)
                        && path.startsWith(other)
        );
    }

    private static boolean isIgnoredDirectory(String name) {
        return IGNORED_DIRS.contains(name.toLowerCase(Locale.ROOT));
    }

    private static String workspaceRelative(Path root, Path path) {
        return normalize(root.relativize(path).toString());
    }

    private static String relative(Path root, Path path) {
        Path rel = root.relativize(path);
        return rel.toString().isBlank() ? "" : normalize(rel.toString());
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String normalized = value.replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        return normalized.isBlank() ? "." : normalized;
    }

    private static String normalizeTask(String task) {
        if (task == null || task.isBlank() || task.length() > 160) {
            throw new IllegalArgumentException("任务无效");
        }
        String normalized = task.trim();
        if (!normalized.matches(":?[A-Za-z][A-Za-z0-9_-]*(?::[A-Za-z][A-Za-z0-9_-]*)*")) {
            throw new IllegalArgumentException("任务格式无效: " + task);
        }
        String leaf = normalized.substring(normalized.lastIndexOf(':') + 1);
        if (!MAVEN_GOALS.contains(leaf) && !GRADLE_TASKS.contains(leaf)) {
            throw new IllegalArgumentException("任务不在允许列表中: " + task);
        }
        return normalized;
    }

    public record BuildToolsResult(List<BuildToolProject> projects) {
    }

    public record BuildToolProject(
            String id,
            String system,
            String label,
            String rootPath,
            String wrapper,
            boolean wrapperAvailable,
            List<BuildToolModule> modules
    ) {
    }

    public record BuildToolModule(
            String id,
            String name,
            String path,
            String taskPath,
            List<BuildToolCommand> commands
    ) {
    }

    public record BuildToolCommand(
            String id,
            String label,
            String task,
            String description,
            boolean executable,
            String disabledReason,
            String commandLine
    ) {
    }

    public record BuildCommandTarget(
            String system,
            String projectPath,
            String modulePath,
            String task,
            String commandLine
    ) {
    }
}