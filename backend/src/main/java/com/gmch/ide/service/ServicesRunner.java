package com.gmch.ide.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmch.ide.config.Workspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全栈运行编排器。按 {@code .gmch/run.json} 的顺序启动服务：构建、启动进程、
 * 等待 HTTP 就绪，再启动依赖它的服务。停止时终止完整进程树。
 */
@Service
public class ServicesRunner {

    private static final Logger log = LoggerFactory.getLogger(ServicesRunner.class);
    private static final int MAX_LOG_LINES = 2000;
    private static final Path CONFIG_PATH = Path.of(".gmch", "run.json");

    private final Workspace workspace;
    private final ServiceProcesses processRunner;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Object lock = new Object();
    private final Map<String, ServiceRuntime> runtimes = new LinkedHashMap<>();
    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicBoolean failureHandled = new AtomicBoolean(false);

    private volatile RunConfiguration activeConfig;
    private volatile Path activeRoot;
    private volatile String configError = "";

    public ServicesRunner(
            Workspace workspace,
            ServiceProcesses processRunner,
            ObjectMapper objectMapper
    ) {
        this.workspace = workspace;
        this.processRunner = processRunner;
        this.objectMapper = objectMapper;
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "services-shutdown"));
    }

    public Map<String, Object> status() {
        synchronized (lock) {
            if (!workspace.isOpen()) {
                return unavailable("尚未打开工作区");
            }

            RunConfiguration configuration = activeConfig;
            if (!running.get() || configuration == null) {
                try {
                    configuration = loadConfiguration();
                    syncConfiguration(configuration, workspace.root(), false);
                    configError = "";
                } catch (Exception error) {
                    configError = error.getMessage() == null ? "运行配置无效" : error.getMessage();
                    return unavailable(configError);
                }
            }

            List<Map<String, Object>> serviceStatuses = new ArrayList<>();
            for (RunConfiguration.Service service : configuration.services()) {
                ServiceRuntime runtime = runtimes.computeIfAbsent(service.id(), ignored -> new ServiceRuntime());
                serviceStatuses.add(serviceStatus(service, runtime));
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("configured", true);
            result.put("name", displayName(configuration));
            result.put("path", configFile().toString());
            result.put("running", running.get());
            result.put("stopping", stopping.get());
            result.put("previewUrl", previewUrl(configuration));
            result.put("services", serviceStatuses);
            return result;
        }
    }

    public Map<String, Object> start() {
        RunConfiguration configuration;
        Path root;
        synchronized (lock) {
            if (running.get()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "全栈服务已经在运行");
            }
            try {
                configuration = loadConfiguration();
                root = workspace.root();
            } catch (Exception error) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        error.getMessage() == null ? "无法读取运行配置" : error.getMessage(),
                        error
                );
            }

            syncConfiguration(configuration, root, true);
            activeConfig = configuration;
            activeRoot = root;
            configError = "";
            failureHandled.set(false);
            stopping.set(false);
            running.set(true);
            processes.clear();
            log.info("Full-stack run start: config={}", configFile());
        }

        emitSnapshot();
        executor.submit(() -> execute(configuration));
        return status();
    }

    public Map<String, Object> stop() {
        if (!running.get() && processes.isEmpty()) {
            return status();
        }

        stopping.set(true);
        failureHandled.set(true);
        synchronized (lock) {
            for (ServiceRuntime runtime : runtimes.values()) {
                if (!"failed".equals(runtime.state) && !"stopped".equals(runtime.state)) {
                    runtime.state = "stopping";
                }
            }
        }
        emitSnapshot();
        terminateProcesses();

        synchronized (lock) {
            for (ServiceRuntime runtime : runtimes.values()) {
                if (!"failed".equals(runtime.state)) runtime.state = "stopped";
            }
            running.set(false);
            stopping.set(false);
        }
        log.info("Full-stack run stopped");
        emitSnapshot();
        return status();
    }

    public SseEmitter events() {
        SseEmitter emitter = new SseEmitter(0L);
        subscribers.add(emitter);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError((ignored) -> subscribers.remove(emitter));
        try {
            send(emitter, "snapshot", status());
        } catch (Exception error) {
            subscribers.remove(emitter);
        }
        return emitter;
    }

    private void execute(RunConfiguration configuration) {
        String activeService = null;
        try {
            for (RunConfiguration.Service service : configuration.services()) {
                if (stopping.get() || !running.get()) return;
                activeService = service.id();
                ensureDependenciesReady(service, configuration);

                runCommands(service, service.preBuild(), "准备构建");
                if (service.build() != null) {
                    runCommands(service, List.of(service.build()), "构建");
                }
                if (stopping.get() || !running.get()) return;

                updateState(service.id(), "starting", "启动进程", null, service.open());
                Process process = processRunner.start(service, service.run(), line -> appendLog(service.id(), line));
                processes.put(service.id(), process);
                monitorExit(service, process);

                boolean ready = processRunner.awaitReady(
                        service,
                        process,
                        line -> appendLog(service.id(), line)
                );
                if (stopping.get() || !running.get()) return;
                if (!ready) {
                    String reason = process.isAlive()
                            ? "启动超时，未通过就绪检查"
                            : "进程在就绪前退出";
                    throw new IllegalStateException(serviceLabel(service) + reason);
                }

                String readyUrl = service.ready() == null ? null : service.ready().url();
                updateState(service.id(), "ready", "就绪", readyUrl, service.open());
                appendLog(service.id(), "[就绪] " + (readyUrl == null ? "进程已启动" : readyUrl));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            if (!stopping.get()) failSession(activeService, "运行被中断", error);
        } catch (Exception error) {
            if (!stopping.get()) failSession(activeService, error.getMessage(), error);
        }
    }

    private void runCommands(
            RunConfiguration.Service service,
            List<RunConfiguration.Command> commands,
            String phase
    ) throws IOException, InterruptedException {
        if (commands == null || commands.isEmpty()) return;
        updateState(service.id(), "building", phase, null, service.open());
        for (RunConfiguration.Command command : commands) {
            if (stopping.get() || !running.get()) return;
            Process process = processRunner.start(service, command, line -> appendLog(service.id(), line));
            processes.put(service.id(), process);
            int exitCode = process.waitFor();
            processes.remove(service.id(), process);
            if (stopping.get() || !running.get()) return;
            if (exitCode != 0) {
                throw new IllegalStateException(
                        serviceLabel(service) + phase + "失败，退出码 " + exitCode
                );
            }
        }
    }

    private void ensureDependenciesReady(
            RunConfiguration.Service service,
            RunConfiguration configuration
    ) {
        if (service.dependsOn() == null) return;
        for (String dependency : service.dependsOn()) {
            ServiceRuntime dependencyRuntime;
            synchronized (lock) {
                dependencyRuntime = runtimes.get(dependency);
            }
            if (dependencyRuntime == null || !"ready".equals(dependencyRuntime.state)) {
                throw new IllegalStateException(
                        serviceLabel(service) + "的依赖服务未就绪: " + dependency
                );
            }
        }
    }

    private void monitorExit(RunConfiguration.Service service, Process process) {
        process.onExit().thenAccept(exited -> {
            processes.remove(service.id(), exited);
            if (stopping.get() || !running.get()) return;
            String detail = "进程意外退出（code=" + exited.exitValue() + "）";
            failSession(service.id(), serviceLabel(service) + detail, null);
        });
    }

    private void failSession(String serviceId, String message, Exception error) {
        if (!failureHandled.compareAndSet(false, true)) return;
        stopping.set(true);
        if (serviceId != null) {
            updateState(serviceId, "failed", message, null, null);
            appendLog(serviceId, "[失败] " + message);
        }
        if (error != null) log.warn("Full-stack run failed: {}", message, error);
        else log.warn("Full-stack run failed: {}", message);

        terminateProcesses();
        synchronized (lock) {
            for (ServiceRuntime runtime : runtimes.values()) {
                if (!"failed".equals(runtime.state)) runtime.state = "stopped";
            }
            running.set(false);
            stopping.set(false);
        }
        emitSnapshot();
    }

    private void terminateProcesses() {
        List<Process> snapshot = new ArrayList<>(processes.values());
        processes.clear();
        for (Process process : snapshot) {
            try {
                processRunner.stopProcessTree(process);
            } catch (Exception error) {
                log.warn("Failed to stop service process {}", process.pid(), error);
            }
        }
    }

    private void appendLog(String serviceId, String line) {
        String text = line == null ? "" : line;
        synchronized (lock) {
            ServiceRuntime runtime = runtimes.get(serviceId);
            if (runtime == null) return;
            runtime.logs.addLast(text);
            while (runtime.logs.size() > MAX_LOG_LINES) runtime.logs.removeFirst();
        }
        emit("log", Map.of("serviceId", serviceId, "line", text));
    }

    private void updateState(
            String serviceId,
            String state,
            String message,
            String readyUrl,
            String openUrl
    ) {
        String effectiveReadyUrl;
        String effectiveOpenUrl;
        synchronized (lock) {
            ServiceRuntime runtime = runtimes.get(serviceId);
            if (runtime == null) return;
            runtime.state = state;
            runtime.message = message;
            runtime.readyUrl = readyUrl;
            if (openUrl != null) runtime.openUrl = openUrl;
            effectiveReadyUrl = runtime.readyUrl;
            effectiveOpenUrl = runtime.openUrl;
        }

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("serviceId", serviceId);
        event.put("state", state);
        if (message != null) event.put("message", message);
        if (effectiveReadyUrl != null) event.put("readyUrl", effectiveReadyUrl);
        if (effectiveOpenUrl != null) event.put("openUrl", effectiveOpenUrl);
        emit("state", event);
    }

    private void emitSnapshot() {
        Map<String, Object> snapshot;
        try {
            snapshot = status();
        } catch (Exception error) {
            return;
        }
        emit("snapshot", snapshot);
    }

    private void emit(String name, Map<String, Object> payload) {
        if (subscribers.isEmpty()) return;
        String data;
        try {
            data = objectMapper.writeValueAsString(payload);
        } catch (Exception error) {
            log.warn("Failed to encode service event", error);
            return;
        }
        for (SseEmitter emitter : subscribers) {
            try {
                synchronized (emitter) {
                    // data 已是 JSON 文本；按纯文本发送，避免 Spring 再序列化一次字符串。
                    emitter.send(SseEmitter.event().name(name).data(data, MediaType.TEXT_PLAIN));
                }
            } catch (Exception error) {
                subscribers.remove(emitter);
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // 已断开
                }
            }
        }
    }

    private void send(SseEmitter emitter, String name, Object payload) throws IOException {
        String data = objectMapper.writeValueAsString(payload);
        synchronized (emitter) {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.TEXT_PLAIN));
        }
    }

    private RunConfiguration loadConfiguration() throws IOException {
        Path file = configFile();
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("未找到运行配置: " + file);
        }
        RunConfiguration configuration;
        try {
            configuration = objectMapper.readValue(file.toFile(), RunConfiguration.class);
        } catch (Exception error) {
            throw new IllegalStateException("运行配置 JSON 无效: " + error.getMessage(), error);
        }
        validate(configuration);
        return configuration;
    }

    private void validate(RunConfiguration configuration) {
        if (configuration.version() < 1) {
            throw new IllegalStateException("run.json 的 version 必须大于 0");
        }
        if (configuration.services() == null || configuration.services().isEmpty()) {
            throw new IllegalStateException("run.json 至少需要一个 service");
        }

        Set<String> ids = new HashSet<>();
        for (RunConfiguration.Service service : configuration.services()) {
            if (service.id() == null || !service.id().matches("[A-Za-z0-9_-]+")) {
                throw new IllegalStateException("service.id 只能包含字母、数字、下划线和短横线");
            }
            if (!ids.add(service.id())) {
                throw new IllegalStateException("service.id 重复: " + service.id());
            }
            if (service.run() == null) {
                throw new IllegalStateException("service 缺少 run 配置: " + service.id());
            }
            processRunner.workingDirectory(service);
        }

        for (RunConfiguration.Service service : configuration.services()) {
            if (service.dependsOn() == null) continue;
            for (String dependency : service.dependsOn()) {
                if (service.id().equals(dependency) || !ids.contains(dependency)) {
                    throw new IllegalStateException(
                            service.id() + " 引用了无效依赖: " + dependency
                    );
                }
            }
        }
        validateNoCycles(configuration);
    }

    private void validateNoCycles(RunConfiguration configuration) {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        for (RunConfiguration.Service service : configuration.services()) {
            graph.put(service.id(), service.dependsOn() == null ? List.of() : service.dependsOn());
        }
        Set<String> visiting = new LinkedHashSet<>();
        Set<String> visited = new HashSet<>();
        for (String id : graph.keySet()) visit(id, graph, visiting, visited);
    }

    private void visit(
            String id,
            Map<String, List<String>> graph,
            Set<String> visiting,
            Set<String> visited
    ) {
        if (visited.contains(id)) return;
        if (!visiting.add(id)) {
            throw new IllegalStateException("服务依赖存在循环: " + String.join(" -> ", visiting));
        }
        for (String dependency : graph.getOrDefault(id, List.of())) {
            visit(dependency, graph, visiting, visited);
        }
        visiting.remove(id);
        visited.add(id);
    }

    private void syncConfiguration(RunConfiguration configuration, Path root, boolean reset) {
        if (activeRoot != null && !activeRoot.equals(root)) reset = true;
        activeConfig = configuration;
        activeRoot = root;

        Set<String> ids = new LinkedHashSet<>();
        for (RunConfiguration.Service service : configuration.services()) ids.add(service.id());
        runtimes.keySet().removeIf(id -> !ids.contains(id));
        for (String id : ids) {
            ServiceRuntime runtime = runtimes.get(id);
            if (runtime == null || reset) runtimes.put(id, new ServiceRuntime());
        }
    }

    private Map<String, Object> serviceStatus(
            RunConfiguration.Service service,
            ServiceRuntime runtime
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", service.id());
        result.put("name", service.name() == null || service.name().isBlank()
                ? service.id()
                : service.name());
        result.put("state", runtime.state);
        if (runtime.readyUrl != null) result.put("readyUrl", runtime.readyUrl);
        if (runtime.openUrl != null) result.put("openUrl", runtime.openUrl);
        if (runtime.message != null) result.put("message", runtime.message);
        result.put("log", List.copyOf(runtime.logs));
        return result;
    }

    private Map<String, Object> unavailable(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", false);
        result.put("name", "未配置全栈运行");
        result.put("path", workspace.isOpen() ? configFile().toString() : null);
        result.put("running", false);
        result.put("stopping", false);
        result.put("message", message);
        result.put("services", List.of());
        return result;
    }

    private String previewUrl(RunConfiguration configuration) {
        List<RunConfiguration.Service> services = configuration.services();
        for (int index = services.size() - 1; index >= 0; index--) {
            String open = services.get(index).open();
            if (open != null && !open.isBlank()) return open;
        }
        return null;
    }

    private String displayName(RunConfiguration configuration) {
        return configuration.name() == null || configuration.name().isBlank()
                ? "全栈开发"
                : configuration.name();
    }

    private String serviceLabel(RunConfiguration.Service service) {
        return service.name() == null || service.name().isBlank()
                ? service.id() + " "
                : service.name() + " ";
    }

    private Path configFile() {
        return workspace.root().resolve(CONFIG_PATH);
    }

    private static final class ServiceRuntime {
        private String state = "idle";
        private String readyUrl;
        private String openUrl;
        private String message;
        private final Deque<String> logs = new ArrayDeque<>();
    }
}
