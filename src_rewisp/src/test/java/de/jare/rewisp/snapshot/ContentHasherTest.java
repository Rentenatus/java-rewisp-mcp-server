package de.jare.rewisp.snapshot;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContentHasherTest {

    private final ContentHasher hasher = new ContentHasher();

    @Test
    void sha256_knownValue() {
        // Known SHA-256 of "hello"
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                hasher.sha256("hello"));
    }

    @Test
    void sha256_sameContent_sameHash() {
        assertEquals(hasher.sha256("test content"), hasher.sha256("test content"));
    }

    @Test
    void sha256_differentContent_differentHash() {
        assertNotEquals(hasher.sha256("foo"), hasher.sha256("bar"));
    }

    @Test
    void rootHash_independentOfIterationOrder() {
        Map<Path, String> hashes1 = new LinkedHashMap<>();
        hashes1.put(Path.of("a.java"), "hashA");
        hashes1.put(Path.of("b.java"), "hashB");
        hashes1.put(Path.of("c.java"), "hashC");

        Map<Path, String> hashes2 = new LinkedHashMap<>();
        hashes2.put(Path.of("c.java"), "hashC");
        hashes2.put(Path.of("a.java"), "hashA");
        hashes2.put(Path.of("b.java"), "hashB");

        assertEquals(hasher.rootHash(hashes1), hasher.rootHash(hashes2));
    }

    @Test
    void rootHash_emptyMap_definedHash() {
        String result = hasher.rootHash(Map.of());
        assertNotNull(result);
        assertFalse(result.isBlank());
        // Empty input should still produce a valid SHA-256 (hash of empty string)
        assertEquals(64, result.length());
    }
}
