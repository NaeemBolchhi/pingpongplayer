package nb.pingpong.pong;

import android.system.Os;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Arrays;

/**
 * Frees the disk space of part of a file while keeping its size (fallocate "punch hole"), through a small
 * native library. The torrent window mode uses it to drop the already watched part of a video.
 * Everything fails soft: without the library, or on a file system that can't do it, {@link #works} is false.
 */
final class HolePunch {
    private static boolean tried;
    private static boolean loaded;
    private static String probedDir;
    private static boolean probeResult;

    private HolePunch() {
    }

    private static native int nativePunch(String path, long offset, long length);

    private static synchronized boolean load() {
        if (!tried) {
            tried = true;
            try {
                System.loadLibrary("pongpunch");
                loaded = true;
            } catch (Throwable t) {
                loaded = false;
            }
        }
        return loaded;
    }

    /** 0 on success, a negative errno otherwise (-1 when the native library isn't available). */
    static int punch(String path, long offset, long length) {
        if (length <= 0) return 0;
        if (!load()) return -1;
        try {
            return nativePunch(path, offset, length);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Whether space can really be freed inside files of this directory. Writes a small test file and checks
     * that its allocated size drops after punching (so a file system that quietly ignores it is caught).
     */
    static synchronized boolean works(File dir) {
        String key = dir.getAbsolutePath();
        if (key.equals(probedDir)) return probeResult;
        boolean ok = false;
        File test = new File(dir, ".punch-test");
        try {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            byte[] block = new byte[64 * 1024];
            Arrays.fill(block, (byte) 0x5A);
            RandomAccessFile raf = new RandomAccessFile(test, "rw");
            try {
                for (int i = 0; i < 4; i++) raf.write(block); // 256 KB of real data
                raf.getFD().sync();
            } finally {
                raf.close();
            }
            long before = Os.stat(test.getAbsolutePath()).st_blocks;
            int rc = punch(test.getAbsolutePath(), 64 * 1024, 128 * 1024);
            long after = Os.stat(test.getAbsolutePath()).st_blocks;
            // 128 KB = 256 blocks of 512 bytes; accept at least half of that being freed
            ok = rc == 0 && before - after >= 128 && test.length() == 256 * 1024;
        } catch (Throwable t) {
            ok = false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            test.delete();
        }
        probedDir = key;
        probeResult = ok;
        return ok;
    }
}
