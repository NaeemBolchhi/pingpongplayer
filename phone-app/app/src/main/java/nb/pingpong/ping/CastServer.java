package nb.pingpong.ping;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small HTTP file server that lets the TV stream a file living on this phone.
 * <p>
 * Every served file gets its own random token; the URL looks like {@code http://phone:port/<token>/<file name>}
 * and only the token is matched, so the name after it is free to be the real file name (the TV shows it as the
 * title and reads the subtitle format from its extension). Only the TV's address is answered. GET, HEAD and
 * single byte ranges are supported, because the player seeks by opening new ranged connections. Files are read
 * with positional reads, so any number of connections can stream the same file at once.
 */
final class CastServer {
    static final class Entry {
        final String token;
        final String name;
        final String mime;
        final FileChannel channel;
        final long size;

        Entry(String token, String name, String mime, FileChannel channel, long size) {
            this.token = token;
            this.name = name;
            this.mime = mime;
            this.channel = channel;
            this.size = size;
        }
    }

    private static final int MAX_LINE = 8192;

    private final InetAddress allowedPeer; // null = unknown, then the token alone protects the files
    // Entries may be added while the server is running: a playlist materialises files lazily.
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Set<Socket> open = Collections.synchronizedSet(new HashSet<Socket>());
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "cast-conn");
        t.setDaemon(true);
        return t;
    });
    private final SecureRandom random = new SecureRandom();
    private ServerSocket server;
    private volatile boolean running;

    CastServer(String tvHost) {
        InetAddress peer = null;
        try {
            peer = InetAddress.getByName(tvHost);
        } catch (Exception ignored) {
        }
        allowedPeer = peer;
    }

    /** Registers a file (before or after {@link #start()}). */
    Entry add(String name, String mime, FileChannel channel, long size) {
        byte[] rnd = new byte[12];
        random.nextBytes(rnd);
        StringBuilder tok = new StringBuilder();
        for (byte b : rnd) tok.append(String.format(Locale.ROOT, "%02x", b));
        Entry e = new Entry(tok.toString(), name, guessMime(name, mime), channel, size);
        entries.put(e.token, e);
        return e;
    }

    /** Starts listening on a free port and returns it. */
    int start() throws IOException {
        server = new ServerSocket(0);
        running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    final Socket s = server.accept();
                    open.add(s);
                    pool.execute(() -> serve(s));
                } catch (IOException e) {
                    if (!running) break;
                } catch (RuntimeException e) {
                    break; // pool already shut down
                }
            }
        }, "cast-accept");
        t.setDaemon(true);
        t.start();
        return server.getLocalPort();
    }

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        synchronized (open) {
            for (Socket s : open) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
            open.clear();
        }
        pool.shutdownNow();
    }

    // ------------------------------------------------------------ request handling

    private void serve(Socket s) {
        try {
            s.setSoTimeout(10_000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            String request = readLine(in);
            if (request == null) return;
            String range = null, line;
            for (int i = 0; i < 100 && (line = readLine(in)) != null && !line.isEmpty(); i++) {
                if (line.regionMatches(true, 0, "range:", 0, 6)) range = line.substring(6).trim();
            }
            s.setSoTimeout(0);

            if (allowedPeer != null && !allowedPeer.equals(s.getInetAddress())) {
                status(out, "403 Forbidden", null);
                return;
            }
            String[] parts = request.split(" ");
            if (parts.length < 2) {
                status(out, "400 Bad Request", null);
                return;
            }
            boolean head = parts[0].equals("HEAD");
            if (!head && !parts[0].equals("GET")) {
                status(out, "405 Method Not Allowed", null);
                return;
            }
            Entry e = entries.get(tokenOf(parts[1]));
            if (e == null) {
                status(out, "404 Not Found", null);
                return;
            }

            long size = e.size, start = 0, end = size - 1;
            boolean partial = false;
            if (range != null && size > 0 && range.regionMatches(true, 0, "bytes=", 0, 6)) {
                String spec = range.substring(6).trim();
                int comma = spec.indexOf(',');
                if (comma >= 0) spec = spec.substring(0, comma).trim(); // first range only
                int dash = spec.indexOf('-');
                try {
                    if (dash >= 0) {
                        String a = spec.substring(0, dash).trim(), b = spec.substring(dash + 1).trim();
                        if (a.isEmpty()) {
                            long suffix = Long.parseLong(b);
                            start = Math.max(0, size - suffix);
                            if (suffix <= 0) start = size; // unsatisfiable
                        } else {
                            start = Long.parseLong(a);
                            if (!b.isEmpty()) end = Math.min(Long.parseLong(b), size - 1);
                        }
                        if (start >= size || start > end) {
                            status(out, "416 Range Not Satisfiable", "Content-Range: bytes */" + size + "\r\n");
                            return;
                        }
                        partial = true;
                    }
                } catch (NumberFormatException bad) {
                    start = 0;
                    end = size - 1; // ignore a range we can't read and send the whole file
                    partial = false;
                }
            }

            long length = size == 0 ? 0 : end - start + 1;
            StringBuilder h = new StringBuilder();
            h.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            h.append("Content-Type: ").append(e.mime).append("\r\n");
            h.append("Accept-Ranges: bytes\r\n");
            h.append("Content-Length: ").append(length).append("\r\n");
            if (partial) h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(size).append("\r\n");
            h.append("Connection: close\r\n\r\n");
            out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (!head) {
                byte[] buf = new byte[64 * 1024];
                ByteBuffer bb = ByteBuffer.wrap(buf);
                long pos = start, left = length;
                while (left > 0 && running) {
                    bb.clear();
                    bb.limit((int) Math.min(buf.length, left));
                    int n = e.channel.read(bb, pos);
                    if (n <= 0) break;
                    out.write(buf, 0, n);
                    pos += n;
                    left -= n;
                }
            }
            out.flush();
        } catch (IOException ignored) {
            // the player closed the connection (seek, stop): normal
        } finally {
            open.remove(s);
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String tokenOf(String target) {
        int q = target.indexOf('?');
        if (q >= 0) target = target.substring(0, q);
        if (target.startsWith("/")) target = target.substring(1);
        int slash = target.indexOf('/');
        return slash < 0 ? target : target.substring(0, slash);
    }

    private static void status(OutputStream out, String status, String extraHeaders) throws IOException {
        out.write(("HTTP/1.1 " + status + "\r\n" + (extraHeaders == null ? "" : extraHeaders)
                + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') sb.setLength(n - 1);
                return sb.toString();
            }
            if (sb.length() >= MAX_LINE) throw new IOException("Header line too long");
            sb.append((char) c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    static String guessMime(String name, String provided) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String ext = dot >= 0 ? n.substring(dot + 1) : "";
        switch (ext) {
            case "mp4":
            case "m4v":
                return "video/mp4";
            case "mkv":
                return "video/x-matroska";
            case "webm":
                return "video/webm";
            case "avi":
                return "video/x-msvideo";
            case "mov":
                return "video/quicktime";
            case "ts":
            case "m2ts":
            case "mts":
                return "video/mp2t";
            case "mpg":
            case "mpeg":
                return "video/mpeg";
            case "wmv":
                return "video/x-ms-wmv";
            case "flv":
                return "video/x-flv";
            case "3gp":
                return "video/3gpp";
            case "ogv":
                return "video/ogg";
            case "srt":
                return "application/x-subrip";
            case "vtt":
                return "text/vtt";
            case "ass":
            case "ssa":
                return "text/x-ssa";
            default:
                return provided != null && !provided.isEmpty() ? provided : "application/octet-stream";
        }
    }
}
