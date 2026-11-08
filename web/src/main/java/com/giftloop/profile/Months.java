package com.giftloop.profile;

import java.time.Month;
import java.time.format.TextStyle;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Month names for birthday inputs and labels.
 */
public final class Months {

    /**
     * Option for a month select.
     *
     * @param value 1–12
     * @param name  English month name
     */
    public record Option(int value, String name) {
    }

    /** All twelve months, January first. */
    public static final List<Option> OPTIONS = Arrays.stream(Month.values())
            .map(m -> new Option(m.getValue(), name(m.getValue())))
            .toList();

    private Months() {
    }

    /**
     * English month name.
     *
     * @param month 1–12
     * @return e.g. "March"
     */
    public static String name(int month) {
        return Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }
}
