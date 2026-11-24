package nb.pingpong.pong;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Asks Android why the previous run of the app ended (Android 11+), so native crashes, out-of-memory kills
 * and freezes can be reported too; Java exceptions are recorded separately by MainActivity. For a native crash
 * on Android 12+ the system also keeps the crash dump, from which the readable stack frames are pulled out.
 */
@android.annotation.TargetApi(31) // every call is behind an SDK_INT check
final class ExitReport {
    private ExitReport() {
    }

    /** A report for an abnormal exit not shown before, or null. */
    static String take(Context ctx, SharedPreferences prefs) {
        if (Build.VERSION.SDK_INT < 30) return null;
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return null;
            List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(ctx.getPackageName(), 0, 1);
            if (list == null || list.isEmpty()) return null;
            ApplicationExitInfo e = list.get(0);
            long seen = prefs.getLong("exit_seen", 0);
            prefs.edit().putLong("exit_seen", e.getTimestamp()).apply();
            if (e.getTimestamp() <= seen) return null;
            String reason = reasonName(e.getReason());
            if (reason == null) return null; // a normal exit
            StringBuilder sb = new StringBuilder(reason);
            if (e.getReason() == ApplicationExitInfo.REASON_SIGNALED
                    || e.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                sb.append(" ").append(signalName(e.getStatus()));
            }
            if (e.getDescription() != null && !e.getDescription().isEmpty()) sb.append(": ").append(e.getDescription());
            if (e.getPss() > 0) sb.append(String.format(Locale.ROOT, " (memory %d MB)", e.getPss() / 1024));
            String trace = prefs.getString("torrent_trace", null);
            if (trace != null) sb.append('\n').append(trace);
            if (e.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE
                    || e.getReason() == ApplicationExitInfo.REASON_ANR) {
                String frames = frames(e);
                if (!frames.isEmpty()) sb.append('\n').append(frames);
            }
            String out = sb.toString();
            return out.length() > 1800 ? out.substring(0, 1800) : out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String signalName(int sig) {
        switch (sig) {
            case 6:
                return "SIGABRT (6, aborted by native code)";
            case 7:
                return "SIGBUS (7, bad memory-mapped file access)";
            case 9:
                return "SIGKILL (9, killed by the system)";
            case 11:
                return "SIGSEGV (11, native crash)";
            default:
                return "signal " + sig;
        }
    }

    private static String reasonName(int r) {
        switch (r) {
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return "Native crash";
            case ApplicationExitInfo.REASON_ANR:
                return "Stopped responding (ANR)";
            case ApplicationExitInfo.REASON_LOW_MEMORY:
                return "Closed by Android: low memory";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return "Closed by Android: too much resource use";
            case ApplicationExitInfo.REASON_SIGNALED:
                return "Killed by a signal";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return "Failed to start";
            default:
                return null; // normal exit, Java crash (reported separately), user stop, update...
        }
    }

    /** Readable lines from the crash dump: the library and function names of the top stack frames. */
    private static String frames(ApplicationExitInfo e) {
        try (InputStream in = e.getTraceInputStream()) {
            if (in == null) return "";
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0 && buf.size() < 512 * 1024) buf.write(b, 0, n);
            byte[] data = buf.toByteArray();
            // The dump is binary (protobuf for native crashes); keep printable runs that look like frames.
            List<String> picked = new ArrayList<>();
            StringBuilder run = new StringBuilder();
            for (int i = 0; i <= data.length && picked.size() < 14; i++) {
                int c = i < data.length ? data[i] & 0xff : 0;
                if (c >= 32 && c < 127) {
                    run.append((char) c);
                    continue;
                }
                String str = run.toString();
                run.setLength(0);
                if (str.length() < 6) continue;
                boolean useful = str.contains("::") || str.contains(".so") || str.startsWith("signal")
                        || str.contains("SIG") || str.contains("Abort") || str.contains("at ");
                if (useful && !picked.contains(str)) picked.add(str.length() > 140 ? str.substring(0, 140) : str);
            }
            StringBuilder sb = new StringBuilder();
            for (String p : picked) sb.append(p).append('\n');
            return sb.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }
}
