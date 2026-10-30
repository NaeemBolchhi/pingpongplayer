package nb.pingpong.pong;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Response;

/**
 * Tiny loopback HTTP proxy that sits between LibVLC and an HLS stream.
 * <p>
 * Some hosts disguise their segments (for example "page-0.html" instead of "seg-0.ts") or send them with a
 * wrong content type. Players such as MX Player ignore that and sniff the data; LibVLC's HLS reader is much
 * stricter. The proxy fetches the playlist itself (with browser-like headers and the right referer), rewrites every
 * segment, key and sub-playlist address so it points back at the proxy, and serves segments as
 * {@code video/mp2t}. The bytes themselves are passed through untouched.
 */
final class HlsProxy {
    private static final int MAX_PLAYLIST = 8 * 1024 * 1024;
    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]*)\"");

    private final String userAgent;
    private ServerSocket server;

    HlsProxy(String userAgent) {
        this.userAgent = userAgent;
    }

    /** Starts the proxy if needed. */
    synchronized void start() throws IOException {
        if (server != null && !server.isClosed()) return;
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        final ServerSocket s = server;
        Thread t = new Thread(() -> {
            while (!s.isClosed()) {
                try {
                    final Socket c = s.accept();
                    Thread h = new Thread(() -> handle(c), "hls-proxy-conn");
                    h.setDaemon(true);
                    h.start();
                } catch (IOException e) {
                    break;
                }
            }
        }, "hls-proxy");
        t.setDaemon(true);
        t.start();
    }

    synchronized void stop() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        server = null;
    }

    /** Address to give LibVLC for the playlist at {@code url}; {@code referer} may be null. */
    synchronized String playlistUrl(String url, String referer) throws IOException {
        start();
        return "http://127.0.0.1:" + server.getLocalPort() + "/m.m3u8?r=" + enc(referer == null ? "" : referer)
                + "&u=" + enc(url);
    }

    // ------------------------------------------------------------------ requests

    private void handle(Socket c) {
        try (Socket s = c) {
            s.setSoTimeout(15000);
            String request = readLine(s.getInputStream());
            String line;
            while ((line = readLine(s.getInputStream())) != null && !line.isEmpty()) {
                // headers are not needed
            }
            if (request == null) return;
            String[] parts = request.split(" ");
            if (parts.length < 2) return;
            String target = parts[1];
            int q = target.indexOf('?');
            String path = q < 0 ? target : target.substring(0, q);
            String query = q < 0 ? "" : target.substring(q + 1);
            String u = param(query, "u");
            String r = param(query, "r");
            OutputStream out = s.getOutputStream();
            if (u == null || u.isEmpty()) {
                status(out, 400, "Bad Request");
                return;
            }
            if (path.equals("/m.m3u8")) servePlaylist(out, u, r);
            else servePassThrough(out, u, r, path.equals("/s.ts") ? "video/mp2t" : "application/octet-stream");
        } catch (Exception ignored) {
            // the player will simply retry or report its own error
        }
    }

    private void servePlaylist(OutputStream out, String url, String referer) throws IOException {
        try (Response resp = open(url, referer)) {
            if (resp.code() >= 400 || resp.body() == null) {
                status(out, resp.code(), "Upstream error");
                return;
            }
            byte[] raw = readAll(resp.body().byteStream(), MAX_PLAYLIST);
            // relative entries resolve against the address after any redirects
            String base = resp.request().url().toString();
            String text = new String(raw, StandardCharsets.UTF_8);
            byte[] body = rewrite(text, base, referer).getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: "
                    + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
        }
    }

    private void servePassThrough(OutputStream out, String url, String referer, String type) throws IOException {
        try (Response resp = open(url, referer)) {
            if (resp.code() >= 400 || resp.body() == null) {
                status(out, resp.code(), "Upstream error");
                return;
            }
            // no Content-Length: the upstream may be compressed in transit; the connection close ends the body
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            try (InputStream in = resp.body().byteStream()) {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            out.flush();
        }
    }

    // ------------------------------------------------------------------- rewriting

    /** Points every address in the playlist back at this proxy. Package-private for testing. */
    String rewrite(String text, String base, String referer) {
        int port = server != null ? server.getLocalPort() : 0;
        return rewrite(text, base, referer, "http://127.0.0.1:" + port);
    }

    String rewrite(String text, String base, String referer, String self) {
        boolean master = text.contains("#EXT-X-STREAM-INF");
        StringBuilder sb = new StringBuilder(text.length() + 1024);
        String r = referer == null ? "" : referer;
        for (String line : text.split("\\r?\\n", -1)) {
            String t = line.trim();
            if (t.isEmpty()) {
                sb.append(line).append('\n');
            } else if (t.startsWith("#")) {
                if (t.startsWith("#EXT-X-MEDIA:") || t.startsWith("#EXT-X-I-FRAME-STREAM-INF:")) {
                    sb.append(replaceUris(line, base, r, self, "/m.m3u8")).append('\n');
                } else if (t.startsWith("#EXT-X-KEY") || t.startsWith("#EXT-X-MAP")
                        || t.startsWith("#EXT-X-SESSION-KEY")) {
                    sb.append(replaceUris(line, base, r, self, "/k.bin")).append('\n');
                } else {
                    sb.append(line).append('\n');
                }
            } else {
                String abs = resolve(base, t);
                String route = master || isPlaylistLike(abs) ? "/m.m3u8" : "/s.ts";
                sb.append(self).append(route).append("?r=").append(enc(r)).append("&u=").append(enc(abs)).append('\n');
            }
        }
        return sb.toString();
    }

    private static boolean isPlaylistLike(String url) {
        String p = url;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        return p.toLowerCase(java.util.Locale.ROOT).endsWith(".m3u8");
    }

    private static String replaceUris(String line, String base, String referer, String self, String route) {
        Matcher m = URI_ATTR.matcher(line);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String abs = resolve(base, m.group(1));
            String prox = self + route + "?r=" + enc(referer) + "&u=" + enc(abs);
            m.appendReplacement(out, Matcher.quoteReplacement("URI=\"" + prox + "\""));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String resolve(String base, String ref) {
        try {
            return new URL(new URL(base), ref).toString();
        } catch (Exception e) {
            return ref;
        }
    }

    // ---------------------------------------------------------------------- helpers

    private Response open(String url, String referer) throws IOException {
        return Net.client().newCall(Net.request(url, userAgent, referer).build()).execute();
    }

    private static void status(OutputStream out, int code, String text) throws IOException {
        out.write(("HTTP/1.1 " + code + " " + text + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[16 * 1024];
        int n;
        while ((n = in.read(b)) > 0) {
            bos.write(b, 0, n);
            if (bos.size() > max) throw new IOException("playlist too large");
        }
        in.close();
        return bos.toByteArray();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) >= 0) {
            if (ch == '\n') break;
            if (ch != '\r') sb.append((char) ch);
        }
        if (ch < 0 && sb.length() == 0) return null;
        return sb.toString();
    }

    private static String param(String query, String name) {
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(name)) {
                try {
                    return URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
