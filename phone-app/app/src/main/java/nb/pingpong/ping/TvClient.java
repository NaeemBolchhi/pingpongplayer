package nb.pingpong.ping;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.google.gson.Gson;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * UDP link to one TV app. Polls STATUS every ~0.8s; the TV answers with its state.
 * No answer for a few seconds = disconnected. Callbacks arrive on the main thread.
 */
public class TvClient {
    public interface Listener {
        void onConnectionChanged(boolean connected);

        void onStatus(TvStatus status);
    }

    private static final long POLL_MS = 800;
    private static final long TIMEOUT_MS = 3500;

    private final DeviceInfo device;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private final Gson gson = new Gson();
    private volatile DatagramSocket socket;
    private volatile boolean running;
    private volatile long lastReply = 0;
    private boolean connected = false;

    public TvClient(DeviceInfo device, Listener listener) {
        this.device = device;
        this.listener = listener;
    }

    public void start() {
        running = true;
        new Thread(this::loop, "tv-client").start();
    }

    public void stop() {
        running = false;
        DatagramSocket s = socket;
        if (s != null) s.close();
        sender.shutdownNow();
    }

    public void send(final String message) {
        try {
            sender.execute(() -> sendNow(message));
        } catch (Exception ignored) {
        }
    }

    private void sendNow(String message) {
        try {
            DatagramSocket s = socket;
            if (s == null) return;
            byte[] data = message.getBytes(StandardCharsets.UTF_8);
            s.send(new DatagramPacket(data, data.length, InetAddress.getByName(device.host), device.port));
        } catch (Exception ignored) {
        }
    }

    private void loop() {
        try {
            DatagramSocket s = new DatagramSocket();
            s.setSoTimeout(400);
            socket = s;
        } catch (Exception e) {
            return;
        }
        byte[] buf = new byte[32768];
        long nextPoll = 0;
        while (running) {
            long now = SystemClock.elapsedRealtime();
            if (now >= nextPoll) {
                send("STATUS");
                nextPoll = now + POLL_MS;
            }
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                String m = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                if (m.startsWith("STATUS:")) {
                    final TvStatus st = gson.fromJson(m.substring(7), TvStatus.class);
                    lastReply = SystemClock.elapsedRealtime();
                    updateConnection();
                    if (st != null) main.post(() -> listener.onStatus(st));
                }
            } catch (SocketTimeoutException ignored) {
            } catch (Exception e) {
                if (!running) break;
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    break;
                }
            }
            updateConnection();
        }
    }

    private void updateConnection() {
        final boolean c = lastReply != 0 && SystemClock.elapsedRealtime() - lastReply < TIMEOUT_MS;
        if (c != connected) {
            connected = c;
            main.post(() -> listener.onConnectionChanged(c));
        }
    }
}
