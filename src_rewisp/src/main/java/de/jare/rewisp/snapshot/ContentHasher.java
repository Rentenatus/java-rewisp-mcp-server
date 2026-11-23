package de.jare.rewisp.snapshot;

import java.util.TreeMap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.nio.file.Path;

public final class ContentHasher {

    public String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Builds a root hash from all file hashes.
     * Sorted iteration for reproducibility.
     */
    public String rootHash(Map<Path, String> contentHashes) {
        // Pfade mit "/" normalisieren: derselbe Inhalt ergibt auf jedem Betriebssystem denselben Hash
        TreeMap<String, String> sorted = new TreeMap<>();
        contentHashes.forEach((p, h) -> sorted.put(p.toString().replace('\\', '/'), h));
        StringBuilder sb = new StringBuilder();
        sorted.forEach((p, h) -> sb.append(p).append(':').append(h).append('\n'));
        return sha256(sb.toString());
    }
}
