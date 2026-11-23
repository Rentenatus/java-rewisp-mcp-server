/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import de.jare.rewisp.ProjectManager;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * AP-1.16: Edit-Tools und mutierende Lifecycle-Tools laufen unter derselben prozessglobalen Sperre;
 * rein lesende Tools nicht.
 *
 * @author Janusch Rentenatus
 */
class ProjectLockTest {

    private static final AtomicInteger CONCURRENT = new AtomicInteger();
    private static final AtomicInteger MAX_CONCURRENT = new AtomicInteger();

    /** Tool, das waehrend seiner Ausfuehrung kurz blockiert und die Gleichzeitigkeit misst. */
    private abstract static class SlowTool {
        CallToolResult run() {
            int now = CONCURRENT.incrementAndGet();
            MAX_CONCURRENT.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                CONCURRENT.decrementAndGet();
            }
            return CallToolResult.builder().addTextContent("ok").build();
        }
    }

    private static McpServerFeatures.SyncToolSpecification editTool(String name) {
        return new BaseEditTool(new ProjectManager()) {
            @Override protected String toolName() { return name; }
            @Override protected String toolDescription() { return "d"; }
            @Override protected Map<String, Object> toolProperties() { return Map.of(); }
            @Override protected List<String> toolRequired() { return List.of(); }
            @Override public CallToolResult doHandle(McpSyncServerExchange e, CallToolRequest r) {
                return new SlowTool() { }.run();
            }
        }.build();
    }

    private static McpServerFeatures.SyncToolSpecification lifecycleTool(String name, boolean mutating) {
        return new BaseProjectTool(new ProjectManager()) {
            @Override protected boolean requiresProjectLock() { return mutating; }
            @Override protected String toolName() { return name; }
            @Override protected String toolDescription() { return "d"; }
            @Override protected Map<String, Object> toolProperties() { return Map.of(); }
            @Override protected List<String> toolRequired() { return List.of(); }
            @Override public CallToolResult doHandle(McpSyncServerExchange e, CallToolRequest r) {
                return new SlowTool() { }.run();
            }
        }.build();
    }

    private static int maxConcurrency(McpServerFeatures.SyncToolSpecification a,
                                      McpServerFeatures.SyncToolSpecification b) throws Exception {
        CONCURRENT.set(0);
        MAX_CONCURRENT.set(0);
        CountDownLatch start = new CountDownLatch(1);
        CallToolRequest req = new CallToolRequest("t", Map.of());
        Thread t1 = new Thread(() -> { await(start); a.callHandler().apply(null, req); });
        Thread t2 = new Thread(() -> { await(start); b.callHandler().apply(null, req); });
        t1.start();
        t2.start();
        start.countDown();
        t1.join(TimeUnit.SECONDS.toMillis(20));
        t2.join(TimeUnit.SECONDS.toMillis(20));
        return MAX_CONCURRENT.get();
    }

    private static void await(CountDownLatch l) {
        try {
            l.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void editAndMutatingLifecycleTool_neverRunConcurrently() throws Exception {
        assertEquals(1, maxConcurrency(editTool("e"), lifecycleTool("reload", true)));
    }

    @Test
    void twoEditTools_neverRunConcurrently() throws Exception {
        assertEquals(1, maxConcurrency(editTool("e1"), editTool("e2")));
    }

    @Test
    void readOnlyLifecycleTool_isNotSerialized() throws Exception {
        int max = maxConcurrency(lifecycleTool("list", false), lifecycleTool("meta", false));
        assertTrue(max >= 2, "lesende Tools duerfen parallel laufen, max=" + max);
        assertFalse(max < 1);
    }
}
