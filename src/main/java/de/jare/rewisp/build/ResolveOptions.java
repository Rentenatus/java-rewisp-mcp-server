package de.jare.rewisp.build;

/**
 * Optionen fuer {@link BuildResolver#resolve}.
 *
 * @param includeTestClasspath  Test-Classpath mit aufloesen
 * @param timeoutSeconds        Subprocess-Timeout
 * @param maxOutputBytes         Output-Limit in Bytes
 * @param networkAccess          Netzwerkzugriff erlaubt (offline-Modus wenn false)
 */
public record ResolveOptions(
        boolean includeTestClasspath,
        int timeoutSeconds,
        int maxOutputBytes,
        boolean networkAccess
) {
    public static ResolveOptions defaults() {
        return new ResolveOptions(false, 120, 10_485_760, false);
    }
}
