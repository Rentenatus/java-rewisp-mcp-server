/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.project;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Process-wide lock for all calls that change project, snapshot or transaction state:
 * edit tools, transaction tools and the mutating lifecycle tools (load, reload,
 * unload, reboot, build refresh).
 *
 * <p>MCP clients can issue tool calls in parallel. Without serialization,
 * overlapping edits of the same project would overwrite each other's buffered predecessors (last
 * writer wins, found on 2026-10-03 in live operation), and a parallel
 * {@code reload}/{@code unload} would swap entry, snapshot and transaction buffer under a
 * running edit. Transactions are process-global, edit throughput in practice is
 * sequential - a global lock is therefore deliberate. The lock is reentrant.</p>
 *
 * @author Janusch Rentenatus
 */
public final class ProjectLock {

    private static final ReentrantLock LOCK = new ReentrantLock();

    private ProjectLock() {
    }

    public static void lock() {
        LOCK.lock();
    }

    public static void unlock() {
        LOCK.unlock();
    }
}