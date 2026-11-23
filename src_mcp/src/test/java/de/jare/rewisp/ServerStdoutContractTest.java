/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * AP-0.5 (RT:P-01): Logging-Vertrag des stdio-Servers. {@code stdout} gehoert ausschliesslich dem
 * JSON-RPC-Protokoll; Log-Ausgaben duerfen dort nie erscheinen. Der Test startet den echten Server
 * in einem Kindprozess, sendet {@code initialize} und prueft, dass die erste Zeile auf stdout die
 * gueltige JSON-RPC-Antwort ist.
 *
 * @author Janusch Rentenatus
 */
class ServerStdoutContractTest {

    private static final long TIMEOUT_SECONDS = 90;

    /** Kein @TempDir: der Kindprozess haelt unter Windows Logdateien teils laenger offen als das Aufraeumen erlaubt. */
    private Path home;

    @Test
    void firstStdoutLine_isJsonRpcInitializeResponse() throws Exception {
        home = Files.createTempDirectory("rewisp-stdout-contract");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java,
                "-Duser.home=" + home,
                "-cp", System.getProperty("java.class.path"),
                "de.jare.rewisp.JavaMcpServer");
        pb.redirectError(home.resolve("stderr.log").toFile());
        Process process = pb.start();
        try {
            Writer in = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            in.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                    + "\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"contract-test\",\"version\":\"1\"}}}\n");
            in.flush();

            BufferedReader out = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            String first = CompletableFuture.supplyAsync(() -> {
                try {
                    return out.readLine();
                } catch (Exception e) {
                    return null;
                }
            }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertNotNull(first, "Server hat nichts auf stdout geschrieben");
            assertTrue(first.startsWith("{") && first.contains("\"jsonrpc\""),
                    "Erste stdout-Zeile ist kein JSON-RPC: " + first);
            assertTrue(first.contains("\"id\":1") && first.contains("\"result\""),
                    "Erwartet wird die initialize-Antwort: " + first);
        } finally {
            process.destroyForcibly();
            // Auf das Ende warten: unter Windows haelt der Kindprozess sonst die Logdateien im @TempDir offen
            process.waitFor(15, TimeUnit.SECONDS);
            deleteBestEffort(home);
        }
    }

    /** AP-5.4 (RT:P-02): Schliesst der Client stdin, beendet sich der Server (kein verwaister Prozess). */
    @Test
    void serverExits_whenStdinIsClosed() throws Exception {
        home = Files.createTempDirectory("rewisp-stdin-eof");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java,
                "-Duser.home=" + home,
                "-cp", System.getProperty("java.class.path"),
                "de.jare.rewisp.JavaMcpServer");
        pb.redirectError(home.resolve("stderr.log").toFile());
        Process process = pb.start();
        try {
            Writer in = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            in.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                    + "\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"eof-test\",\"version\":\"1\"}}}\n");
            in.flush();
            BufferedReader out = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            String first = CompletableFuture.supplyAsync(() -> {
                try {
                    return out.readLine();
                } catch (Exception e) {
                    return null;
                }
            }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull(first, "Server hat nichts auf stdout geschrieben");

            in.close(); // EOF auf stdin

            assertTrue(process.waitFor(30, TimeUnit.SECONDS),
                    "Server muss sich nach stdin-EOF beenden");
        } finally {
            process.destroyForcibly();
            process.waitFor(15, TimeUnit.SECONDS);
            deleteBestEffort(home);
        }
    }

    private static void deleteBestEffort(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (java.io.IOException | RuntimeException e) {
            // Aufraeumen ist optional
        }
    }
}
