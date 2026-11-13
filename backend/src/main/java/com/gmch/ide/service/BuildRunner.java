package com.gmch.ide.service;

import com.gmch.ide.config.Workspace;
import com.gmch.ide.service.StartupClassDetector.StartupTarget;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * 编译/运行入口的构建工具路由。
 *
 * <p>根据自动识别出的启动类及最近的构建文件选择 Maven 或 Gradle；不再假设
 * 所有工作区都是 Gradle 工程。运行前仍会校验启动类路径位于当前工作区。</p>
 */
@Service
public class BuildRunner {

    private final Workspace workspace;
    private final StartupClassDetector startupClassDetector;
    private final MavenRunner mavenRunner;
    private final GradleRunner gradleRunner;

    public BuildRunner(
            Workspace workspace,
            StartupClassDetector startupClassDetector,
            MavenRunner mavenRunner,
            GradleRunner gradleRunner
    ) {
        this.workspace = workspace;
        this.startupClassDetector = startupClassDetector;
        this.mavenRunner = mavenRunner;
        this.gradleRunner = gradleRunner;
    }

    public int run(String task, String startupPath, Consumer<String> onLine)
            throws IOException, InterruptedException {
        if (!workspace.isOpen()) {
            throw new IllegalStateException("请先打开项目");
        }

        StartupTarget target = startupClassDetector.target(startupPath);
        if (target != null && "maven".equals(target.buildSystem())) {
            return mavenRunner.run(task, target, onLine);
        }

        Path gradleProject = gradleProjectDirectory(target);
        String gradleTask = "run".equals(task) && target != null && target.springBoot()
                ? "bootRun"
                : task;
        return gradleRunner.run(gradleTask, gradleProject, onLine);
    }

    /** 执行构建工具导航中经过后端白名单校验的任务。 */
    public int runBuildCommand(
            BuildToolDetector.BuildCommandTarget target,
            Consumer<String> onLine
    ) throws IOException, InterruptedException {
        if (!workspace.isOpen()) {
            throw new IllegalStateException("请先打开项目");
        }
        Path projectRoot = workspace.resolve(target.projectPath());
        if ("maven".equals(target.system())) {
            Path moduleRoot = workspace.resolve(target.modulePath());
            return mavenRunner.runBuildCommand(projectRoot, moduleRoot, target.task(), onLine);
        }
        if ("gradle".equals(target.system())) {
            return gradleRunner.run(target.task(), projectRoot, onLine);
        }
        throw new IllegalArgumentException("未知构建工具: " + target.system());
    }

    public boolean stop() {
        boolean mavenStopped = mavenRunner.stop();
        boolean gradleStopped = gradleRunner.stop();
        return mavenStopped || gradleStopped;
    }

    /** 从识别出的模块向上找最近的 Wrapper 目录；未识别到启动类时保持旧入口。 */
    private Path gradleProjectDirectory(StartupTarget target) {
        Path root = workspace.root();
        if (target == null || target.modulePath() == null || target.modulePath().isBlank()) {
            return root;
        }
        Path current = workspace.resolve(target.modulePath()).normalize();
        if (!current.startsWith(root) || !Files.isDirectory(current)) return root;
        while (current != null && current.startsWith(root)) {
            if (Files.isRegularFile(current.resolve("gradlew"))
                    || Files.isRegularFile(current.resolve("gradlew.bat"))) {
                return current;
            }
            if (current.equals(root)) break;
            current = current.getParent();
        }
        return root;
    }
}
