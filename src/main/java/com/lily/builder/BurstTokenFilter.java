package com.lily.builder;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * /api/burst/** 는 클러스터 밖(온프레미스 에이전트)에서 Ingress 로 들어온다. "Authorization: Bearer {BURST_API_TOKEN}".
 * 토큰이 없으면 버스팅 API 자체를 끈다 (404).
 * 경로는 디코딩·정규화된 servletPath 로 판단한다 (인코딩한 경로로 우회하지 못하게).
 */
@Component
public class BurstTokenFilter extends OncePerRequestFilter {

    private final byte[] expected;

    public BurstTokenFilter(BuilderProperties props) {
        String token = props.burstToken();
        this.expected = token == null || token.isBlank()
                ? null
                : ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
        return !(path.equals("/api/burst") || path.startsWith("/api/burst/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (expected == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        byte[] actual = header == null ? new byte[0] : header.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }
}
