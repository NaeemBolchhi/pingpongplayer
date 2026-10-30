package nb.pingpong.ping;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Hands one subtitle file to the TV over the local network.
 * <p>
 * SubSource downloads need the API key, which must not be sent to the TV, and they arrive as an archive.
 * So the phone downloads and unpacks the subtitle itself, then serves the finished file on a throw-away
 * HTTP port. The TV fetches it through its normal SUB_URL path. The URL contains a random token, only serves
 * that one file, and the server shuts itself down after a few requests or two minutes.
 */
final class SubtitleServer {
    private static final long LIFETIME_MS = 120_000L;
    private static final int MAX_REQUESTS = 3;

    private SubtitleServer() {
    }

    /** Starts serving {@code data} and returns the URL the TV should fetch. */
    static String serve(final byte[] data, String ext, String tvHost, int tvPort) throws IOException {
        InetAddress local = localAddressToward(tvHost, tvPort);
        final ServerSocket server = new ServerSocket(0);
        byte[] rnd = new byte[12];
        new SecureRandom().nextBytes(rnd);
        StringBuilder tok = new StringBuilder();
        for (byte b : rnd) tok.append(String.format("%02x", b));
        final String path = "/" + tok + "/subtitle" + ext;
        final String url = "http://" + local.getHostAddress() + ":" + server.getLocalPort() + path;

        Thread t = new Thread(() -> {
            long deadline = System.currentTimeMillis() + LIFETIME_MS;
            int served = 0;
            try {
                while (served < MAX_REQUESTS) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) break;
                    try {
                        server.setSoTimeout((int) left);
                    } catch (IOException e) {
                        break; // server socket already closed
                    }
                    try (Socket s = server.accept()) {
                        s.setSoTimeout(5000);
                        if (handle(s, path, data)) served++;
                    } catch (java.net.SocketTimeoutException e) {
                        break;
                    } catch (IOException ignored) {
                        // a broken connection must not stop the server
                    }
                }
            } finally {
                try {
                    server.close();
                } catch (IOException ignored) {
                }
            }
        }, "sub-server");
        t.setDaemon(true);
        t.start();
        return url;
    }

    private static boolean handle(Socket s, String path, byte[] data) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
        String request = in.readLine();
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            // skip headers
        }
        OutputStream out = s.getOutputStream();
        boolean ok = request != null && request.startsWith("GET " + path + " ");
        if (!ok) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return false;
        }
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: "
                + data.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(data);
        out.flush();
        return true;
    }

    /** The phone's own address on the network that reaches the TV (connecting a UDP socket sends nothing). */
    private static InetAddress localAddressToward(String host, int port) throws IOException {
        try (DatagramSocket ds = new DatagramSocket()) {
            ds.connect(InetAddress.getByName(host), port);
            InetAddress a = ds.getLocalAddress();
            if (a == null || a.isAnyLocalAddress()) throw new IOException("Can't find this phone's network address");
            return a;
        }
    }
}
