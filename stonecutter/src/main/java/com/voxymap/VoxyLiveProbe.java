package com.voxymap;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Event-driven map PoC: coalesce Voxy's live section dirty callbacks by key, then let a low-priority
 * worker fetch the latest section with the official {@code WorldEngine.acquireIfExists(key)} API.
 *
 * <p>The callback only records (level, key, flags); it never copies voxel data. This measures the
 * real work an event-driven map would have to do after duplicate dirty events are merged.</p>
 *
 * <p>Purely reflective and read-only. If Voxy classes or the private dirtyCallback field are not
 * available the probe disables itself; the normal refresh pipeline is never touched.</p>
 */
public final class VoxyLiveProbe {
    private static final String IDENTIFIER = "me.cortex.voxy.commonImpl.WorldIdentifier";
    private static final String ENGINE = "me.cortex.voxy.common.world.WorldEngine";
    private static final String CALLBACK = ENGINE + "$ISectionChangeCallback";
    private static final String SECTION = "me.cortex.voxy.common.world.WorldSection";
    private static final String MAPPER = "me.cortex.voxy.common.world.other.Mapper";

    private static final int DIRTY_CAP = 50_000;
    private static final long ACQUIRE_SAMPLE_MASK = 63L;

    /** Receives live section data when the tile overlay is enabled. */
    public interface SectionConsumer {
        void accept(long key, long[] raw);
    }

    private static volatile SectionConsumer consumer;

    public static void setSectionConsumer(SectionConsumer next) {
        consumer = next;
    }

    private static boolean disabled;
    private static boolean missingLogged;
    private static Level installedLevel;
    private static Object engine;
    private static Object installedCallback;
    private static Object previousCallback;
    private static Class<?> sectionClass;
    private static Method sectionCopyData;
    private static Method sectionRelease;
    private static Field sectionLvlField;
    private static Field sectionKeyField;
    private static Method engineAcquireIfExists;
    private static Method engineAcquire;
    private static Field engineDirtyCallback;
    private static Method engineSetDirtyCallback;
    private static volatile boolean running;
    private static Thread worker;

    private static final AtomicLong events = new AtomicLong();
    private static final AtomicLong coalescedAdds = new AtomicLong();
    private static final AtomicLong dropped = new AtomicLong();
    private static final AtomicLong processed = new AtomicLong();
    private static final AtomicLong acquireNull = new AtomicLong();
    private static final AtomicLong acquireFails = new AtomicLong();
    private static final AtomicLong copyFails = new AtomicLong();
    private static final AtomicLong copySamples = new AtomicLong();
    private static final AtomicLong acquireNanos = new AtomicLong();
    private static final AtomicLong copyNanos = new AtomicLong();
    private static final AtomicIntegerArray levelEvents = new AtomicIntegerArray(8);
    private static final Map<Long, Dirty> dirty = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Long> dirtyQueue = new ConcurrentLinkedQueue<>();
    private static final Map<Long, Boolean> uniqueWindow = new ConcurrentHashMap<>();
    private static final Map<Long, Boolean> uniqueEver = new ConcurrentHashMap<>();
    private static final Map<Thread, AtomicLong> threadCounts = new ConcurrentHashMap<>();
    private static final Map<Integer, AtomicLong> flagCounts = new ConcurrentHashMap<>();
    private static volatile long lastEventMs;
    private static volatile String lastSample = "";
    private static long lastReportMs;
    private static long lastReportEvents;
    private static long lastReportAtMs;
    private static long lastAttemptMs;
    private static long lastCallbackCheckMs;
    private static Method callbackAccept;

    private static final class Dirty {
        final int lvl;
        final int flags;

        Dirty(int lvl, int flags) {
            this.lvl = lvl;
            this.flags = flags;
        }
    }

    private VoxyLiveProbe() {
    }

