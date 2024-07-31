package com.example.migratorx.utils.json.exception;

public class JsonParserException extends RuntimeException {

    public JsonParserException(String message, Throwable cause) {
        super(message, cause);
    }

    public JsonParserException(String message) {
        super(message);
    }

    public JsonParserException(Throwable cause) {
        super(cause);
    }
}
