package com.example.err;

public class TuiException extends RuntimeException {
    private final String userMessageKey;

    public TuiException(String message, String userMessageKey) {
        super(message);
        this.userMessageKey = userMessageKey;
    }

    public TuiException(String message, String userMessageKey, Throwable cause) {
        super(message, cause);
        this.userMessageKey = userMessageKey;
    }

    public String getUserMessageKey() { return userMessageKey; }
}
