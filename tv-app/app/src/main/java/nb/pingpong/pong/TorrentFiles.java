package nb.pingpong.pong;

import java.util.Locale;

/** Filename helpers for choosing the video and its sidecar subtitles inside a torrent. */
final class TorrentFiles {
    private static final String[] VIDEO_EXT = {"mp4", "m4v", "mkv", "webm", "avi", "mov", "wmv", "flv", "3gp",
            "ts", "m2ts", "mts", "mpg", "mpeg", "ogv", "vob", "divx", "asf", "rmvb"};
    private static final String[] SUB_EXT = {"srt", "ass", "ssa", "vtt", "sub", "ttml", "dfxp"};
    private static final String[] TAGS = {
            "english", "french", "spanish", "german", "italian", "portuguese", "russian", "japanese",
            "chinese", "korean", "hearing.impaired", "forced", "sdh", "cc", "default",
            "en", "eng", "fr", "fre", "fra", "es", "spa", "de", "ger", "deu", "it", "ita", "pt", "por",
            "ru", "rus", "ja", "jpn", "zh", "chi", "ko", "kor", "ar", "ara", "hi", "hin", "tr", "tur",
            "nl", "dut", "sv", "swe", "pl", "pol"
    };

    private TorrentFiles() {
    }

    static boolean isVideo(String name) {
        return kind(name) == 1;
    }

    static boolean isSubtitle(String name) {
        return kind(name) == 2;
    }

    private static int kind(String name) {
        String ext = ext(name);
        for (String e : VIDEO_EXT) if (e.equals(ext)) return 1;
        for (String e : SUB_EXT) if (e.equals(ext)) return 2;
        return 0;
    }

    private static String ext(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        int i = n.lastIndexOf('.');
        return i >= 0 ? n.substring(i + 1) : "";
    }

    /** Lowercase base name with separators folded to dots. */
    private static String norm(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        if (dot >= 0) n = n.substring(0, dot);
        n = n.replace('_', '.').replace('-', '.').replace(' ', '.');
        StringBuilder sb = new StringBuilder();
        boolean lastDot = false;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c == '.') {
                if (!lastDot) sb.append(c);
                lastDot = true;
            } else {
                sb.append(c);
                lastDot = false;
            }
        }
        String s = sb.toString();
        while (s.startsWith(".")) s = s.substring(1);
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String withoutTags(String name) {
        String s = norm(name);
        boolean changed = true;
        while (changed && !s.isEmpty()) {
            changed = false;
            for (String tag : TAGS) {
                if (s.endsWith("." + tag)) {
                    s = s.substring(0, s.length() - tag.length() - 1);
                    changed = true;
                    break;
                }
            }
        }
        return s;
    }

    private static String episode(String s) {
        for (int i = 0; i + 5 < s.length(); i++) {
            if (s.charAt(i) != 's') continue;
            int j = i + 1;
            while (j < s.length() && Character.isDigit(s.charAt(j))) j++;
            if (j == i + 1 || j >= s.length() || s.charAt(j) != 'e') continue;
            int k = j + 1;
            while (k < s.length() && Character.isDigit(s.charAt(k))) k++;
            if (k > j + 1) return s.substring(i, k);
        }
        return "";
    }

    /**
     * A parameter of a magnet link, URL-decoded; null if absent. Android's Uri can't do this: "magnet:?..." is
     * an opaque URI, and getQueryParameter() throws for those.
     */
    static String magnetParam(String magnet, String key) {
        if (magnet == null) return null;
        int q = magnet.indexOf('?');
        if (q < 0) return null;
        for (String kv : magnet.substring(q + 1).split("&")) {
            int eq = kv.indexOf('=');
            String k = eq < 0 ? kv : kv.substring(0, eq);
            if (!k.equalsIgnoreCase(key)) continue;
            String v = eq < 0 ? "" : kv.substring(eq + 1);
            try {
                return java.net.URLDecoder.decode(v, "UTF-8"); // "+" is a space, as in form encoding
            } catch (Exception e) {
                return v;
            }
        }
        return null;
    }

    /** The BitTorrent v1 info hash of a magnet link as 40 lowercase hex digits (hex or base32 form); else null. */
    static String infoHashHex(String magnet) {
        if (magnet == null) return null;
        String m = magnet.toLowerCase(Locale.ROOT);
        int i = m.indexOf("urn:btih:");
        if (i < 0) return null;
        int s = i + 9, e = s;
        while (e < m.length() && m.charAt(e) != '&') e++;
        String v = m.substring(s, e).trim();
        if (v.length() == 40 && v.matches("[0-9a-f]{40}")) return v;
        if (v.length() == 32) {
            String alphabet = "abcdefghijklmnopqrstuvwxyz234567";
            byte[] out = new byte[20];
            long buffer = 0;
            int bits = 0, n = 0;
            for (int k = 0; k < 32; k++) {
                int val = alphabet.indexOf(v.charAt(k));
                if (val < 0) return null;
                buffer = (buffer << 5) | val;
                bits += 5;
                if (bits >= 8) {
                    bits -= 8;
                    out[n++] = (byte) (buffer >> bits);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : out) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return sb.toString();
        }
        return null;
    }

    static boolean matches(String video, String sub) {
        String v = norm(video);
        String s = withoutTags(sub);
        if (v.isEmpty() || s.isEmpty()) return false;
        if (v.equals(s) || s.startsWith(v + ".") || v.startsWith(s + ".")) return true;
        String ep = episode(v);
        return !ep.isEmpty() && ep.equals(episode(norm(sub)));
    }
}
