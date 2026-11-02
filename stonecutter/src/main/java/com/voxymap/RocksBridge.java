package com.voxymap;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Reflective access to the rocksdbjni classes that voxy already loads through its
 * own jar-in-jar (module {@code roxy.jij.rocksdbjni}). Bundling a second rocksdbjni
 * jar would trip a JPMS split-package failure, so we compile against the jar and
 * reach the loaded classes at runtime instead.
 */
public final class RocksBridge {
    private static boolean initialized;
    private static String error = "";
    private static Class<?> cRocksDB, cDBOptions, cCfd, cHandle, cIter;
    private static Constructor<?> ctorOptions, ctorCfd;
    private static Method mLoadLibrary, mOpenReadOnly, mNewIterator, mSeekFirst, mIsValid, mKey, mValue, mNext;
    /** optional: RocksDB.getLatestSequenceNumber(), used as a cheap "did anything change" test */
    private static Method mLatestSequence;

    private RocksBridge() {
    }

    public static synchronized boolean available() {
        init();
        return error.isEmpty();
    }

    public static String error() {
        return error;
    }

    private static synchronized void init() {
        if (initialized) return;
        initialized = true;
        try {
            cRocksDB = findClass("org.rocksdb.RocksDB");
            cDBOptions = findClass("org.rocksdb.DBOptions");
            cCfd = findClass("org.rocksdb.ColumnFamilyDescriptor");
            cHandle = findClass("org.rocksdb.ColumnFamilyHandle");
            cIter = findClass("org.rocksdb.RocksIterator");
            ctorOptions = cDBOptions.getConstructor();
            ctorCfd = cCfd.getConstructor(byte[].class);
            mLoadLibrary = cRocksDB.getMethod("loadLibrary");
            // optional API (present in rocksdbjni 10.2.1, verified with javap); if a future Voxy
            // bundles a different version without it, only the fast-path check is disabled
            try {
                mLatestSequence = cRocksDB.getMethod("getLatestSequenceNumber");
            } catch (Throwable ignored) {
                mLatestSequence = null;
            }
            mOpenReadOnly = cRocksDB.getMethod("openReadOnly", cDBOptions, String.class, List.class, List.class);
            mNewIterator = cRocksDB.getMethod("newIterator", cHandle);
            mSeekFirst = cIter.getMethod("seekToFirst");
            mIsValid = cIter.getMethod("isValid");
            mKey = cIter.getMethod("key");
            mValue = cIter.getMethod("value");
            mNext = cIter.getMethod("next");
            mLoadLibrary.invoke(null);
        } catch (Throwable t) {
            error = t.toString();
            t.printStackTrace();
        }
    }

    private static Class<?> findClass(String name) throws ClassNotFoundException {
        List<ModuleLayer> layers = new ArrayList<>();
        ModuleLayer own = RocksBridge.class.getModule().getLayer();
        if (own != null) {
            layers.add(own);
            layers.addAll(own.parents());
        }
        layers.add(ModuleLayer.boot());
        for (ModuleLayer layer : layers) {
            for (Module m : layer.modules()) {
                ClassLoader cl = m.getClassLoader();
                if (cl == null) continue;
                try {
                    return Class.forName(name, true, cl);
                } catch (ClassNotFoundException ignored) {
                }
            }
        }
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException ignored) {
        }
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx != null) return Class.forName(name, true, ctx);
        throw new ClassNotFoundException(name);
    }

    public static Object openReadOnly(String path, List<Object> handlesOut) throws Exception {
        init();
        Object opts = ctorOptions.newInstance();
        List<Object> cfds = new ArrayList<>();
        for (String name : new String[]{"default", "world_sections", "id_mappings"}) {
            cfds.add(ctorCfd.newInstance((Object) name.getBytes()));
        }
        List<Object> handles = new ArrayList<>();
        Object db = mOpenReadOnly.invoke(null, opts, path, cfds, handles);
        handlesOut.addAll(handles);
        return db;
    }

    public static Object newIterator(Object db, Object handle) throws Exception {
        return mNewIterator.invoke(db, handle);
    }

    public static void seekToFirst(Object it) throws Exception {
        mSeekFirst.invoke(it);
    }

    public static boolean isValid(Object it) throws Exception {
        return (Boolean) mIsValid.invoke(it);
    }

    public static byte[] key(Object it) throws Exception {
        return (byte[]) mKey.invoke(it);
    }

    public static byte[] value(Object it) throws Exception {
        return (byte[]) mValue.invoke(it);
    }

    public static void next(Object it) throws Exception {
        mNext.invoke(it);
    }

    /**
     * RocksDB latest sequence number, or -1 when the optional API is unavailable. Any write to any
     * column family advances it, so an unchanged value means the world_sections scan can be skipped.
     */
    public static long latestSequence(Object db) {
        init();
        if (db == null || mLatestSequence == null) return -1L;
        try {
            return (Long) mLatestSequence.invoke(db);
        } catch (Throwable t) {
            return -1L;
        }
    }

    public static void close(Object obj) {
        if (obj == null) return;
        try {
            obj.getClass().getMethod("close").invoke(obj);
        } catch (Exception ignored) {
        }
    }
}
