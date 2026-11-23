package de.jare.rewisp.build;

/**
 * Exception during build resolution. The {@code errorCode} identifies the kind of error
 * as an enum, while {@code code()} provides the string for callers.
 * 
 * Beispiel-Fehlercodes: GOAL_NOT_ALLOWED, TIMEOUT, BUILD_FAILED, IO_ERROR, UNSUPPORTED.
 */
public class BuildResolutionException extends Exception {

    private final BuildResolutionErrorCode errorCode;

    public BuildResolutionException(BuildResolutionErrorCode errorCode, String message) {
        super(errorCode.name() + ": " + message);
        this.errorCode = errorCode;
    }

    public BuildResolutionException(BuildResolutionErrorCode errorCode, String message, Throwable cause) {
        super(errorCode.name() + ": " + message, cause);
        this.errorCode = errorCode;
    }

    /**
     * Returns the error code as enum. Preferred method for type-safe processing.
     */
    public BuildResolutionErrorCode errorCode() {
        return errorCode;
    }

    /**
     * Returns the error code as a string for callers that expect string codes.
     * This method is kept for backwards compatibility.
     */
    public String code() {
        return errorCode.name();
    }
}
