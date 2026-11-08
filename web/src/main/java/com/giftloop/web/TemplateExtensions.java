package com.giftloop.web;

import io.quarkus.qute.TemplateExtension;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Helpers callable from Qute templates.
 */
@TemplateExtension
public final class TemplateExtensions {

    private TemplateExtensions() {
    }

    /**
     * URL-encodes a query parameter value: {@code {next.urlEncode}}.
     *
     * @param value raw value
     * @return encoded value
     */
    static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
