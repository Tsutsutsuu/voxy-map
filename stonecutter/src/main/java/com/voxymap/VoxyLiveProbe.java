package com.voxymap;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Proof-of-concept for the event-driven map: observe Voxy's live section dirty callbacks instead
 * of polling RocksDB.
 *
 * <p>Entirely reflective: the mod does not compile against Voxy, and if the classes/methods are
 * missing this probe disables itself (the normal refresh pipeline keeps working). It only logs
 * statistics and copies a small sample of section data; it never modifies the map.</p>
 */
public final class VoxyLiveProbe {
    private static final String IDENTIFIER = "me.cortex.voxy.commonImpl.WorldIdentifier";
    private static final String ENGINE = "me.cortex.voxy.common.world.WorldEngine";
    private static final String CALLBACK = ENGINE + "$ISectionChangeCallback";
    private static final String SECTION = "me.cortex.voxy.common.world.WorldSection";
    private static final String MAPPER = "me.cortex.voxy.common.world.other.Mapper";

    private static boolean disabled;
    private static boolean missingLogged;
    private static Level installedLevel;
    private static Object engine;
    private static Object installedCallback;
    private static Object previousCallback;
    private static Class<?> sectionClass;
    private static Method sectionCopyData;
    private static Method sectionIsAir;
    private static Field sectionLvl, sectionX, sectionY, sectionZ, sectionKey;
    private static Field engineDirtyCallback;
    private static Method engineSetDirtyCallback;

    private static final AtomicLong events = new AtomicLong();
    private static final AtomicLong samples = new AtomicLong();
    private static final AtomicLong copyFails = new AtomicLong();
    private static final AtomicIntegerArray levelCounts = new AtomicIntegerArray(8);
    private static final Map<Long, Boolean> uniqueKeys = new ConcurrentHashMap<>();
    private static final Set<String> threads = ConcurrentHashMap.newKeySet();
    private static volatile long lastEventMs;
    private static volatile String lastSample = "";
    private static long lastReportMs;
    private static long lastAttemptMs;

    private VoxyLiveProbe() {
    }

    /** Called every client tick. Cheap when the probe is disabled. */
    public static void tick(Minecraft mc) {
        if (!VoxyMapConfig.DEBUG_VOXY_EVENTS.get()) {
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
            report();
        }
    }

