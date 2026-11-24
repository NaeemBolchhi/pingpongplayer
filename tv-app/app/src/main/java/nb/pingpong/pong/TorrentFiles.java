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

    static boolean matches(String video, String sub) {
        String v = norm(video);
        String s = withoutTags(sub);
        if (v.isEmpty() || s.isEmpty()) return false;
        if (v.equals(s) || s.startsWith(v + ".") || v.startsWith(s + ".")) return true;
        String ep = episode(v);
        return !ep.isEmpty() && ep.equals(episode(norm(sub)));
    }
}
