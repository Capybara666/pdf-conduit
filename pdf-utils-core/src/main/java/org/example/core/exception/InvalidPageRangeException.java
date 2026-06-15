package org.example.core.exception;

public class InvalidPageRangeException extends Exception {
    public InvalidPageRangeException(String expression) {
        super("Invalid page range expression: \"" + expression + "\"");
    }
}
