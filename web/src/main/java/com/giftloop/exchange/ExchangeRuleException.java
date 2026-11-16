package com.giftloop.exchange;

/**
 * An exchange action the rules refused (too few people, no draw fits, already drawn). The message
 * is shown to the user in the exchange panel.
 */
public class ExchangeRuleException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message user-facing message
     */
    public ExchangeRuleException(String message) {
        super(message);
    }
}