    /** Called every client tick. Cheap when the probe is disabled. */
    public static void tick(Minecraft mc) {
        if (!VoxyMapConfig.DEBUG_VOXY_EVENTS.get() && !VoxyMapConfig.LIVE_TILES.get()) {
            uninstall();
            return;
        }
        Level level = mc.level;
        if (level == null) {
            uninstall();
            return;
        }
        long now = System.currentTimeMillis();
        if (engine == null || installedLevel != level) {
            if (installedLevel != null && installedLevel != level) uninstall();
            if (now - lastAttemptMs > 1000L) {
                lastAttemptMs = now;
                tryInstall(mc, level);
            }
        } else if (now - lastReportMs > 10_000L) {
            lastReportMs = now;
            if (VoxyMapConfig.DEBUG_VOXY_EVENTS.get() || RefreshDebug.enabled()) report();
        }
        if (engine != null && now - lastCallbackCheckMs > 100L) {
            lastCallbackCheckMs = now;
            ensureCallback();
        }
    }

    /**
     * Voxy's renderer installs its own dirty callback (and clears it on close). If it replaced our
     * proxy, re-install ours with the current callback chained so both sides keep working.
     */
    private static void ensureCallback() {
        if (engine == null || engineDirtyCallback == null || engineSetDirtyCallback == null) return;
        try {
            Object current = engineDirtyCallback.get(engine);
            if (current == installedCallback) return;
            previousCallback = current;
            Object cb = newCallback(previousCallback);
            engineSetDirtyCallback.invoke(engine, cb);
            installedCallback = cb;
            System.out.println("[voxymap-live] re-installed chaining callback "
                    + (current == null ? "null" : current.getClass().getName()));
        } catch (Throwable t) {
            disabled = true;
            System.out.println("[voxymap-live] could not re-chain callback: " + t);
        }
    }

