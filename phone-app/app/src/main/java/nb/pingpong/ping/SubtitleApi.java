package nb.pingpong.ping;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Subtitle search through the OpenSubtitles.com REST API (https://opensubtitles.stoplight.io).
 * Needs a free API key; downloading also needs an OpenSubtitles account (username + password).
 */
public class SubtitleApi {
    public static class Result {
        public int fileId;
        public String title;
        public String release;
        public String language;

        @Override
        public String toString() {
            String t = (title == null || title.isEmpty()) ? "(untitled)" : title;
            String r = (release == null || release.isEmpty()) ? "" : "\n" + release;
            return "[" + (language == null ? "?" : language) + "] " + t + r;
        }
    }

    private static final String DEFAULT_BASE = "https://api.opensubtitles.com/api/v1";
    private static final String USER_AGENT = "PingPong v1.0";

    private static String token;
    private static String baseUrl = DEFAULT_BASE;

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("SubtitlePrefs", Context.MODE_PRIVATE);
    }

    public static boolean hasApiKey(Context c) {
        return !prefs(c).getString("api_key", "").trim().isEmpty();
    }

    public static void saveCredentials(Context c, String apiKey, String user, String pass) {
        prefs(c).edit().putString("api_key", apiKey.trim()).putString("username", user.trim())
                .putString("password", pass).apply();
        token = null; // force new login
        baseUrl = DEFAULT_BASE;
    }

    public static List<Result> search(Context c, String query, String lang) throws IOException {
        String key = prefs(c).getString("api_key", "").trim();
        if (key.isEmpty()) throw new IOException("Set your OpenSubtitles API key first");
        String url = baseUrl + "/subtitles?query=" + URLEncoder.encode(query, "UTF-8")
                + (lang.isEmpty() ? "" : "&languages=" + URLEncoder.encode(lang, "UTF-8"))
                + "&order_by=download_count&order_direction=desc";
        String body = request("GET", url, key, null, null);
        List<Result> out = new ArrayList<>();
        JsonArray data = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("data");
        if (data == null) return out;
        for (JsonElement e : data) {
            JsonObject attr = e.getAsJsonObject().getAsJsonObject("attributes");
            if (attr == null) continue;
            JsonArray files = attr.getAsJsonArray("files");
            if (files == null || files.size() == 0) continue;
            Result r = new Result();
            r.fileId = files.get(0).getAsJsonObject().get("file_id").getAsInt();
            r.language = str(attr, "language");
            r.release = str(attr, "release");
            JsonObject fd = attr.has("feature_details") && attr.get("feature_details").isJsonObject()
                    ? attr.getAsJsonObject("feature_details") : null;
            r.title = fd != null ? str(fd, "title") : null;
            if (r.title == null || r.title.isEmpty()) r.title = str(attr, "release");
            out.add(r);
            if (out.size() >= 25) break;
        }
        return out;
    }

    /** Returns a direct download URL for the subtitle file. */
    public static String downloadLink(Context c, int fileId) throws IOException {
        String key = prefs(c).getString("api_key", "").trim();
        if (key.isEmpty()) throw new IOException("Set your OpenSubtitles API key first");
        if (token == null) login(c, key);
        JsonObject req = new JsonObject();
        req.addProperty("file_id", fileId);
        String body = request("POST", baseUrl + "/download", key, token, req.toString());
        String link = str(JsonParser.parseString(body).getAsJsonObject(), "link");
        if (link == null) throw new IOException("No download link returned");
        return link;
    }

    private static void login(Context c, String key) throws IOException {
        String user = prefs(c).getString("username", "").trim();
        String pass = prefs(c).getString("password", "");
        if (user.isEmpty() || pass.isEmpty()) {
            throw new IOException("Set your OpenSubtitles username and password (needed to download)");
        }
        JsonObject req = new JsonObject();
        req.addProperty("username", user);
        req.addProperty("password", pass);
        String body = request("POST", DEFAULT_BASE + "/login", key, null, req.toString());
        JsonObject o = JsonParser.parseString(body).getAsJsonObject();
        token = str(o, "token");
        String base = str(o, "base_url");
        if (base != null && !base.isEmpty()) baseUrl = "https://" + base + "/api/v1";
        if (token == null) throw new IOException("Login failed");
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

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private static String request(String method, String url, String apiKey, String bearer, String json)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Api-Key", apiKey);
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Accept", "application/json");
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        if (json != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = in == null ? "" : readAll(in);
        if (code >= 400) {
            String msg = null;
            try {
                JsonObject o = JsonParser.parseString(body).getAsJsonObject();
                msg = str(o, "message");
            } catch (Exception ignored) {
            }
            throw new IOException("OpenSubtitles error " + code + (msg != null ? ": " + msg : ""));
        }
        return body;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toString("UTF-8");
    }
}
