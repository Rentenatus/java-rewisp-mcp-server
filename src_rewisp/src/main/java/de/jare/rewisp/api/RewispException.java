/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.api;

import java.util.Map;

import de.jare.rewisp.model.ErrorType;

/**
 * Domain error of the {@link RewispDriver} interface (project unknown, type not found,
 * invalid parameter, project not loadable, ...). It carries a stable {@link #code()}
 * (name of an {@link ErrorType} or a load error type like {@code INVALID_PARAMS}), the message
 * and optional context values (e.g. {@code className}). Technical errors remain ordinary
 * runtime exceptions.
 *
 * @author Janusch Rentenatus
 */
public class RewispException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final transient Map<String, Object> context;

    public RewispException(String code, String message) {
        this(code, message, Map.of());
    }

    public RewispException(ErrorType type, String message) {
        this(type.name(), message, Map.of());
    }

    public RewispException(ErrorType type, String message, Map<String, Object> context) {
        this(type.name(), message, context);
    }

    public RewispException(String code, String message, Map<String, Object> context) {
        super(message);
        this.code = code == null ? ErrorType.DOMAIN_ERROR.name() : code;
        this.context = context == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(context));
    }

    /** Stabiler, maschinenlesbarer Fehlercode. */
    public String code() {
        return code;
    }

    /** Additional details about the error (never {@code null}). */
    public Map<String, Object> context() {
        return context;
    }
}
