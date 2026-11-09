package com.giftloop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.microprofile.config.ConfigProvider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Obtains real access tokens from local Supabase Auth for seed users
 * (see database/supabase/seed.sql). Requires {@code supabase start} + {@code db reset}.
 */
public final class SupabaseAuthTestClient {

    private static final String SEED_PASSWORD = "password123";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    private SupabaseAuthTestClient() {
    }

    /**
     * Signs in a seed user with the password grant.
     *
     * @param username seed username, e.g. {@code bob} (email {@code bob@example.com})
     * @return the raw access token
     * @throws IllegalStateException if sign-in fails
     */
    public static String accessToken(String username) {
        String url = ConfigProvider.getConfig().getValue("supabase.url", String.class);
        String key = ConfigProvider.getConfig().getValue("supabase.publishable-key", String.class);
        String body = "{\"email\":\"%s@example.com\",\"password\":\"%s\"}".formatted(username, SEED_PASSWORD);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/auth/v1/token?grant_type=password"))
                .header("apikey", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Sign-in failed for " + username + ": " + response.body());
            }
            return JSON.readTree(response.body()).get("access_token").asText();
        } catch (IOException e) {
            throw new IllegalStateException("Is local Supabase running? (supabase start)", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted signing in " + username, e);
        }
    }
}
