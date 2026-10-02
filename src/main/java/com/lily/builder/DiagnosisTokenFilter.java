package com.lily.builder;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** This internal route requires a dedicated service token, including in local development. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DiagnosisTokenFilter extends OncePerRequestFilter {
    static final int MAX_BODY_BYTES = 196608;
    private final byte[] expected;

    public DiagnosisTokenFilter(@Value("${lily.diagnosis.api-token:}") String token) {
        expected = token == null || token.isBlank() ? null : ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().matches("/api/diagnoses/?");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (expected == null) { reject(response, 503, "diagnosis_disabled"); return; }
        String token = request.getHeader("Authorization");
        if (!MessageDigest.isEqual(expected, token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8))) {
            reject(response, 401, "unauthorized"); return;
        }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) { reject(response, 413, "request_too_large"); return; }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) { reject(response, 413, "request_too_large"); return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] bytes, int offset, int length) { return input.read(bytes, offset, length); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
        }, response);
    }

    private static void reject(HttpServletResponse response, int status, String error) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + error + "\"}");
    }
}
