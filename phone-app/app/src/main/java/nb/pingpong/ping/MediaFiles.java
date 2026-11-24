package nb.pingpong.ping;

import java.util.Locale;

/**
 * File classification and subtitle-to-video filename matching.
 * <p>
 * Sharing a season pack is common. A video may arrive with several subtitles named after it, with language
 * or format tags appended. Matching is deliberately conservative: a subtitle is only attached to a video when
 * the cleaned names agree, or when both name the same episode. Wrong matches are worse than missing ones.
 */
final class MediaFiles {
    static final String[] VIDEO_EXT = {"mp4", "m4v", "mkv", "webm", "avi", "mov", "wmv", "flv", "3gp", "ts",
            "m2ts", "mts", "mpg", "mpeg", "ogv", "vob", "divx", "asf", "rmvb"};
    static final String[] SUB_EXT = {"srt", "ass", "ssa", "vtt", "sub", "ttml", "dfxp"};

    // Trailing tokens in subtitle file names. Stripped repeatedly, e.g. "movie.en.forced" -> "movie".
    private static final String[] SUB_TAGS = {
            "english", "french", "spanish", "german", "italian", "portuguese", "russian", "japanese",
            "chinese", "korean", "brazilian", "hearing.impaired", "forced", "sdh", "cc", "default",
            "en", "eng", "fr", "fre", "fra", "es", "spa", "de", "ger", "deu", "it", "ita", "pt", "por",
            "ru", "rus", "ja", "jpn", "zh", "chi", "ko", "kor", "ar", "ara", "hi", "hin", "tr", "tur",
            "nl", "dut", "sv", "swe", "pl", "pol", "da", "dan", "no", "nor", "fi", "fin", "cs", "ces",
            "el", "ell", "he", "heb", "th", "tha", "vi", "vie", "id", "ind", "ms", "msa"
    };

    private MediaFiles() {
    }

    /** 1 = video, 2 = subtitle, 0 = other. The extension wins; content providers often report a vague MIME type. */
    static int kind(String name, String mime) {
        String ext = extension(name);
        for (String e : VIDEO_EXT) if (e.equals(ext)) return 1;
        for (String e : SUB_EXT) if (e.equals(ext)) return 2;
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.startsWith("video/")) return 1;
        if (m.equals("application/x-subrip") || m.equals("text/vtt") || m.equals("text/x-ssa")
                || m.equals("text/srt") || m.equals("application/x-subtitle")
                || m.equals("application/ttml+xml")) return 2;
        return 0;
    }

    static boolean isVideo(String name) {
        return kind(name, null) == 1;
    }

    static boolean isSubtitle(String name) {
        return kind(name, null) == 2;
    }

    static String extension(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        int i = n.lastIndexOf('.');
        return i >= 0 ? n.substring(i + 1) : "";
    }

    /** Lowercase name without its extension, with all separators folded to single dots. */
    private static String normalize(String name) {
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
        String out = sb.toString();
        while (out.startsWith(".")) out = out.substring(1);
        while (out.endsWith(".")) out = out.substring(0, out.length() - 1);
        return out;
    }

    /** Removes trailing language/format tags from a subtitle name. */
    private static String withoutTags(String name) {
        String s = normalize(name);
        boolean changed = true;
        while (changed && !s.isEmpty()) {
            changed = false;
            for (String tag : SUB_TAGS) {
                if (s.endsWith("." + tag)) {
                    s = s.substring(0, s.length() - tag.length() - 1);
                    changed = true;
                    break;
                }
            }
        }
        return s;
    }

    private static String episodeKey(String name) {
        String s = normalize(name);
        // S01E02 / s1e02
        for (int i = 0; i + 5 < s.length(); i++) {
            if (s.charAt(i) != 's') continue;
            int j = i + 1;
            while (j < s.length() && Character.isDigit(s.charAt(j))) j++;
            if (j == i + 1 || j >= s.length() || s.charAt(j) != 'e') continue;
            int k = j + 1;
            while (k < s.length() && Character.isDigit(s.charAt(k))) k++;
            if (k > j + 1) return s.substring(i, k);
        }
        // 1x02
        for (int i = 0; i + 3 < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) continue;
            int j = i;
            while (j < s.length() && Character.isDigit(s.charAt(j))) j++;
            if (j >= s.length() || s.charAt(j) != 'x') continue;
            int k = j + 1;
            while (k < s.length() && Character.isDigit(s.charAt(k))) k++;
            if (k > j + 1) return s.substring(i, k);
        }
        return "";
    }

    /** True when {@code subName} belongs to {@code videoName}. */
    static boolean matches(String videoName, String subName) {
        String v = normalize(videoName);
        String s = withoutTags(subName);
        if (v.isEmpty() || s.isEmpty()) return false;
        if (v.equals(s) || s.startsWith(v + ".") || v.startsWith(s + ".")) return true;
        String ep = episodeKey(videoName);
        return !ep.isEmpty() && ep.equals(episodeKey(subName));
    }
}
