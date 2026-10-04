package de.jare.rewisp.build;

/**
 * Exception bei Build-Resolution. Der {@code errorCode} identifiziert die Fehlerart
 * als Enum, waehrend {@code code()} den String fuer Caller bereitstellt.
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
     * Liefert den Fehlercode als Enum. Bevorzugte Methode fuer typsichere Verarbeitung.
     */
    public BuildResolutionErrorCode errorCode() {
        return errorCode;
    }

    /**
     * Liefert den Fehlercode als String fuer Caller, die String-Codes erwarten.
     * Diese Methode bleibt zur AbwaertsKompatibilitaet erhalten.
     */
    public String code() {
        return errorCode.name();
    }
}
