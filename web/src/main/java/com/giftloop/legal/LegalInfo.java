package com.giftloop.legal;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Values shown in the legal documents.
 *
 * @param version      current document version, an ISO date ({@code giftloop.legal.version})
 * @param contactEmail address for privacy and legal requests
 */
public record LegalInfo(String version, String contactEmail) {

    /** Person operating GiftLoop, named in the documents. */
    public static final String OPERATOR = "Colten Glover";

    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);

    /**
     * Validates the version up front so a bad config fails at startup, not on a page view.
     *
     * @param version      ISO date, e.g. {@code 2026-11-16}
     * @param contactEmail contact address
     * @throws IllegalArgumentException if the version is not an ISO date or the email is blank
     */
    public LegalInfo {
        LocalDate.parse(version);
        if (contactEmail == null || contactEmail.isBlank()) {
            throw new IllegalArgumentException("giftloop.legal.contact-email must be set");
        }
    }

    /**
     * Version as a readable date for "Last updated".
     *
     * @return e.g. {@code November 16, 2026}
     */
    public String updated() {
        return LocalDate.parse(version).format(LONG_DATE);
    }

    /**
     * Operator name for templates.
     *
     * @return {@link #OPERATOR}
     */
    public String operator() {
        return OPERATOR;
    }
}
