package com.giftloop.supabase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

/**
 * Turns Supabase error responses into {@link SupabaseException}. Handles both
 * Auth ({@code error_code}/{@code msg}) and Storage ({@code error}/{@code message}) shapes.
 */
public class SupabaseErrorMapper implements ResponseExceptionMapper<SupabaseException> {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Maps a non-2xx response.
     *
     * @param response Supabase response
     * @return the exception to throw
     */
    @Override
    public SupabaseException toThrowable(Response response) {
        String body = response.hasEntity() ? response.readEntity(String.class) : "";
        String code = null;
        String message = body;
        try {
            JsonNode node = JSON.readTree(body);
            code = text(node, "error_code", "error");
            message = text(node, "msg", "message", "error_description");
        } catch (Exception ignored) {
            // Non-JSON body: keep raw text as the message.
        }
        return new SupabaseException(response.getStatus(), code, message);
    }

    /**
     * Returns the first non-blank text field found.
     *
     * @param node   JSON object
     * @param fields candidate field names, in order
     * @return field text or null
     */
    private static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isValueNode() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }
}
