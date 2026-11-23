package de.jare.rewisp.build;

/**
 * Fingerprint of the build model. Used as a cache-key component
 * and for invalidation detection on pom.xml changes.
 *
 * @param buildSystem        build system identifier (e.g. "MAVEN")
 * @param buildFilesHash      SHA-256 of the build files (pom.xml)
 * @param classpathHash       SHA-256 of the resolved classpath
 * @param sourceRootsHash     SHA-256 of the source root paths
 * @param toolchainHash       toolchain identifier (Java version)
 * @param activeProfilesHash  SHA-256 of the active Maven profiles
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
