package nb.pingpong.pong;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure helpers for the update checker (no Android classes, so they can be unit-tested on a plain JVM). */
final class UpdateLogic {
    private UpdateLogic() {
    }

    /** True if release tag {@code latest} (like "v1.2.0") is newer than {@code current} (like "1.0.0"). */
    static boolean isNewer(String current, String latest) {
        int[] a = parse(current), b = parse(latest);
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (y != x) return y > x;
        }
        return false;
    }

    /** "v1.2.3-beta" -> [1,2,3]. Anything that isn't a number ends a part; no digits at all -> [0]. */
    static int[] parse(String v) {
        if (v == null) return new int[]{0};
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int dash = s.indexOf('-');
        if (dash >= 0) s = s.substring(0, dash);
        int plus = s.indexOf('+');
        if (plus >= 0) s = s.substring(0, plus);
        List<Integer> parts = new ArrayList<>();
        for (String p : s.split("\\.")) {
            int i = 0;
            while (i < p.length() && Character.isDigit(p.charAt(i))) i++;
            if (i == 0) break;
            try {
                parts.add(Integer.parseInt(p.substring(0, i)));
            } catch (NumberFormatException e) {
                break;
            }
        }
        if (parts.isEmpty()) return new int[]{0};
        int[] out = new int[parts.size()];
        for (int i = 0; i < out.length; i++) out[i] = parts.get(i);
        return out;
    }

    /**
     * Picks the file for this device from a list of file names: the first ABI (most preferred first) whose
     * "-&lt;abi&gt;.ext" file exists, else the "-universal.ext" file, else the only candidate. Candidates must contain
     * {@code prefix} (like "Pong-TV") and end with {@code ext} (".apk" or ".zip"). Returns the name or null.
     */
    static String pick(List<String> names, String prefix, List<String> abis, String ext) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> cands = new ArrayList<>();
        for (String n : names) {
            String l = n.toLowerCase(Locale.ROOT);
            if (l.endsWith(ext) && l.contains(p)) cands.add(n);
        }
        for (String abi : abis) {
            String suffix = ("-" + abi + ext).toLowerCase(Locale.ROOT);
            for (String n : cands) if (n.toLowerCase(Locale.ROOT).endsWith(suffix)) return n;
        }
        String uni = "-universal" + ext;
        for (String n : cands) if (n.toLowerCase(Locale.ROOT).endsWith(uni)) return n;
        return cands.size() == 1 ? cands.get(0) : null;
    }

    static String humanSize(long bytes) {
        if (bytes <= 0) return "";
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }
}
