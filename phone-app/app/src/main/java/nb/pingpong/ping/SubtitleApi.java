package nb.pingpong.ping;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Subtitle search + download through the SubSource REST API (https://subsource.net/api-docs).
 * <p>
 * API keys are not entered by the user: they are baked into the app at build time (see {@link KeyVault}).
 * Each app session picks a random key; a key that is rejected (401/403) or rate limited (429) is skipped
 * and another random one is used instead.
 */
public class SubtitleApi {
    public static class Result {
        public long id;
        public String title;
        public String release;
        public String language;
        public int season = -1;   // from the query ("S02E05"), -1 if none
        public int episode = -1;

        @Override
        public String toString() {
            String t = (title == null || title.isEmpty()) ? "(untitled)" : title;
            String r = (release == null || release.isEmpty()) ? "" : "\n" + release;
            return "[" + (language == null ? "?" : language) + "] " + t + r;
        }
    }

    /** A downloaded subtitle file, already taken out of SubSource's archive. */
    public static class Fetched {
        public byte[] data;
        public String ext; // ".srt", ".ass", ...
    }

    private static final String BASE = "https://api.subsource.net/api/v1";
    private static final String USER_AGENT = "PingPong v1.0";
    private static final int MAX_JSON = 2 * 1024 * 1024;
    private static final int MAX_DOWNLOAD = 8 * 1024 * 1024;
    private static final int MOVIES_TO_QUERY = 3;
    private static final int SUBS_PER_MOVIE = 15;
    private static final int MAX_RESULTS = 30;
    private static final long MAX_AUTO_WAIT_MS = 15_000L;   // wait this long for a key to free up before giving up

    // ------------------------------------------------------------ key handling

    /** Thrown when every embedded key was rejected (or none were built into this APK). */
    public static class NoKeyException extends IOException {
        NoKeyException(String m) {
            super(m);
        }
    }

    private static final Object KEY_LOCK = new Object();
    private static List<String> pool;                       // shuffled once per app session
    private static final Map<String, Long> bannedUntil = new HashMap<>();

    public static boolean hasKeys() {
        synchronized (KEY_LOCK) {
            return !loadPool().isEmpty();
        }
    }

    private static List<String> loadPool() {
        if (pool == null) {
            List<String> keys = KeyVault.keys();
            java.util.Collections.shuffle(keys, new java.security.SecureRandom());
            pool = keys;
        }
        return pool;
    }

    /** First key in this session's random order that is not currently banned; null if none is left. */
    private static String currentKey() {
        synchronized (KEY_LOCK) {
            long now = System.currentTimeMillis();
            for (String k : loadPool()) {
                Long until = bannedUntil.get(k);
                if (until == null || until <= now) return k;
            }
            return null;
        }
    }

    private static void ban(String key, long ms) {
        synchronized (KEY_LOCK) {
            bannedUntil.put(key, ms == Long.MAX_VALUE ? Long.MAX_VALUE : System.currentTimeMillis() + ms);
        }
    }

    /** Milliseconds until the first temporarily banned key is usable again; -1 if every key is banned for good. */
    private static long soonestFreeMs() {
        synchronized (KEY_LOCK) {
            long now = System.currentTimeMillis(), best = -1;
            for (String k : loadPool()) {
                Long until = bannedUntil.get(k);
                if (until == null) return 0;
                if (until == Long.MAX_VALUE) continue;
                long left = Math.max(0, until - now);
                if (best < 0 || left < best) best = left;
            }
            return best;
        }
    }

    /**
     * SubSource's 429 body looks like {"error":"Too many requests","limit":"minute","retryAfter":26}.
     * Returns how long this key should be left alone, in ms.
     */
    private static long cooldownFor(JsonObject body) {
        long seconds = body != null && body.has("retryAfter") && body.get("retryAfter").isJsonPrimitive()
                ? num(body, "retryAfter") : 0;
        if (seconds <= 0) {
            String limit = body == null ? null : firstString(body, "limit");
            seconds = "day".equals(limit) ? 86_400 : "hour".equals(limit) ? 3_600 : 60;
        }
        return (seconds + 1) * 1000L;
    }

    // -------------------------------------------------------------- search

    /** "Breaking Bad S02E05" -> title "Breaking Bad", season 2, episode 5. Also "2x05" and "season 2". */
    private static final Pattern[] EPISODE_TOKENS = {
            Pattern.compile("(?i)(?<![a-z0-9])s(\\d{1,2})\\s*e(\\d{1,3})(?![0-9])"),
            Pattern.compile("(?i)(?<![0-9])(\\d{1,2})x(\\d{1,3})(?![0-9])"),
    };
    private static final Pattern[] SEASON_TOKENS = {
            Pattern.compile("(?i)(?<![a-z0-9])s(\\d{1,2})(?![a-z0-9])"),
            Pattern.compile("(?i)\\bseason\\s*(\\d{1,2})\\b"),
    };

    public static List<Result> search(String query, String lang) throws IOException {
        String title = query.trim();
        int wantSeason = -1, wantEpisode = -1;
        for (Pattern pat : EPISODE_TOKENS) {
            Matcher m = pat.matcher(title);
            if (m.find()) {
                wantSeason = Integer.parseInt(m.group(1));
                wantEpisode = Integer.parseInt(m.group(2));
                title = (title.substring(0, m.start()) + " " + title.substring(m.end())).trim();
                break;
            }
        }
        if (wantSeason < 0) {
            for (Pattern pat : SEASON_TOKENS) {
                Matcher m = pat.matcher(title);
                if (m.find()) {
                    wantSeason = Integer.parseInt(m.group(1));
                    title = (title.substring(0, m.start()) + " " + title.substring(m.end())).trim();
                    break;
                }
            }
        }
        if (title.isEmpty()) title = query.trim();

        String language = languageName(lang);
        String movieBody = new String(get(BASE + "/movies/search?q=" + enc(title) + "&searchType=text",
                MAX_JSON), StandardCharsets.UTF_8);
        JsonArray found = findArray(parse(movieBody));
        List<Result> out = new ArrayList<>();
        if (found == null) return out;

        // usable entries only: with subtitles, and (if the user asked for a season) that season
        List<JsonObject> movies = new ArrayList<>();
        for (JsonElement me : found) {
            if (!me.isJsonObject()) continue;
            JsonObject m = me.getAsJsonObject();
            if (firstString(m, "movieId", "movie_id", "id") == null) continue;
            if (num(m, "subtitleCount") <= 0 && m.has("subtitleCount")) continue;
            if (wantSeason >= 0) {
                boolean series = "tvseries".equalsIgnoreCase(firstString(m, "type"));
                if (!series || num(m, "season") != wantSeason) continue;
            }
            movies.add(m);
        }
        // every season of a series is its own entry; list them in season order (movies keep API order)
        java.util.Collections.sort(movies, (x, y) -> {
            boolean sx = "tvseries".equalsIgnoreCase(firstString(x, "type"));
            boolean sy = "tvseries".equalsIgnoreCase(firstString(y, "type"));
            if (sx && sy) return Long.compare(num(x, "season"), num(y, "season"));
            return 0;
        });

        boolean episodeMode = wantSeason >= 0 && wantEpisode >= 0;
        int queried = 0;
        for (JsonObject m : movies) {
            if (queried >= MOVIES_TO_QUERY || out.size() >= MAX_RESULTS) break;
            queried++;
            String movieId = firstString(m, "movieId", "movie_id", "id");
            String name = firstString(m, "title", "name");
            String year = firstString(m, "releaseYear", "release_year", "year");
            String shown = name == null ? null : (year == null ? name : name + " (" + year + ")");
            boolean series = "tvseries".equalsIgnoreCase(firstString(m, "type"));
            if (shown != null && series && m.has("season")) {
                shown += String.format(Locale.US, " S%02d", num(m, "season"));
            }

            // episode searches need to look through more pages, because most results are other episodes
            int pageSize = episodeMode ? 25 : SUBS_PER_MOVIE;
            int maxPages = episodeMode ? 4 : 1;
            int perMovie = 0;
            for (int page = 1; page <= maxPages && perMovie < SUBS_PER_MOVIE && out.size() < MAX_RESULTS; page++) {
                String subBody = new String(get(BASE + "/subtitles?movieId=" + enc(movieId)
                        + "&language=" + enc(language) + "&sort=popular&limit=" + pageSize + "&page=" + page,
                        MAX_JSON), StandardCharsets.UTF_8);
                JsonElement root = parse(subBody);
                JsonArray subs = findArray(root);
                if (subs == null) break;
                for (JsonElement se : subs) {
                    if (!se.isJsonObject()) continue;
                    JsonObject sub = se.getAsJsonObject();
                    long id = num(sub, "subtitleId");
                    if (id == 0) id = num(sub, "id");
                    if (id == 0) continue;
                    String releases = releaseNames(sub);
                    if (episodeMode && !matchesEpisode(releases, wantSeason, wantEpisode)
                            && !isSeasonPack(releases, wantSeason)) {
                        continue;
                    }
                    Result r = new Result();
                    r.id = id;
                    r.title = episodeMode ? shown + String.format(Locale.US, "E%02d", wantEpisode) : shown;
                    r.release = describe(sub);
                    r.season = wantSeason;
                    r.episode = wantEpisode;
                    String l = firstString(sub, "language", "lang");
                    r.language = shortLanguage(l == null ? language : l);
                    if (r.title == null || r.title.isEmpty()) r.title = releases;
                    out.add(r);
                    if (++perMovie >= SUBS_PER_MOVIE || out.size() >= MAX_RESULTS) break;
                }
                if (root.isJsonObject() && root.getAsJsonObject().has("pagination")
                        && root.getAsJsonObject().get("pagination").isJsonObject()) {
                    long pages = num(root.getAsJsonObject().getAsJsonObject("pagination"), "pages");
                    if (page >= pages) break;
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------- episode matching

    static boolean matchesEpisode(String text, int season, int episode) {
        if (text == null) return false;
        return Pattern.compile("(?i)(?<![a-z0-9])s0*" + season + "[\\s._-]*e0*" + episode + "(?![0-9])").matcher(text).find()
                || Pattern.compile("(?i)(?<![0-9])0*" + season + "x0*" + episode + "(?![0-9])").matcher(text).find();
    }

    /** "Show.S02.1080p" / "Season 2 Complete": names the season but no episode. */
    static boolean isSeasonPack(String text, int season) {
        if (text == null) return false;
        Matcher m = Pattern.compile("(?i)(?<![a-z0-9])(?:s0*" + season + "|season[\\s._-]*0*" + season + ")(?![0-9])").matcher(text);
        Pattern episodeAfter = Pattern.compile("(?i)^[\\s._-]*e\\d");
        while (m.find()) {
            if (!episodeAfter.matcher(text.substring(m.end())).find()) return true;
        }
        return false;
    }

    /** All release names of a subtitle, joined; used for matching. */
    private static String releaseNames(JsonObject s) {
        StringBuilder sb = new StringBuilder();
        for (String n : new String[]{"releaseInfo", "release_info", "release"}) {
            if (!s.has(n) || s.get(n).isJsonNull()) continue;
            JsonElement e = s.get(n);
            if (e.isJsonPrimitive()) sb.append(e.getAsString()).append('\n');
            else if (e.isJsonArray()) {
                for (JsonElement x : e.getAsJsonArray()) if (x.isJsonPrimitive()) sb.append(x.getAsString()).append('\n');
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ download

    /** Downloads a subtitle and, if SubSource sent an archive, takes the subtitle file out of it. */
    public static Fetched download(long subtitleId, int season, int episode) throws IOException {
        byte[] raw = get(BASE + "/subtitles/" + subtitleId + "/download", MAX_DOWNLOAD);
        Fetched f = new Fetched();
        if (raw.length > 3 && raw[0] == 'P' && raw[1] == 'K') {
            f = unzipBest(raw, season, episode);
        } else {
            f.data = raw;
            f.ext = guessExt(raw);
        }
        if (f.data == null || f.data.length == 0) throw new IOException("SubSource returned an empty subtitle");
        return f;
    }

    private static Fetched unzipBest(byte[] zip, int season, int episode) throws IOException {
        String[] pref = {".srt", ".ass", ".ssa", ".vtt", ".sub"};
        final boolean wantEp = season >= 0 && episode >= 0;
        List<String> names = new ArrayList<>();
        List<byte[]> datas = new ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName().toLowerCase(Locale.ROOT);
                if (e.isDirectory() || name.startsWith("__macosx") || name.contains("/.")) continue;
                boolean subtitle = false;
                for (String ext : pref) if (name.endsWith(ext)) subtitle = true;
                if (!subtitle) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zin.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > MAX_DOWNLOAD) throw new IOException("Subtitle too large");
                }
                names.add(name);
                datas.add(bos.toByteArray());
            }
        }
        if (names.isEmpty()) throw new IOException("No subtitle file found in the SubSource download");

        // a season pack holds many episodes: take the requested one, never a guess
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) candidates.add(i);
        if (wantEp && names.size() > 1) {
            List<Integer> matching = new ArrayList<>();
            for (int i : candidates) if (matchesEpisode(names.get(i), season, episode)) matching.add(i);
            if (matching.isEmpty()) {
                throw new IOException(String.format(Locale.US, "This pack doesn't contain S%02dE%02d", season, episode));
            }
            candidates = matching;
        }
        int best = -1, bestRank = Integer.MAX_VALUE;
        for (int i : candidates) {
            int rank = pref.length;
            for (int r = 0; r < pref.length; r++) if (names.get(i).endsWith(pref[r])) rank = r;
            // best format first; among equals the bigger file (the full subtitle, not a "forced" extract)
            if (rank < bestRank || (rank == bestRank && datas.get(i).length > datas.get(best).length)) {
                best = i;
                bestRank = rank;
            }
        }
        Fetched f = new Fetched();
        f.data = datas.get(best);
        f.ext = bestRank < pref.length ? pref[bestRank] : ".srt";
        return f;
    }

    private static String guessExt(byte[] data) {
        String head = new String(data, 0, Math.min(data.length, 2048), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        if (head.contains("webvtt")) return ".vtt";
        if (head.contains("[script info]") || head.contains("[events]")) return ".ass";
        return ".srt";
    }

    // --------------------------------------------------------------- HTTP

    /**
     * GET with the session key. A rejected (401/403) or rate-limited (429) key is skipped and the next random
     * key is tried; network errors and other HTTP errors do not burn keys.
     */
    private static byte[] get(String url, int maxBytes) throws IOException {
        if (!hasKeys()) {
            throw new NoKeyException("This build has no SubSource API key");
        }
        long waitedMs = 0;
        while (true) {
            String key = currentKey();
            if (key == null) {
                long free = soonestFreeMs();
                if (free < 0) throw new NoKeyException("All SubSource keys were rejected");
                // every key is rate limited: a short wait is better than an error
                if (free <= MAX_AUTO_WAIT_MS - waitedMs) {
                    try {
                        Thread.sleep(free + 200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Cancelled");
                    }
                    waitedMs += free + 200;
                    continue;
                }
                throw new NoKeyException("Subtitle search is busy right now, please try again in a while");
            }
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setConnectTimeout(10000);
                c.setReadTimeout(25000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("X-API-Key", key);
                c.setRequestProperty("User-Agent", USER_AGENT);
                c.setRequestProperty("Accept", "application/json, application/zip, */*");
                int code = c.getResponseCode();
                if (code == 401 || code == 403) {
                    ban(key, Long.MAX_VALUE);   // invalid / inactive key: skip it for the whole session
                    continue;
                }
                if (code == 429) {
                    ban(key, cooldownFor(errorJson(c)));
                    continue;
                }
                if (code >= 400) {
                    String msg = errorMessage(c);
                    if (code == 404) throw new IOException("Not found on SubSource");
                    throw new IOException("SubSource error " + code + (msg != null ? ": " + msg : ""));
                }
                return readAll(c.getInputStream(), maxBytes);
            } finally {
                c.disconnect();
            }
        }
    }

    private static JsonObject errorJson(HttpURLConnection c) {
        try {
            InputStream es = c.getErrorStream();
            if (es == null) return null;
            return JsonParser.parseString(new String(readAll(es, 64 * 1024), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static String errorMessage(HttpURLConnection c) {
        JsonObject o = errorJson(c);
        return o == null ? null : firstString(o, "message", "error", "detail");
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > max) throw new IOException("SubSource response too large");
            }
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------- JSON helpers

    private static JsonElement parse(String body) throws IOException {
        try {
            return JsonParser.parseString(body);
        } catch (Exception e) {
            throw new IOException("Unexpected response from SubSource");
        }
    }

    /** The result list, whether the API wraps it as {"data":[...]}, {"data":{"results":[...]}} or sends a bare array. */
    private static JsonArray findArray(JsonElement root) {
        if (root == null) return null;
        if (root.isJsonArray()) return root.getAsJsonArray();
        if (!root.isJsonObject()) return null;
        JsonObject o = root.getAsJsonObject();
        for (String k : new String[]{"data", "results", "items", "subtitles", "movies"}) {
            if (!o.has(k)) continue;
            JsonElement e = o.get(k);
            if (e.isJsonArray()) return e.getAsJsonArray();
            if (e.isJsonObject()) {
                JsonArray inner = findArray(e);
                if (inner != null) return inner;
            }
        }
        return null;
    }

    private static String firstString(JsonObject o, String... names) {
        for (String n : names) {
            if (o.has(n) && !o.get(n).isJsonNull() && o.get(n).isJsonPrimitive()) {
                String s = o.get(n).getAsString();
                if (!s.isEmpty()) return s;
            }
        }
        return null;
    }

    /** Release name(s) plus quick facts: "Inception.2010.x264 YIFY  ·  HI  ·  1,191 downloads". */
    private static String describe(JsonObject s) {
        StringBuilder sb = new StringBuilder();
        for (String n : new String[]{"releaseInfo", "release_info", "release"}) {
            if (!s.has(n) || s.get(n).isJsonNull()) continue;
            JsonElement e = s.get(n);
            if (e.isJsonPrimitive()) {
                sb.append(e.getAsString());
            } else if (e.isJsonArray()) {
                int count = 0;
                for (JsonElement x : e.getAsJsonArray()) {
                    if (!x.isJsonPrimitive() || count >= 2) continue;
                    if (count++ > 0) sb.append(" / ");
                    sb.append(x.getAsString());
                }
            }
            if (sb.length() > 0) break;
        }
        StringBuilder facts = new StringBuilder();
        if (flag(s, "hearingImpaired")) facts.append("HI");
        if (flag(s, "foreignParts")) append(facts, "foreign parts only");
        long dl = num(s, "downloads");
        if (dl > 0) append(facts, String.format(Locale.US, "%,d downloads", dl));
        if (facts.length() > 0) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(facts);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void append(StringBuilder sb, String part) {
        if (sb.length() > 0) sb.append("  \u00b7  ");
        sb.append(part);
    }

    private static boolean flag(JsonObject o, String name) {
        return o.has(name) && o.get(name).isJsonPrimitive() && o.get(name).getAsBoolean();
    }

    private static long num(JsonObject o, String name) {
        try {
            return o.has(name) && o.get(name).isJsonPrimitive() ? o.get(name).getAsLong() : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String enc(String s) throws IOException {
        return URLEncoder.encode(s, "UTF-8");
    }

    // ------------------------------------------------------------ languages

    private static final Map<String, String> LANGS = new HashMap<>();

    static {
        String[] pairs = {
                "en", "english", "es", "spanish", "fr", "french", "de", "german", "it", "italian",
                "pt", "portuguese", "ar", "arabic", "fa", "farsi_persian", "bn", "bengali", "hi", "hindi",
                "ur", "urdu", "tr", "turkish", "ru", "russian", "id", "indonesian", "vi", "vietnamese",
                "ko", "korean", "ja", "japanese", "th", "thai", "nl", "dutch", "pl", "polish",
                "sv", "swedish", "da", "danish", "fi", "finnish", "no", "norwegian", "el", "greek",
                "he", "hebrew", "ro", "romanian", "hu", "hungarian", "cs", "czech", "bg", "bulgarian",
                "hr", "croatian", "sr", "serbian", "ms", "malay", "ta", "tamil", "ne", "nepali"};
        for (int i = 0; i < pairs.length; i += 2) LANGS.put(pairs[i], pairs[i + 1]);
    }

    /** "en" -> "english"; a full SubSource name such as "farsi_persian" or "Brazilian Portuguese" is passed through. */
    static String languageName(String input) {
        String s = input == null ? "" : input.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (s.isEmpty()) return "english";
        String mapped = LANGS.get(s);
        return mapped != null ? mapped : s;
    }

    private static String shortLanguage(String name) {
        if (name == null || name.isEmpty()) return "?";
        String n = name.toLowerCase(Locale.ROOT).replace(' ', '_');
        for (Map.Entry<String, String> e : LANGS.entrySet()) {
            if (e.getValue().equals(n)) return e.getKey();
        }
        return n.length() <= 3 ? n : n.substring(0, 3);
    }

    /**
     * Checks that a link really leads to a subtitle file (SRT / VTT / ASS / SSA, optionally gzipped).
     * Blocking: call from a background thread.
     */
    public static boolean looksLikeSubtitle(String link) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(link).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", USER_AGENT);
            if (c.getResponseCode() >= 400) return false;
            InputStream in = c.getInputStream();
            byte[] head = new byte[256 * 1024];
            int len = 0, n;
            while (len < head.length && (n = in.read(head, len, head.length - len)) > 0) len += n;
            in.close();
            if (len == 0) return false;
            byte[] data = java.util.Arrays.copyOf(head, len);
            if (len > 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) {
                java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(data));
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int r;
                try {
                    while ((r = gz.read(b)) > 0 && bos.size() < 256 * 1024) bos.write(b, 0, r);
                } catch (IOException ignored) {
                    // truncated gzip: keep what we got
                }
                data = bos.toByteArray();
            }
            String text = new String(data, StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
            String t = text.trim();
            if (t.startsWith("<!doctype") || t.startsWith("<html") || t.startsWith("<?xml") && t.contains("<html")) {
                return false;
            }
            return text.contains("-->") || text.contains("[script info]") || text.contains("dialogue:")
                    || text.contains("webvtt");
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

}
