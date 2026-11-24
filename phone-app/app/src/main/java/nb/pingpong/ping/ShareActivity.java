package nb.pingpong.ping;

import android.app.Activity;
import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
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
 * Receives something shared from another app: a link (sent to the TV as is) or video/subtitle files
 * (served from this phone by {@link CastService}). One TV app found: uses it directly.
 * Several: asks which one (most used first, then most recent).
 */
public class ShareActivity extends Activity {
    private static final long SETTLE_MS = 1200;   // wait this long after the last TV app appears
    private static final long GIVE_UP_MS = 4000;  // no TV app at all

    private static final int REQ_NOTIFICATIONS = 71;

    private String sharedUrl;
    private final List<CastService.Play> plays = new ArrayList<>();
    private final List<CastService.Item> opened = new ArrayList<>();
    private boolean fileMode = false;
    private boolean handedOff = false;
    private boolean waitingPermission = false;
    private DeviceDiscovery discovery;
    private UsageStore usage;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<DeviceInfo> devices = new ArrayList<>();
    private boolean decided = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String action = intent.getAction();
        boolean single = Intent.ACTION_SEND.equals(action);
        if (Intent.ACTION_VIEW.equals(action)) {
            // Tapping a magnet link in a browser.
            String url = intent.getDataString();
            if (url == null) url = intent.getStringExtra(Intent.EXTRA_TEXT);
            sharedUrl = url == null || !url.toLowerCase(java.util.Locale.ROOT).startsWith("magnet:") ? null : url;
            if (sharedUrl == null) {
                Toast.makeText(this, "Not a magnet link", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
        } else if (single || Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            List<Uri> uris = streamUris(intent);
            if (!uris.isEmpty()) {
                fileMode = true;
                if (!openFiles(uris)) {
                    finish();
                    return;
                }
                askNotificationPermission();
            } else {
                if (!single || !isText(intent.getType())) {
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
            }
        } else {
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

    private static boolean isText(String mime) {
        return mime == null || mime.startsWith("text/");
    }

    /** Magnets first: a magnet contains tracker URLs, so looking for http(s) first would send the tracker. */
    private static String extractUrl(String text) {
        if (text == null) return null;
        Matcher mag = Pattern.compile("magnet:\\?[^\\s<>\"']+").matcher(text);
        if (mag.find()) return mag.group();
        Matcher m = Pattern.compile("https?://\\S+").matcher(text);
        if (m.find()) return m.group();
        String t = text.trim();
        return t.isEmpty() ? null : t;
    }

    private final Runnable decide = new Runnable() {
        @Override
        public void run() {
            if (decided) return;
            if (waitingPermission) { // let the notification permission dialog finish first
                handler.postDelayed(this, 300);
                return;
            }
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
        if (fileMode) {
            if (plays.size() > 1) showPlaylist(device);
            else startCasting(device, 0);
            return;
        }
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

    // ------------------------------------------------------------------ files

    private void showPlaylist(final DeviceInfo device) {
        String[] names = new String[plays.size()];
        for (int i = 0; i < names.length; i++) {
            int subs = plays.get(i).subs.size();
            names[i] = (i + 1) + ". " + plays.get(i).name()
                    + (subs == 0 ? "" : "  ·  " + subs + " subtitle" + (subs == 1 ? "" : "s"));
        }
        new AlertDialog.Builder(this)
                .setTitle("Choose a video to play")
                .setItems(names, (dialog, which) -> startCasting(device, which))
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    private void startCasting(DeviceInfo device, int index) {
        try {
            handedOff = true; // from here on the service owns (and closes) the files
            CastService.start(this, new CastService.Pending(device, plays, index));
        } catch (Exception e) {
            handedOff = false;
            Toast.makeText(this, "Couldn't start sharing: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Toast.makeText(this, "Sharing to " + device.serviceName, Toast.LENGTH_SHORT).show();
        Intent i = new Intent(this, ControlActivity.class);
        i.putExtra("device", device);
        startActivity(i);
        finish();
    }

    @SuppressWarnings("deprecation")
    private List<Uri> streamUris(Intent intent) {
        List<Uri> out = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> l = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) for (Uri u : l) if (u != null) out.add(u);
        } else {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) out.add(u);
        }
        if (out.isEmpty()) {
            ClipData cd = intent.getClipData();
            if (cd != null) {
                for (int i = 0; i < cd.getItemCount(); i++) {
                    Uri u = cd.getItemAt(i).getUri();
                    if (u != null) out.add(u);
                }
            }
        }
        return out;
    }

    /**
     * Opens every video and every subtitle of the share while the temporary read permission is still valid.
     * An open file stays readable after this activity ends. Subtitles are grouped with the video whose name
     * they match; each subtitle is attached to only one video.
     */
    private boolean openFiles(List<Uri> uris) {
        List<CastService.Item> videos = new ArrayList<>();
        List<CastService.Item> subs = new ArrayList<>();
        boolean sawVideo = false;
        for (Uri u : uris) {
            String name = displayName(u);
            String mime = getContentResolver().getType(u);
            int kind = MediaFiles.kind(name, mime);
            if (kind == 1) {
                sawVideo = true;
                CastService.Item v = open(u, name, mime);
                if (v == null) break;
                opened.add(v);
                videos.add(v);
            } else if (kind == 2) {
                CastService.Item s = open(u, name, mime);
                if (s != null) {
                    opened.add(s);
                    subs.add(s);
                }
            }
        }
        if (videos.isEmpty()) {
            Toast.makeText(this, sawVideo ? "Couldn't open the video file" : "No video file found in the share",
                    Toast.LENGTH_LONG).show();
            return false;
        }

        boolean[] used = new boolean[subs.size()];
        for (CastService.Item v : videos) {
            List<CastService.Item> matched = new ArrayList<>();
            for (int i = 0; i < subs.size(); i++) {
                if (!used[i] && MediaFiles.matches(v.name, subs.get(i).name)) {
                    matched.add(subs.get(i));
                    used[i] = true;
                }
            }
            plays.add(new CastService.Play(v, matched));
        }
        for (int i = 0; i < subs.size(); i++) if (!used[i]) subs.get(i).close();
        return true;
    }

    private CastService.Item open(Uri u, String name, String mime) {
        try {
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(u, "r");
            if (pfd != null) return new CastService.Item(name, mime, pfd, null);
        } catch (Exception ignored) {
            // some providers only offer a stream
        }
        try {
            java.io.InputStream in = getContentResolver().openInputStream(u);
            if (in != null) return new CastService.Item(name, mime, null, in);
        } catch (Exception ignored) {
        }
        return null;
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                String n = c.getString(0);
                if (n != null && !n.trim().isEmpty()) return n.trim();
            }
        } catch (Exception ignored) {
        }
        String seg = u.getLastPathSegment();
        return seg == null || seg.isEmpty() ? "video" : seg;
    }

    /** Android 13+ hides the "sharing" notification until this is granted; the sharing itself works either way. */
    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        SharedPreferences p = getSharedPreferences("cast", MODE_PRIVATE);
        if (p.getBoolean("asked_notifications", false)) return;
        p.edit().putBoolean("asked_notifications", true).apply();
        waitingPermission = true;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) waitingPermission = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (discovery != null) discovery.stop();
        if (fileMode && !handedOff) { // cancelled or no TV found: give the files back
            for (CastService.Item it : opened) it.close();
        }
    }
}
