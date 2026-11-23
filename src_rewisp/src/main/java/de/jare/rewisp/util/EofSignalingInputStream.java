/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.util;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reports the end of the input stream (EOF or read error) exactly once to a callback. The stdio
 * server uses this to terminate when the client closes stdin - the transport object itself
 * does not propagate this event to the outside.
 *
 * @author Janusch Rentenatus
 */
public final class EofSignalingInputStream extends FilterInputStream {

    private final Runnable onEof;
    private boolean signaled;

    public EofSignalingInputStream(InputStream in, Runnable onEof) {
        super(in);
        this.onEof = onEof;
    }

    @Override
    public int read() throws IOException {
        try {
            int b = super.read();
            if (b < 0) signal();
            return b;
        } catch (IOException e) {
            signal();
            throw e;
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        try {
            int n = super.read(b, off, len);
            if (n < 0) signal();
            return n;
        } catch (IOException e) {
            signal();
            throw e;
        }
    }

    private synchronized void signal() {
        if (!signaled) {
            signaled = true;
            onEof.run();
        }
    }
}
