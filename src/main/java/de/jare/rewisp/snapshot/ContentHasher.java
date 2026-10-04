package de.jare.rewisp.snapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

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
     * Bildet einen Root-Hash aus allen Datei-Hashes.
     * Sortierte Iteration fuer Reproduzierbarkeit.
     */
    public String rootHash(Map<Path, String> contentHashes) {
        List<Path> sortedPaths = new ArrayList<>(contentHashes.keySet());
        Collections.sort(sortedPaths);

        StringBuilder sb = new StringBuilder();
        for (Path p : sortedPaths) {
            sb.append(p.toString()).append(':').append(contentHashes.get(p)).append('\n');
        }
        return sha256(sb.toString());
    }
}
