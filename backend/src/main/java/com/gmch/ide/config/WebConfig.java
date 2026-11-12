package com.gmch.ide.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 渲染进程在开发模式来自 http://localhost:5173，打包后来自 file://（Origin 为 null），
 * 两种来源都需要放行；真正的访问控制由随机 token 承担，服务只绑定 127.0.0.1。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowedHeaders("*");
    }
}
