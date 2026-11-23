/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.analysis;

/**
 * A requested type does not exist in the model. An {@link IllegalArgumentException} so existing callers
 * keep working unchanged; the tool layer reports it as {@code TYPE_NOT_FOUND} instead of a generic error.
 *
 * @author Janusch Rentenatus
 */
public final class TypeNotFoundException extends IllegalArgumentException {

    private final String typeName;

    public TypeNotFoundException(String message, String typeName) {
        super(message);
        this.typeName = typeName;
    }

    /** The qualified name that was searched for. */
    public String typeName() {
        return typeName;
    }
}
