package com.giftloop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads sign-in codes from the local Supabase Mailpit inbox (http://127.0.0.1:54324).
 */
public final class Mailpit {

    private static final String BASE = "http://127.0.0.1:54324/api/v1";
    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    private Mailpit() {
    }

    /**
     * Latest 6-digit code sent to an address, polling briefly for delivery.
     *
     * @param email recipient
     * @return the code
     * @throws IllegalStateException if no code arrives
     */
    public static String latestCode(String email) {
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                JsonNode messages = get("/search?limit=1&query=" + URLEncoder.encode("to:" + email, StandardCharsets.UTF_8))
                        .get("messages");
                if (messages != null && !messages.isEmpty()) {
                    String text = get("/message/" + messages.get(0).get("ID").asText()).get("Text").asText();
                    Matcher m = CODE.matcher(text);
                    if (m.find()) {
                        return m.group(1);
                    }
                }
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                throw new IllegalStateException("Mailpit not reachable", e);
            }
        }
        throw new IllegalStateException("No sign-in code received for " + email);
    }

    private static JsonNode get(String path) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(BASE + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return JSON.readTree(response.body());
    }
}
