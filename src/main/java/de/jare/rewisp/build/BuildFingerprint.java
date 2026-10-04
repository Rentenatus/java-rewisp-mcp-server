package de.jare.rewisp.build;

/**
 * Fingerprint des Build-Modells. Wird als Cache-Key-Bestandteil verwendet
 * und zur Invalidierungserkennung bei pom.xml-Aenderungen herangezogen.
 *
 * @param buildSystem        Build-System-Kennung (z.B. "MAVEN")
 * @param buildFilesHash      SHA-256 der Build-Dateien (pom.xml)
 * @param classpathHash       SHA-256 des aufgeloesten Classpath
 * @param sourceRootsHash     SHA-256 der Source-Root-Pfade
 * @param toolchainHash       Toolchain-Kennung (Java-Version)
 * @param activeProfilesHash  SHA-256 der aktiven Maven-Profile
 */
public record BuildFingerprint(
        String buildSystem,
        String buildFilesHash,
        String classpathHash,
        String sourceRootsHash,
        String toolchainHash,
        String activeProfilesHash
) {
    public String compositeHash() {
        return buildFilesHash + ":" + classpathHash + ":" + sourceRootsHash
                + ":" + toolchainHash + ":" + activeProfilesHash;
    }
}
