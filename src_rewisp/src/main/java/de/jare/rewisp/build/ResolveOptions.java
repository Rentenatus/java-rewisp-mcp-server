package de.jare.rewisp.build;

/**
 * Options for {@link BuildResolver#resolve}.
 *
 * @param includeTestClasspath  resolve the test classpath too
 * @param timeoutSeconds        subprocess timeout
 * @param maxOutputBytes         output limit in bytes
 * @param networkAccess          network access allowed (offline mode when false)
 */
public record ResolveOptions(
        boolean includeTestClasspath,
        int timeoutSeconds,
        int maxOutputBytes,
        boolean networkAccess
) {
    /** System property with which build resolution gets network access (default: offline, -o). */
    public static final String NETWORK_PROPERTY = "build.networkAccess";

    public static ResolveOptions defaults() {
        return new ResolveOptions(false, 120, 10_485_760, Boolean.getBoolean(NETWORK_PROPERTY));
    }
}
