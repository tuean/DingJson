package com.dingjson.exception;

/**
 * Runtime exception thrown by DingJson when JSON parsing or serialization fails.
 * Unchecked to keep the API clean — callers can catch if they wish.
 */
public class JSONException extends RuntimeException {

    public JSONException(String message) {
        super(message);
    }

    public JSONException(String message, Throwable cause) {
        super(message, cause);
    }
}
