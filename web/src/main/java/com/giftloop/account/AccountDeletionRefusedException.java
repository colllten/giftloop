package com.giftloop.account;

/**
 * The database refused an account deletion step (e.g. a chosen owner left the group).
 * The message is safe to show to the user.
 */
public class AccountDeletionRefusedException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message user-facing reason
     */
    public AccountDeletionRefusedException(String message) {
        super(message);
    }
}