    private static Object newCallback(Object previous) throws Exception {
        Class<?> callbackClass = findClass(CALLBACK, VoxyLiveProbe.class.getClassLoader());
        if (callbackClass == null) throw new ClassNotFoundException(CALLBACK);
        callbackAccept = callbackClass.getMethod("accept", sectionClass, int.class, int.class);
        InvocationHandler handler = (proxy, method, args) -> {
            if ("accept".equals(method.getName()) && args != null && args.length >= 3) {
                // Keep Voxy's own renderer callback latency first; our probe must never delay it.
                if (previous != null) {
                    try {
                        callbackAccept.invoke(previous, args);
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    onEvent(args[0], (Integer) args[1], (Integer) args[2]);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
            return null;
        };
        return Proxy.newProxyInstance(callbackClass.getClassLoader(),
                new Class<?>[]{callbackClass}, handler);
    }

    private static void tryInstall(Minecraft mc, Level level) {
        if (disabled) return;
        try {
            ClassLoader loader = VoxyLiveProbe.class.getClassLoader();
            Class<?> identifier = findClass(IDENTIFIER, loader);
            Class<?> engineClass = findClass(ENGINE, loader);
            Class<?> callbackClass = findClass(CALLBACK, loader);
            sectionClass = findClass(SECTION, loader);
            if (identifier == null || engineClass == null || callbackClass == null || sectionClass == null) {
                if (!missingLogged) {
                    missingLogged = true;
                    System.out.println("[voxymap-live] Voxy classes not found; probe disabled");
                }
                disabled = true;
                return;
            }
            Method ofEngineNullable = identifier.getMethod("ofEngineNullable", Level.class);
            Object found = ofEngineNullable.invoke(null, level);
            if (found == null) {
                if (!missingLogged) {
                    missingLogged = true;
                    System.out.println("[voxymap-live] no live WorldEngine for the current level yet");
                }
                return;
            }
            sectionCopyData = sectionClass.getMethod("copyData");
            sectionRelease = sectionClass.getMethod("release");
            sectionLvlField = sectionClass.getField("lvl");
            sectionKeyField = sectionClass.getField("key");
            try {
                engineAcquireIfExists = engineClass.getMethod("acquireIfExists", long.class);
            } catch (Throwable ignored) {
                engineAcquireIfExists = null;
            }
            try {
                engineAcquire = engineClass.getMethod("acquire", long.class);
            } catch (Throwable ignored) {
                engineAcquire = null;
            }
            engineSetDirtyCallback = engineClass.getMethod("setDirtyCallback", callbackClass);
            try {
                engineDirtyCallback = engineClass.getDeclaredField("dirtyCallback");
                engineDirtyCallback.setAccessible(true);
            } catch (Throwable ignored) {
                engineDirtyCallback = null;
            }
            if (engineDirtyCallback == null) {
                // Never overwrite Voxy's own renderer callback when we cannot inspect/chain it.
                disabled = true;
                System.out.println("[voxymap-live] cannot inspect WorldEngine.dirtyCallback; probe disabled");
                return;
            }
            previousCallback = null;
            try {
                previousCallback = engineDirtyCallback.get(found);
            } catch (Throwable ignored) {
            }
            Object callback = newCallback(previousCallback);
            engineSetDirtyCallback.invoke(found, callback);
            engine = found;
            installedCallback = callback;
            installedLevel = level;
            resetStats();
            startWorker();
            lastAttemptMs = System.currentTimeMillis();
            lastCallbackCheckMs = lastAttemptMs;
            lastReportMs = lastAttemptMs;
            lastReportAtMs = lastAttemptMs;
            lastReportEvents = 0;
            System.out.println("[voxymap-live] installed coalescing probe on "
                    + engineClass.getSimpleName() + " previousCallback="
                    + (previousCallback == null ? "null" : previousCallback.getClass().getName()));
            if (RefreshDebug.enabled()) {
                RefreshDebug.chat("coalescing live probe installed", net.minecraft.ChatFormatting.DARK_AQUA);
            }
        } catch (Throwable t) {
            disabled = true;
            System.out.println("[voxymap-live] install failed: " + t);
            t.printStackTrace();
        }
    }

    private static void resetStats() {
        events.set(0);
        coalescedAdds.set(0);
        dropped.set(0);
        processed.set(0);
        acquireNull.set(0);
        acquireFails.set(0);
        copyFails.set(0);
        copySamples.set(0);
        acquireNanos.set(0);
        copyNanos.set(0);
        for (int i = 0; i < levelEvents.length(); i++) levelEvents.set(i, 0);
        dirty.clear();
        dirtyQueue.clear();
        uniqueWindow.clear();
        uniqueEver.clear();
        threadCounts.clear();
        flagCounts.clear();
        lastEventMs = 0;
        lastSample = "";
    }

    private static void startWorker() {
        if (worker != null) return;
        running = true;
        worker = new Thread(() -> {
            while (running) {
                try {
                    Long key = dirtyQueue.poll();
                    if (key == null) {
                        Thread.sleep(2L);
                        continue;
                    }
                    Dirty d = dirty.remove(key);
                    if (d == null) continue; // superseded by a newer event? no, another consumer
                    processKey(key, d);
                    if ((processed.get() & 63L) == 0L) Thread.sleep(1L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable t) {
                    acquireFails.incrementAndGet();
                }
            }
        }, "voxymap-live-coalesce");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private static void processKey(long key, Dirty d) {
        if (engine == null) return;
        Object section = null;
        long t0 = System.nanoTime();
        try {
            if (engineAcquireIfExists != null) {
                section = engineAcquireIfExists.invoke(engine, key);
            } else if (engineAcquire != null) {
                section = engineAcquire.invoke(engine, key);
            }
        } catch (Throwable t) {
            acquireFails.incrementAndGet();
        } finally {
            acquireNanos.addAndGet(System.nanoTime() - t0);
        }
        if (section == null) {
            acquireNull.incrementAndGet();
            return;
        }
        try {
            long t1 = System.nanoTime();
            long n = processed.incrementAndGet();
            SectionConsumer sink = consumer;
            if (sink != null && VoxyMapConfig.LIVE_TILES.get() && MapData.INSTANCE.hasLiveStore()) {
                // The live tile overlay needs the current voxels, so the consumer path always
                // copies; the geometric map work is coalesced by the dirty queue already.
                try {
                    long[] data = (long[]) sectionCopyData.invoke(section);
                    if (data != null && data.length >= 32768) {
                        sink.accept(key, data);
                        copySamples.incrementAndGet();
                    } else {
                        copyFails.incrementAndGet();
                    }
                } catch (Throwable t) {
                    copyFails.incrementAndGet();
                } finally {
                    copyNanos.addAndGet(System.nanoTime() - t1);
                }
                return;
            }
            // Copy only a sample: the real map would decode here, but the probe only needs to
            // measure acquire cost and confirm that the worker can fetch current data.
            if ((n & ACQUIRE_SAMPLE_MASK) == 0L) {
                try {
                    long[] data = (long[]) sectionCopyData.invoke(section);
                    if (data == null || data.length < 32768) {
                        copyFails.incrementAndGet();
                    } else {
                        int nonZero = 0;
                        long xor = 0;
                        for (long v : data) {
                            xor ^= v;
                            if (v != 0) nonZero++;
                        }
                        lastSample = "key=" + key + " lvl=" + d.lvl
                                + " flags=" + d.flags + " data=" + data.length
                                + " nonZero=" + nonZero + " xor=" + xor;
                        copySamples.incrementAndGet();
                    }
                } catch (Throwable t) {
                    copyFails.incrementAndGet();
                } finally {
                    copyNanos.addAndGet(System.nanoTime() - t1);
                }
            } else {
                copyNanos.addAndGet(System.nanoTime() - t1);
            }
        } finally {
            try {
                sectionRelease.invoke(section);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void onEvent(Object section, int updateFlags, int neighborMask) throws Exception {
        if (section == null) return;
        long now = System.currentTimeMillis();
        lastEventMs = now;
        events.incrementAndGet();
        int lvl = -1;
        long key = 0L;
        try {
            lvl = sectionLvlField.getInt(section);
            key = sectionKeyField.getLong(section);
        } catch (Throwable ignored) {
        }
        if (lvl >= 0 && lvl < levelEvents.length()) levelEvents.incrementAndGet(lvl);
        if (uniqueWindow.size() < 100_000) uniqueWindow.put(key, Boolean.TRUE);
        if (uniqueEver.size() < 200_000) uniqueEver.put(key, Boolean.TRUE);
        threadCounts.computeIfAbsent(Thread.currentThread(), t -> new AtomicLong()).incrementAndGet();
        flagCounts.computeIfAbsent(updateFlags, f -> new AtomicLong()).incrementAndGet();
        if (dirty.size() >= DIRTY_CAP) {
            dropped.incrementAndGet();
            return;
        }
        Dirty fresh = new Dirty(lvl, updateFlags);
        Dirty old = dirty.putIfAbsent(key, fresh);
        if (old == null) {
            coalescedAdds.incrementAndGet();
            dirtyQueue.add(key);
        }
        // no copyData here: the worker reads the current section later
    }

    private static void report() {
        long count = events.get();
        long now = System.currentTimeMillis();
        long elapsed = Math.max(1L, lastReportAtMs == 0 ? 10_000L : now - lastReportAtMs);
        long delta = Math.max(0L, count - lastReportEvents);
        long proc = processed.get();
        long acquires = proc + acquireNull.get() + acquireFails.get();
        long avgAcquireUs = acquires == 0 ? 0 : acquireNanos.get() / acquires / 1000L;
        long copies = copySamples.get() + copyFails.get();
        long avgCopyUs = copies == 0 ? 0 : copyNanos.get() / copies / 1000L;
        StringBuilder levels = new StringBuilder();
        for (int i = 0; i < levelEvents.length(); i++) {
            int c = levelEvents.get(i);
            if (c > 0) {
                if (levels.length() > 0) levels.append('/');
                levels.append(i).append(':').append(c);
            }
        }
        StringBuilder pendingLevels = new StringBuilder();
        int[] pl = new int[levelEvents.length()];
        for (Dirty d : dirty.values()) {
            if (d.lvl >= 0 && d.lvl < pl.length) pl[d.lvl]++;
        }
        for (int i = 0; i < pl.length; i++) {
            if (pl[i] > 0) {
                if (pendingLevels.length() > 0) pendingLevels.append('/');
                pendingLevels.append(i).append(':').append(pl[i]);
            }
        }
        StringBuilder threadList = new StringBuilder();
        threadCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .limit(6)
                .forEach(e -> {
                    if (threadList.length() > 0) threadList.append(", ");
                    threadList.append(e.getKey().getName()).append(':').append(e.getValue().get());
                });
        StringBuilder flagList = new StringBuilder();
        flagCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .limit(8)
                .forEach(e -> {
                    if (flagList.length() > 0) flagList.append('/');
                    flagList.append(e.getKey()).append(':').append(e.getValue().get());
                });
        String text = "events=" + count + " (+" + (delta * 1000L / elapsed) + "/s)"
                + " uniqueWindow=" + uniqueWindow.size() + " uniqueTotal=" + uniqueEver.size()
                + " levels=" + (levels.length() == 0 ? "-" : levels)
                + " pending=" + dirty.size() + (pendingLevels.length() == 0 ? "" : " pendingLevels=" + pendingLevels)
                + " dropped=" + dropped.get()
                + " processed=" + proc + " acquireNull=" + acquireNull.get() + " acquireFails=" + acquireFails.get()
                + " avgAcquire=" + avgAcquireUs + "us avgCopy=" + avgCopyUs + "us"
                + " copyFails=" + copyFails.get() + " copySamples=" + copySamples.get()
                + " threads=[" + threadList + "] flags=" + (flagList.length() == 0 ? "-" : flagList)
                + (lastSample.isEmpty() ? "" : " sample[" + lastSample + "]");
        System.out.println("[voxymap-live] " + text);
        if (RefreshDebug.enabled()) {
            RefreshDebug.chat("live: " + text, net.minecraft.ChatFormatting.DARK_GRAY);
        }
        uniqueWindow.clear();
        threadCounts.clear();
        flagCounts.clear();
        lastReportEvents = count;
        lastReportAtMs = now;
    }

    public static String statsLine() {
        if (!VoxyMapConfig.DEBUG_VOXY_EVENTS.get()) return "live: off";
        if (engine == null) return "live: not installed";
        long age = lastEventMs == 0 ? -1 : System.currentTimeMillis() - lastEventMs;
        return "live: events=" + events.get() + " pending=" + dirty.size()
                + " processed=" + processed.get()
                + " acquireNull=" + acquireNull.get() + " acquireFails=" + acquireFails.get()
                + " lastEvent=" + (age < 0 ? "never" : age + "ms");
    }

    private static void uninstall() {
        if (engine == null && worker == null) return;
        running = false;
        Thread w = worker;
        worker = null;
        if (w != null) {
            try {
                w.interrupt();
                w.join(200L);
            } catch (Throwable ignored) {
            }
        }
        try {
            if (engine != null && engineDirtyCallback != null && engineSetDirtyCallback != null) {
                Object current = engineDirtyCallback.get(engine);
                if (current == installedCallback) {
                    engineSetDirtyCallback.invoke(engine, previousCallback);
                }
            }
        } catch (Throwable ignored) {
        }
        System.out.println("[voxymap-live] uninstalled");
        engine = null;
        installedCallback = null;
        previousCallback = null;
        installedLevel = null;
        dirty.clear();
        dirtyQueue.clear();
    }

    private static Class<?> findClass(String name, ClassLoader preferred) {
        ClassLoader[] loaders = {
                preferred,
                Thread.currentThread().getContextClassLoader(),
                VoxyLiveProbe.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader cl : loaders) {
            if (cl == null) continue;
            try {
                return Class.forName(name, false, cl);
            } catch (ClassNotFoundException ignored) {
            }
        }
        return null;
    }
}
