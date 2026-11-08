package com.voxymap;

import net.minecraft.client.Minecraft;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional per-tile lifecycle trace. Disabled by default; when enabled it writes one line per
 * cache/source/color/texture/evict stage to logs/voxymap-tile-<session>.log.
 *
 * This is deliberately asynchronous: tile loading is already latency-sensitive, so callers only
 * pay for an enabled check and a queue offer unless this debug switch is on.
 */
public final class TileTrace {
    private static final int QUEUE_CAP = 100_000;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static final BlockingQueue<String> QUEUE = new LinkedBlockingQueue<>(QUEUE_CAP);
    private static final AtomicLong DROPPED = new AtomicLong();
    private static volatile boolean started;
    private static volatile boolean broken;
    private static volatile Path logFile;

    private TileTrace() {
    }

    public static boolean enabled() {
        return VoxyMapConfig.DEBUG_TILE_TRACE.get();
    }

    public static Path logFile() {
        return logFile;
    }

    /** tx,tz,stage and a compact detail string (the caller should only call this when enabled). */
    public static void stage(int tx, int tz, String stage, String detail) {
        if (!enabled() || broken) return;
        ensureStarted();
        if (broken) return;
        String line = TIME.format(Instant.now()) + " " + Thread.currentThread().getName()
                + " tile=" + tx + "," + tz + " " + stage
                + (detail == null || detail.isEmpty() ? "" : " " + detail);
        if (!QUEUE.offer(line)) DROPPED.incrementAndGet();
    }

    public static void timed(int tx, int tz, String stage, long startNanos, String detail) {
        if (!enabled()) return;
        long ns = System.nanoTime() - startNanos;
        stage(tx, tz, stage, String.format(java.util.Locale.ROOT, "took=%.2fms%s",
                ns / 1_000_000.0, detail == null || detail.isEmpty() ? "" : " " + detail));
    }

    private static synchronized void ensureStarted() {
        if (started) return;
        started = true;
        try {
            Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("logs");
            Files.createDirectories(dir);
            String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .withZone(ZoneId.systemDefault()).format(Instant.now());
            logFile = dir.resolve("voxymap-tile-" + stamp + ".log");
            BufferedWriter out = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Thread writer = new Thread(() -> {
                try {
                    out.write("voxymap tile trace session=" + Instant.now() + "\n");
                    out.flush();
                    while (!Thread.currentThread().isInterrupted()) {
                        String line = QUEUE.poll(500, TimeUnit.MILLISECONDS);
                        while (line != null) {
                            out.write(line);
                            out.write('\n');
                            line = QUEUE.poll();
                        }
                        long dropped = DROPPED.getAndSet(0);
                        if (dropped > 0) out.write("# dropped " + dropped + " lines\n");
                        out.flush();
                    }
                } catch (InterruptedException ignored) {
                } catch (Throwable t) {
                    broken = true;
                } finally {
                    try {
                        out.close();
                    } catch (Throwable ignored) {
                    }
                }
            }, "voxymap-tile-trace");
            writer.setDaemon(true);
            writer.setPriority(Thread.MIN_PRIORITY);
            writer.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                writer.interrupt();
                try {
                    writer.join(500);
                } catch (InterruptedException ignored) {
                }
            }, "voxymap-tile-trace-flush"));
        } catch (Throwable t) {
            broken = true;
        }
    }
}
