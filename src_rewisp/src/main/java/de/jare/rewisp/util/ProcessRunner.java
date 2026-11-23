/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.util;

import java.util.Locale;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Starts a child process (Maven, Gradle, Delombok) with time and output limits.
 *
 * <p>The output is read in a dedicated thread, so that the time limit also applies to a process
 * that no longer writes anything or hangs. On abort the child processes are also terminated
 * (Maven/Gradle start further JVMs), stdin is closed immediately and stderr is mixed into stdout.</p>
 *
 * @author Janusch Rentenatus
 */
public final class ProcessRunner {

    private ProcessRunner() {
    }

    /**
     * @param exitCode            exit code; not meaningful on time/output limit
     * @param output              read output (on output limit the part read so far)
     * @param timedOut            time limit exceeded, process was terminated
     * @param outputLimitExceeded output limit exceeded, process was terminated
     */
    public record Result(int exitCode, String output, boolean timedOut, boolean outputLimitExceeded) {
        public boolean success() {
            return !timedOut && !outputLimitExceeded && exitCode == 0;
        }

        /** The last {@code max} characters of the output (for error messages). */
        public String tail(int max) {
            return output.length() <= max ? output : "..." + output.substring(output.length() - max);
        }
    }

    /** Characters that cmd.exe evaluates in the arguments of a .bat/.cmd call (BatBadBut). */
    private static final String CMD_METACHARACTERS = "&|<>^%\"!\r\n";

    /**
     * Checks the invocation of a Windows batch script ({@code .bat}/{@code .cmd}): Java starts it via
     * {@code cmd.exe}, which evaluates arguments by its own rules. An argument containing {@code & | < > ^ % " !}
     * or a line break could execute additional commands (BatBadBut).
     *
     * @return the first unsafe argument, otherwise {@code null} (also for non-batch programs)
     */
    public static String unsafeBatchArgument(List<String> command) {
        if (command == null || command.isEmpty()) return null;
        String exe = command.get(0).toLowerCase(Locale.ROOT);
        if (!exe.endsWith(".bat") && !exe.endsWith(".cmd")) return null;
        for (int i = 1; i < command.size(); i++) {
            String arg = command.get(i);
            for (int c = 0; c < arg.length(); c++) {
                if (CMD_METACHARACTERS.indexOf(arg.charAt(c)) >= 0) return arg;
            }
        }
        return null;
    }

    /**
     * @param environmentRemove names of environment variables that are not passed to the child process
     * @throws IOException when the process cannot be started
     * @throws InterruptedException when the waiting thread is interrupted (process is terminated)
     */
    public static Result run(List<String> command, Path workDir, long timeoutSeconds, int maxOutputBytes,
                             List<String> environmentRemove) throws IOException, InterruptedException {
        String unsafe = unsafeBatchArgument(command);
        if (unsafe != null) {
            throw new IOException("Refusing to start a batch file with an argument that cmd.exe would interpret: "
                    + unsafe);
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workDir != null) pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        for (String name : environmentRemove) {
            pb.environment().remove(name);
        }
        Process process = pb.start();
        process.getOutputStream().close(); // kein Prozess soll auf Eingaben warten

        StringBuilder output = new StringBuilder();
        boolean[] limit = new boolean[1];
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    synchronized (output) {
                        output.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        if (output.length() > maxOutputBytes) {
                            limit[0] = true;
                            killTree(process);
                            return;
                        }
                    }
                }
            } catch (IOException e) {
                // Stream wurde durch das Beenden des Prozesses geschlossen
            }
        }, "process-output-reader");
        reader.setDaemon(true);
        reader.start();

        try {
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                killTree(process);
                reader.join(2000);
                return new Result(-1, snapshot(output), true, false);
            }
            reader.join(5000);
        } catch (InterruptedException e) {
            killTree(process);
            throw e;
        }
        boolean exceeded;
        synchronized (output) {
            exceeded = limit[0];
        }
        return new Result(exceeded ? -1 : process.exitValue(), snapshot(output), false, exceeded);
    }

    private static String snapshot(StringBuilder output) {
        synchronized (output) {
            return output.toString();
        }
    }

    /** Terminates the process along with all descendants. */
    static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
