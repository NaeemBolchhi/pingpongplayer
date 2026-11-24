package nb.pingpong.pong;

import org.libtorrent4j.Sha1Hash;
import org.libtorrent4j.TorrentHandle;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Builds libtorrent4j info hashes without depending on one exact API shape: the way to make a Sha1Hash from
 * hex (or read it from a handle) differs between libtorrent4j / jlibtorrent versions, so each known form is
 * tried by reflection. Every method returns null instead of throwing.
 */
final class HashCompat {
    private HashCompat() {
    }

    /** From 40 hex digits. */
    static Sha1Hash fromHex(String hex) {
        if (hex == null || hex.length() != 40) return null;
        // Sha1Hash.parseHex(String)
        try {
            Method m = Sha1Hash.class.getMethod("parseHex", String.class);
            Object r = m.invoke(null, hex);
            if (r instanceof Sha1Hash) return (Sha1Hash) r;
        } catch (Throwable ignored) {
        }
        // new Sha1Hash(String)
        try {
            return Sha1Hash.class.getConstructor(String.class).newInstance(hex);
        } catch (Throwable ignored) {
        }
        // new Sha1Hash(byte[])
        byte[] bytes = new byte[20];
        for (int i = 0; i < 20; i++) bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        try {
            return Sha1Hash.class.getConstructor(byte[].class).newInstance((Object) bytes);
        } catch (Throwable ignored) {
        }
        // the SWIG type: new Sha1Hash(sha1_hash), filled by sha1_hash.from_hex or new sha1_hash(byte_vector)
        try {
            Class<?> raw = Class.forName("org.libtorrent4j.swig.sha1_hash");
            Constructor<?> wrap = Sha1Hash.class.getConstructor(raw);
            try {
                Object h = raw.getConstructor().newInstance();
                Method from = raw.getMethod("from_hex", String.class, raw);
                if (Boolean.TRUE.equals(from.invoke(null, hex, h))) return (Sha1Hash) wrap.newInstance(h);
            } catch (Throwable ignored) {
            }
            try {
                Class<?> bvClass = Class.forName("org.libtorrent4j.swig.byte_vector");
                Object bv = bvClass.getConstructor().newInstance();
                Method add = null;
                for (String name : new String[]{"push_back", "add"}) {
                    for (Class<?> t : new Class<?>[]{byte.class, Byte.class}) {
                        try {
                            add = bvClass.getMethod(name, t);
                            break;
                        } catch (NoSuchMethodException ignored) {
                        }
                    }
                    if (add != null) break;
                }
                if (add != null) {
                    for (byte b : bytes) add.invoke(bv, b);
                    Object h = raw.getConstructor(bvClass).newInstance(bv);
                    return (Sha1Hash) wrap.newInstance(h);
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Reads the v1 info hash of a torrent. Call it only while the handle is valid (e.g. inside the alert
     * callback); the returned hash is a copy and stays usable afterwards.
     */
    static Sha1Hash of(TorrentHandle h) {
        if (h == null) return null;
        Object r = call(h, "infoHash");
        if (r instanceof Sha1Hash) return (Sha1Hash) r;
        Object hashes = call(h, "getInfoHashes");
        if (hashes == null) hashes = call(h, "infoHashes");
        if (hashes != null) {
            for (String name : new String[]{"getBest", "best", "getV1", "v1"}) {
                Object b = call(hashes, name);
                if (b instanceof Sha1Hash) return (Sha1Hash) b;
            }
        }
        return null;
    }

    private static Object call(Object target, String name) {
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
