/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * @author Janusch Rentenatus
 */
class EofSignalingInputStreamTest {

    @Test
    void signalsOnceAtEndOfStream() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var in = new EofSignalingInputStream(new ByteArrayInputStream(new byte[] {1, 2}), calls::incrementAndGet);

        assertEquals(1, in.read());
        assertEquals(0, calls.get());
        assertEquals(1, in.read(new byte[8], 0, 8));
        assertEquals(0, calls.get());
        assertEquals(-1, in.read());
        assertEquals(-1, in.read(new byte[8], 0, 8));

        assertEquals(1, calls.get());
    }

    @Test
    void signalsOnReadFailure() {
        AtomicInteger calls = new AtomicInteger();
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("boom"); }
        };
        var in = new EofSignalingInputStream(broken, calls::incrementAndGet);

        try {
            in.read();
        } catch (IOException expected) {
            // erwartet
        }

        assertEquals(1, calls.get());
    }
}
