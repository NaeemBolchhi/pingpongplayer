package nb.pingpong.ping;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Receives a link shared from another app. One TV app found: forwards directly.
 * Several: asks which one (most used first, then most recent).
 */
public class ShareActivity extends Activity {
    private static final long SETTLE_MS = 1200;   // wait this long after the last TV app appears
    private static final long GIVE_UP_MS = 4000;  // no TV app at all

    private String sharedUrl;
    private DeviceDiscovery discovery;
    private UsageStore usage;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<DeviceInfo> devices = new ArrayList<>();
    private boolean decided = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        if (!Intent.ACTION_SEND.equals(intent.getAction()) || !"text/plain".equals(intent.getType())) {
            finish();
            return;
        }
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) text = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        sharedUrl = extractUrl(text);
        if (sharedUrl == null) {
            Toast.makeText(this, "No link found in shared text", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        usage = new UsageStore(this);
        Toast.makeText(this, "Looking for TV apps…", Toast.LENGTH_SHORT).show();
        discovery = new DeviceDiscovery(this, found -> {
            if (decided) return;
            devices.clear();
            devices.addAll(found);
            handler.removeCallbacks(decide);
            if (!devices.isEmpty()) handler.postDelayed(decide, SETTLE_MS);
        });
        discovery.start();
        handler.postDelayed(decide, GIVE_UP_MS);
    }

    private static String extractUrl(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("https?://\\S+").matcher(text);
        if (m.find()) return m.group();
        String t = text.trim();
        return t.isEmpty() ? null : t;
    }

    private final Runnable decide = new Runnable() {
        @Override
        public void run() {
            if (decided) return;
            decided = true;
            discovery.stop();
            if (devices.isEmpty()) {
                Toast.makeText(ShareActivity.this, "No TV apps found", Toast.LENGTH_LONG).show();
                finish();
            } else if (devices.size() == 1) {
                sendToDevice(devices.get(0));
            } else {
                showDeviceSelection();
            }
        }
    };

    private void showDeviceSelection() {
        usage.sort(devices);
        String[] names = new String[devices.size()];
        for (int i = 0; i < names.length; i++) names[i] = devices.get(i).serviceName;
        new AlertDialog.Builder(this)
                .setTitle("Select TV to cast to")
                .setItems(names, (dialog, which) -> sendToDevice(devices.get(which)))
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    private void sendToDevice(final DeviceInfo device) {
        usage.record(device.serviceName);
        new Thread(() -> {
            boolean ok = true;
            try (DatagramSocket socket = new DatagramSocket()) {
                byte[] data = ("PLAY:" + sharedUrl).getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(data, data.length, InetAddress.getByName(device.host), device.port));
            } catch (Exception e) {
                ok = false;
            }
            final boolean sent = ok;
            runOnUiThread(() -> {
                if (sent) {
                    Toast.makeText(this, "Sent to " + device.serviceName, Toast.LENGTH_SHORT).show();
                    Intent i = new Intent(this, ControlActivity.class);
                    i.putExtra("device", device);
                    startActivity(i);
                } else {
                    Toast.makeText(this, "Failed to send", Toast.LENGTH_SHORT).show();
                }
                finish();
            });
        }, "share-send").start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (discovery != null) discovery.stop();
    }
}