    private static void tryInstall(Minecraft mc, Level level) {
        if (disabled) return;
        try {
            ClassLoader loader = VoxyLiveProbe.class.getClassLoader();
            Class<?> identifier = findClass(IDENTIFIER, loader);
            Class<?> engineClass = findClass(ENGINE, loader);
            Class<?> callbackClass = findClass(CALLBACK, loader);
            sectionClass = findClass(SECTION, loader);
            Class<?> mapperClass = findClass(MAPPER, loader);
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
            sectionLvl = sectionClass.getField("lvl");
            sectionX = sectionClass.getField("x");
            sectionY = sectionClass.getField("y");
            sectionZ = sectionClass.getField("z");
            sectionKey = sectionClass.getField("key");
            if (mapperClass != null) {
                try {
                    sectionIsAir = mapperClass.getMethod("isAir", long.class);
                } catch (Throwable ignored) {
                    sectionIsAir = null;
                }
            }
            engineSetDirtyCallback = engineClass.getMethod("setDirtyCallback", callbackClass);
            try {
                engineDirtyCallback = engineClass.getDeclaredField("dirtyCallback");
                engineDirtyCallback.setAccessible(true);
            } catch (Throwable ignored) {
                engineDirtyCallback = null;
            }
            previousCallback = null;
            if (engineDirtyCallback != null) {
                try {
                    previousCallback = engineDirtyCallback.get(found);
                } catch (Throwable ignored) {
                }
            }
            final Object previous = previousCallback;
            InvocationHandler handler = (proxy, method, args) -> {
                if ("accept".equals(method.getName()) && args != null && args.length >= 3) {
                    try {
                        onEvent(args[0], (Integer) args[1], (Integer) args[2]);
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                    if (previous != null) {
                        try {
                            method.invoke(previous, args);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                return null;
            };
            Object callback = Proxy.newProxyInstance(callbackClass.getClassLoader(),
                    new Class<?>[]{callbackClass}, handler);
            engineSetDirtyCallback.invoke(found, callback);
            engine = found;
            installedCallback = callback;
            installedLevel = level;
            lastAttemptMs = System.currentTimeMillis();
            lastEventMs = 0;
            events.set(0);
            samples.set(0);
            copyFails.set(0);
            uniqueKeys.clear();
            threads.clear();
            for (int i = 0; i < levelCounts.length(); i++) levelCounts.set(i, 0);
            lastReportMs = System.currentTimeMillis();
            System.out.println("[voxymap-live] installed on " + engineClass.getSimpleName()
                    + " previousCallback=" + (previous == null ? "null" : previous.getClass().getName()));
            if (RefreshDebug.enabled()) {
                RefreshDebug.chat("live probe installed on Voxy WorldEngine", net.minecraft.ChatFormatting.DARK_AQUA);
            }
        } catch (Throwable t) {
            disabled = true;
            System.out.println("[voxymap-live] install failed: " + t);
            t.printStackTrace();
        }
    }

    private static void onEvent(Object section, int updateFlags, int neighborMask) throws Exception {
        if (section == null) return;
        long now = System.currentTimeMillis();
        lastEventMs = now;
        events.incrementAndGet();
        threads.add(Thread.currentThread().getName());
        int lvl = sectionLvl == null ? -1 : sectionLvl.getInt(section);
        if (lvl >= 0 && lvl < levelCounts.length()) levelCounts.incrementAndGet(lvl);
        long key = sectionKey == null ? 0 : sectionKey.getLong(section);
        if (uniqueKeys.size() < 100_000) uniqueKeys.put(key, Boolean.TRUE);
        long count = events.get();
        if ((count & 511L) == 0L && sectionCopyData != null) {
            samples.incrementAndGet();
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
                    lastSample = "key=" + key + " lvl=" + lvl
                            + " xyz=" + sectionX.getInt(section) + "," + sectionY.getInt(section) + "," + sectionZ.getInt(section)
                            + " data=" + data.length + " nonZero=" + nonZero + " xor=" + xor;
                }
            } catch (Throwable t) {
                copyFails.incrementAndGet();
            }
        }
    }

    private static void report() {
        long count = events.get();
        StringBuilder levels = new StringBuilder();
        for (int i = 0; i < levelCounts.length(); i++) {
            int c = levelCounts.get(i);
            if (c > 0) {
                if (levels.length() > 0) levels.append('/');
                levels.append(i).append(':').append(c);
            }
        }
        String text = "events=" + count + " unique=" + uniqueKeys.size()
                + " levels=" + (levels.length() == 0 ? "-" : levels)
                + " threads=" + threads.size()
                + " copySamples=" + samples.get() + " copyFails=" + copyFails.get()
                + (lastSample.isEmpty() ? "" : " sample[" + lastSample + "]");
        System.out.println("[voxymap-live] " + text);
        if (RefreshDebug.enabled()) {
            RefreshDebug.chat("live: " + text, net.minecraft.ChatFormatting.DARK_GRAY);
        }
        uniqueKeys.clear();
        threads.clear();
    }

    public static String statsLine() {
        if (!VoxyMapConfig.DEBUG_VOXY_EVENTS.get()) return "live: off";
        if (engine == null) return "live: not installed";
        long age = lastEventMs == 0 ? -1 : System.currentTimeMillis() - lastEventMs;
        return "live: events=" + events.get() + " unique=" + uniqueKeys.size()
                + " lastEvent=" + (age < 0 ? "never" : age + "ms")
                + " copyFails=" + copyFails.get();
    }

    private static void uninstall() {
        if (engine == null) return;
        try {
            if (engineDirtyCallback != null && engineSetDirtyCallback != null) {
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
