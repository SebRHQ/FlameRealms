package com.flamerealms.util;

/**
 * Thrown by {@link MoneyParsing#parseAmountToCents(String)} for input a
 * player should fix and retry. Carries a {@code messages.yml} key rather
 * than a literal message, so every caller can render it however it likes
 * (typically {@code Messages.get(exception.getMessageKey())}).
 *
 * <p>This is the shared replacement for what used to be a private nested
 * exception inside {@code RealmCommand}; that command's own copy is left
 * untouched by this stage (a later stage removes it and switches to this
 * one), so both currently coexist without conflicting.
 */
public final class InvalidAmountException extends RuntimeException {

    private final String messageKey;

    public InvalidAmountException(String messageKey) {
        super(messageKey);
        this.messageKey = messageKey;
    }

    public String getMessageKey() {
        return messageKey;
    }
}
