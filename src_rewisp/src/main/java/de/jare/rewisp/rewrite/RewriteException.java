/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

/**
 * Exception for errors during OpenRewrite transformations.
 */
public class RewriteException extends Exception {
    
    private final String code;
    
    public RewriteException(String code, String message) {
        super(code + ": " + message);
        this.code = code;
    }
    
    public RewriteException(String code, String message, Throwable cause) {
        super(code + ": " + message, cause);
        this.code = code;
    }
    
    public String code() {
        return code;
    }
}
