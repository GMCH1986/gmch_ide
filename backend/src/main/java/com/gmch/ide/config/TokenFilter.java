package com.gmch.ide.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 最小鉴权：Electron 主进程每次启动生成一个 token，服务端只接受携带该 token 的请求。
 * SSE（EventSource）不能设置自定义请求头，因此同时接受查询参数。
 */
@Component
public class TokenFilter extends OncePerRequestFilter {

    private final byte[] token;

    public TokenFilter(@Value("${app.token:}") String token) {
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 未配置 token（本地手工调试）或健康检查时放行
        if (token.length == 0 || "/api/health".equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        // CORS 预检不带自定义头：必须放行，否则浏览器会因 401 判定跨域失败
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String given = request.getHeader("X-GMCH-Token");
        if (given == null || given.isEmpty()) {
            given = request.getParameter("token");
        }

        if (given == null || !MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        chain.doFilter(request, response);
    }
}
