package com.giftloop.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Reads the payload of a compact JWT <b>without verifying it</b>. Use only on
 * tokens already verified by SmallRye JWT, or for non-security decisions such as
 * "is this cookie about to expire?".
 */
public final class JwtPayload {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JwtPayload() {
    }

    /**
     * Decodes the payload segment.
     *
     * @param rawToken compact token ({@code header.payload.signature})
     * @return payload JSON
     * @throws IllegalArgumentException if the token is not a compact JWS
     */
    public static String json(String rawToken) {
        if (rawToken == null) {
            throw new IllegalArgumentException("Token is missing");
        }
        String[] parts = rawToken.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Token is not a compact JWS");
        }
        try {
            return new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Token payload is not base64url", e);
        }
    }

    /**
     * Reads the {@code exp} claim.
     *
     * @param rawToken compact token
     * @return expiry, or empty if the token is malformed or has no {@code exp}
     */
    public static Optional<Instant> expiry(String rawToken) {
        try {
            JsonNode exp = JSON.readTree(json(rawToken)).get("exp");
            return exp != null && exp.canConvertToLong()
                    ? Optional.of(Instant.ofEpochSecond(exp.asLong()))
                    : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
