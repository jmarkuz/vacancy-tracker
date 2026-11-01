package com.vacancytracker.web.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** The configured access token and the constant-time check against what a client sent. */
public final class AccessToken {

    static final int MIN_LENGTH = 16;

    private final byte[] digest;

    public AccessToken(String token) {
        if (token == null || token.length() < MIN_LENGTH) {
            throw new IllegalStateException("app.access-token (APP_ACCESS_TOKEN) must be set to at least "
                    + MIN_LENGTH + " characters when the vps profile is active");
        }
        this.digest = sha256(token);
    }

    /** Compares digests of equal length with MessageDigest.isEqual, so timing does not reveal how much matched. */
    public boolean matches(String candidate) {
        return candidate != null && MessageDigest.isEqual(digest, sha256(candidate));
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
