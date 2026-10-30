package nb.pingpong.pong;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import okhttp3.Request;
import okhttp3.Response;

/**
 * Looks at a link that failed to play and says in plain words what the server actually returned
 * (refused, a web page, an encrypted stream, a segment that isn't video...). Shown on the error screen.
 */
final class StreamProbe {
    private StreamProbe() {
    }

    static String run(String url, String userAgent, String referer) {
        try {
            if (!url.startsWith("http")) return "";
            Resp pl = get(url, userAgent, referer, 1024 * 1024, false);
            if (pl.code >= 400) return "The server refused the link (HTTP " + pl.code + ").";
            String text = new String(pl.body, StandardCharsets.UTF_8).replace("﻿", "").trim();
            if (!text.startsWith("#EXTM3U")) {
                String type = pl.type == null ? "" : pl.type.toLowerCase(Locale.ROOT);
                if (type.contains("html") || text.toLowerCase(Locale.ROOT).startsWith("<!doctype")
                        || text.toLowerCase(Locale.ROOT).startsWith("<html")) {
                    return "The link opens a web page, not a video (the token may have expired).";
                }
                return ""; // a plain media file: nothing useful to add
            }
            if (text.contains("METHOD=SAMPLE-AES") || text.contains("skd://") || text.contains("KEYFORMAT=\"com.")) {
                return "This stream uses DRM-style encryption, which this player can't decrypt.";
            }
            String first = null;
            for (String line : text.split("\\r?\\n")) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    first = t;
                    break;
                }
            }
            if (first == null) return "The playlist is empty.";
            String abs = new URL(new URL(pl.finalUrl), first).toString();
            if (abs.split("\\?")[0].toLowerCase(Locale.ROOT).endsWith(".m3u8")) {
                return "Master playlist OK; the quality list points to " + host(abs) + ".";
            }
            Resp seg = get(abs, userAgent, referer, 4096, true);
            if (seg.code >= 400) return "Playlist OK, but the first video piece was refused (HTTP " + seg.code + ").";
            boolean ts = seg.body.length > 188 && (seg.body[0] & 0xFF) == 0x47 && (seg.body[188] & 0xFF) == 0x47;
            if (ts) {
                return "Server looks fine (playlist and first piece are valid). The player itself rejected it.";
            }
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < Math.min(6, seg.body.length); i++) hex.append(String.format("%02X ", seg.body[i] & 0xFF));
            return "The first video piece isn't plain MPEG-TS (starts " + hex.toString().trim() + ", type "
                    + seg.type + ").";
        } catch (Exception e) {
            return "Couldn't reach the server (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ").";
        }
    }

    private static String host(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return "another address";
        }
    }

    private static final class Resp {
        int code;
        String type;
        String finalUrl;
        byte[] body;
    }

    private static Resp get(String url, String ua, String referer, int max, boolean range) throws Exception {
        Request.Builder rb = Net.request(url, ua, referer);
        if (range) rb.header("Range", "bytes=0-" + (max - 1));
        Resp r = new Resp();
        try (Response resp = Net.client().newCall(rb.build()).execute()) {
            r.code = resp.code();
            r.type = resp.header("Content-Type");
            r.finalUrl = resp.request().url().toString();
            if (r.code >= 400 || resp.body() == null) {
                r.body = new byte[0];
                return r;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = resp.body().byteStream()) {
                byte[] b = new byte[8192];
                int n;
                while (bos.size() < max && (n = in.read(b, 0, Math.min(b.length, max - bos.size()))) > 0) bos.write(b, 0, n);
            }
            r.body = bos.toByteArray();
            return r;
        }
    }
}
