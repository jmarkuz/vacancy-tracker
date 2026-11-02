package com.vacancytracker.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a request that changes data when the page that sent it came from another site. Browsers add an Origin header
 * to every cross-site POST, even a "simple" one that needs no preflight, so a web page the user happens to visit cannot
 * start a run or import a profile on a locally running instance. Requests without Origin (the app's own fetches from
 * the same origin, curl, other tools) are not affected.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class OriginFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SAFE_METHODS.contains(request.getMethod()) || request.getHeader("Origin") == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (sameHost(request.getHeader("Origin"), request.getHeader("Host"))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"title\":\"Cross-site request refused\",\"status\":403}");
    }

    static boolean sameHost(String origin, String host) {
        if (origin == null || host == null) {
            return false;
        }
        try {
            URI uri = URI.create(origin);
            String authority = uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
            return authority != null && authority.equalsIgnoreCase(host.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
