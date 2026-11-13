package com.gmch.ide.service;

import java.util.List;
import java.util.Map;

/**
 * 工作区根目录下的 {@code .gmch/run.json} 运行配置。
 *
 * <p>配置只描述“如何构建、如何启动、何时算就绪”，真正的进程编排由
 * {@link ServicesRunner} 完成。Java record 的组件名与 JSON 字段一一对应。</p>
 */
public record RunConfiguration(
        int version,
        String name,
        List<Service> services
) {

    public record Service(
            String id,
            String name,
            String cwd,
            List<String> dependsOn,
            List<Command> preBuild,
            Command build,
            Command run,
            Ready ready,
            String open,
            Map<String, String> env
    ) {
    }

    /**
     * 一个可执行步骤。
     * kind: maven / pnpm / java / command。
     */
    public record Command(
            String kind,
            List<String> args,
            String jar,
            String executable
    ) {
    }

    public record Ready(
            String url,
            Long timeoutMs
    ) {
    }
}
