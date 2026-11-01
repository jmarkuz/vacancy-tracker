package com.vacancytracker.web.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login for the browser (T-071). In the local profile no token is needed and status says so. In the vps profile
 * the login page posts the token here; a right one is stored in an HTTP-only, same-site cookie.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    static final Duration COOKIE_LIFETIME = Duration.ofDays(30);

    public record LoginRequest(String token) {
    }

    private final ObjectProvider<AccessToken> token;
    private final ObjectProvider<FailureLimiter> limiter;

    public AuthController(ObjectProvider<AccessToken> token, ObjectProvider<FailureLimiter> limiter) {
        this.token = token;
        this.limiter = limiter;
    }

    /** required is false in the local profile; authenticated says whether this request carries a valid token. */
    @GetMapping("/status")
    public Map<String, Boolean> status(HttpServletRequest request) {
        AccessToken configured = token.getIfAvailable();
        boolean ok = configured == null || configured.matches(AuthFilter.presentedToken(request));
        return Map.of("required", configured != null, "authenticated", ok);
    }

    @PostMapping("/login")
    public ResponseEntity<Void> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        AccessToken configured = token.getIfAvailable();
        if (configured == null) {
            return ResponseEntity.noContent().build();
        }
        FailureLimiter failures = limiter.getObject();
        String client = AuthFilter.clientOf(request);
        Duration wait = failures.retryAfter(client);
        if (!wait.isZero()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(AuthFilter.retryAfterSeconds(wait))).build();
        }
        if (!configured.matches(body.token())) {
            failures.recordFailure(client);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        failures.recordSuccess(client);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie(body.token(), COOKIE_LIFETIME, request).toString()).build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO, request).toString()).build();
    }

    /** Secure when the request came over HTTPS, directly or through a proxy that says so. */
    private static ResponseCookie cookie(String value, Duration maxAge, HttpServletRequest request) {
        boolean https = request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
        return ResponseCookie.from(AuthFilter.COOKIE, value)
                .httpOnly(true).secure(https).sameSite("Strict").path("/").maxAge(maxAge).build();
    }
}
