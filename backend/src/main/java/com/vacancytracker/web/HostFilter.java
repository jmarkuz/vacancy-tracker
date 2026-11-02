package com.vacancytracker.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses API requests addressed to a host name the app does not answer to (review 2026-11-02, ADR-039). Without it,
 * DNS rebinding defeats the Origin check: a page on evil.example whose name later resolves to 127.0.0.1 is
 * same-origin with itself, so it could read the backup and start runs on a local instance. A rebinding page always
 * sends its own name as Host, so only loopback names and the names in app.allowed-hosts (the VPS domain) pass.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class HostFilter extends OncePerRequestFilter {

    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final Set<String> allowed;

    public HostFilter(@Value("${app.allowed-hosts:}") String extraHosts) {
        Set<String> hosts = new HashSet<>(LOOPBACK);
        Arrays.stream(extraHosts.split(",")).map(String::strip).filter(h -> !h.isEmpty())
                .map(h -> h.toLowerCase(Locale.ROOT)).forEach(hosts::add);
        this.allowed = Set.copyOf(hosts);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Browsers always send Host, so a rebinding page is always checked; a client without it (HTTP/1.0, tests) is
        // judged by the name the server resolved for the request.
        String host = request.getHeader("Host");
        if (allowed(host != null ? host : request.getServerName())) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"title\":\"Unknown host name\",\"status\":403,"
                + "\"detail\":\"Add this host name to APP_ALLOWED_HOSTS to use the API through it.\"}");
    }

    boolean allowed(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        String host = hostHeader.strip().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            host = end < 0 ? host : host.substring(0, end + 1);
        } else if (host.indexOf(':') == host.lastIndexOf(':') && host.contains(":")) {
            host = host.substring(0, host.indexOf(':'));
        }
        return allowed.contains(host);
    }
}
