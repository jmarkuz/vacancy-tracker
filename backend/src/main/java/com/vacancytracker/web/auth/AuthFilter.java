package com.vacancytracker.web.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Requires the access token on every /api request in the vps profile (T-071). The token comes as
 * "Authorization: Bearer ..." (scripts) or as the vt_token cookie (browser, set by the login endpoint). Wrong
 * tokens count against the client's failure limit; a blocked client gets 429 with Retry-After. The static
 * pages, /actuator/health and the auth endpoints stay open; they hold no data, and login limits itself.
 */
public class AuthFilter extends OncePerRequestFilter {

    public static final String COOKIE = "vt_token";

    private final AccessToken token;
    private final FailureLimiter limiter;

    AuthFilter(AccessToken token, FailureLimiter limiter) {
        this.token = token;
        this.limiter = limiter;
    }

    private static final org.springframework.web.util.UrlPathHelper PATHS = new org.springframework.web.util.UrlPathHelper();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // The path Spring routes on: decoded, dot segments resolved, ";params" removed. The raw URI is not safe to test
        // ("/api;x=1/vacancies" routes to /api/vacancies but does not start with "/api/").
        String path = PATHS.getPathWithinApplication(request);
        return !path.startsWith("/api/") || path.equals("/api/auth/status") || path.equals("/api/auth/logout")
                || path.equals("/api/auth/login");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = clientOf(request);
        Duration wait = limiter.retryAfter(client);
        if (!wait.isZero()) {
            tooManyAttempts(response, wait);
            return;
        }
        String presented = presentedToken(request);
        if (token.matches(presented)) {
            limiter.recordSuccess(client);
            chain.doFilter(request, response);
            return;
        }
        // A request that carries no token at all is just "not logged in", not a guess, and does not count.
        if (presented != null) {
            limiter.recordFailure(client);
        }
        unauthorized(response);
    }

    static String clientOf(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    static String presentedToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if (COOKIE.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }

    static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"An access token is required.\"}");
    }

    static long retryAfterSeconds(Duration wait) {
        return Math.max(1, wait.toSeconds() + (wait.toNanosPart() > 0 ? 1 : 0));
    }

    static void tooManyAttempts(HttpServletResponse response, Duration wait) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds(wait)));
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"title\":\"Too many attempts\",\"status\":429,\"detail\":\"Too many wrong tokens. Try again later.\"}");
    }
}
