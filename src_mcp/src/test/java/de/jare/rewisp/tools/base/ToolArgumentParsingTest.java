/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import de.jare.rewisp.ProjectManager;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * AP-3.18/3.19: Argumente werden tolerant gelesen (String/Boolean/Zahl) statt per Cast zu scheitern;
 * die Liste neu abgelaufener Projekte gehoert zum jeweiligen Aufruf, nicht zum Tool-Objekt.
 *
 * @author Janusch Rentenatus
 */
class ToolArgumentParsingTest {

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path tempDir;

    private static CallToolRequest req(String key, Object value) {
        Map<String, Object> args = new HashMap<>();
        args.put(key, value);
        return new CallToolRequest("t", args);
    }

    @Test
    void boolArg_acceptsBooleanStringAndNumber() {
        assertTrue(BaseTool.toBoolean(Boolean.TRUE));
        assertTrue(BaseTool.toBoolean("true"));
        assertTrue(BaseTool.toBoolean(" TRUE "));
        assertTrue(BaseTool.toBoolean(1));
        assertFalse(BaseTool.toBoolean("false"));
        assertFalse(BaseTool.toBoolean(0));
        assertFalse(BaseTool.toBoolean(null));
        assertFalse(BaseTool.toBoolean("yes please"));
    }

    @Test
    void stringArg_acceptsNumbersAndBooleans() {
        assertEquals("42", BaseTool.arg(req("k", 42), "k"));
        assertEquals("true", BaseTool.arg(req("k", true), "k"));
        assertNull(BaseTool.arg(req("other", 1), "k"));
    }

    @Test
    void intArg_acceptsNumbersAndTextAndFallsBack() {
        assertEquals(7, BaseTool.intArg(req("n", 7), "n", -1));
        assertEquals(8, BaseTool.intArg(req("n", " 8 "), "n", -1));
        assertEquals(3.0, BaseTool.intArg(req("n", 3.9), "n", -1));
        assertEquals(-1, BaseTool.intArg(req("n", "abc"), "n", -1));
        assertEquals(-1, BaseTool.intArg(req("x", 1), "n", -1));
    }

    @Test
    void expiredNames_areScopedToTheCall() throws Exception {
        // zwei parallele Aufrufe desselben Tool-Objekts duerfen ihre Warnungen nicht vermischen
        AtomicReference<String> seenBySlow = new AtomicReference<>();
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch fastDone = new CountDownLatch(1);
        ProjectManager mgr = new ProjectManager();
        BaseProjectTool tool = new BaseProjectTool(mgr) {
            @Override protected String toolName() { return "scoped"; }
            @Override protected String toolDescription() { return "d"; }
            @Override protected Map<String, Object> toolProperties() { return Map.of(); }
            @Override protected List<String> toolRequired() { return List.of(); }
            @Override public CallToolResult doHandle(McpSyncServerExchange e, CallToolRequest r) throws Exception {
                if ("slow".equals(arg(r, "who"))) {
                    slowStarted.countDown();
                    fastDone.await();
                    seenBySlow.set(ok("x").content().toString());
                }
                return ok("x");
            }
        };
        var spec = tool.build();
        Thread slow = new Thread(() -> spec.callHandler().apply(null, req("who", "slow")));
        slow.start();
        slowStarted.await();
        // Jetzt laeuft ein Projekt ab: nur der schnelle Aufruf (der danach markExpired ausfuehrt) darf es melden
        java.nio.file.Path proj = tempDir.resolve("exp/src");
        java.nio.file.Files.createDirectories(proj);
        java.nio.file.Files.writeString(proj.resolve("A.java"), "class A {}");
        mgr.load(tempDir.resolve("exp").toString(), null, java.time.Instant.now().minusSeconds(5), false, true);
        var fastResult = spec.callHandler().apply(null, req("who", "fast"));
        assertTrue(fastResult.content().toString().contains("Expired"), fastResult.content().toString());
        fastDone.countDown();
        slow.join(10_000);

        assertFalse(seenBySlow.get().contains("Expired"), seenBySlow.get());
    }
}
