package com.giftloop.supabase;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Tokens returned by Supabase Auth on sign-in, code verification and refresh.
 *
 * @param accessToken  short-lived JWT
 * @param refreshToken single-use refresh token
 * @param expiresIn    access token lifetime in seconds
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthSession(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("expires_in") long expiresIn) {
}
