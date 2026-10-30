package nb.pingpong.ping;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Looks for a newer release on the GitHub repository that built this app (the repository name is baked in at build
 * time from GITHUB_REPOSITORY, so forks check their own releases), picks the file that matches this device's CPU
 * architecture, downloads it and hands it to Android's installer.
 */
final class UpdateChecker {
    static final String APK_PREFIX = "Ping-Phone";
    private static final long CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000;
    private static final long MAX_DOWNLOAD = 400L * 1024 * 1024;
    private static final String PREFS = "UpdatePrefs";

    static final class Update {
        String tag;
        String version;
        String assetName;
        String url;
        boolean zip;
        long size;
    }

    interface Progress {
        void onProgress(long done, long total);
    }

    enum InstallResult {STARTED, NEEDS_PERMISSION, FAILED}

    private UpdateChecker() {
    }

    // -------------------------------------------------------------- settings

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** False for local builds (no repository known) and after the user chose "never show again". */
    static boolean enabled(Context c) {
        return !BuildConfig.UPDATE_REPO.isEmpty() && !prefs(c).getBoolean("never", false);
    }

    static boolean due(Context c) {
        return System.currentTimeMillis() - prefs(c).getLong("last_ok", 0) > CHECK_INTERVAL_MS;
    }

    static void markChecked(Context c) {
        prefs(c).edit().putLong("last_ok", System.currentTimeMillis()).apply();
    }

    static void neverShowAgain(Context c) {
        prefs(c).edit().putBoolean("never", true).apply();
    }

    // ----------------------------------------------------------------- check

    /** Returns the newer release for this device, or null if this app is up to date. Throws if GitHub can't be reached. */
    static Update check(Context c) throws IOException {
        String json = readText("https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/releases/latest");
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        if (flag(o, "draft") || flag(o, "prerelease")) return null;
        String tag = o.has("tag_name") && !o.get("tag_name").isJsonNull() ? o.get("tag_name").getAsString() : "";
        if (tag.isEmpty() || !UpdateLogic.isNewer(BuildConfig.VERSION_NAME, tag)) {
            deleteDownloads(c);
            return null;
        }
        JsonArray assets = o.has("assets") && o.get("assets").isJsonArray() ? o.getAsJsonArray("assets") : new JsonArray();
        List<String> names = new ArrayList<>();
        for (JsonElement e : assets) names.add(str(e.getAsJsonObject(), "name"));
        List<String> abis = Arrays.asList(Build.SUPPORTED_ABIS);

        boolean zip = false;
        String name = UpdateLogic.pick(names, APK_PREFIX, abis, ".apk");
        if (name == null) {
            name = UpdateLogic.pick(names, APK_PREFIX, abis, ".zip");
            zip = name != null;
        }
        if (name == null) return null; // a release without a file for this app/device
        for (JsonElement e : assets) {
            JsonObject a = e.getAsJsonObject();
            if (name.equals(str(a, "name"))) {
                String url = str(a, "browser_download_url");
                if (!url.startsWith("https://")) return null;
                Update u = new Update();
                u.tag = tag;
                u.version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
                u.assetName = name;
                u.url = url;
                u.zip = zip;
                u.size = a.has("size") && !a.get("size").isJsonNull() ? a.get("size").getAsLong() : 0;
                return u;
            }
        }
        return null;
    }

    private static boolean flag(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() && o.get(k).getAsBoolean();
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    // -------------------------------------------------------------- download

    private static File dir(Context c) {
        File d = new File(c.getCacheDir(), "updates");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static void deleteDownloads(Context c) {
        File[] files = new File(c.getCacheDir(), "updates").listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    /** Downloads (and, for a zip, unpacks) the update and returns the APK. Reuses a finished earlier download. */
    static File download(Context c, Update u, Progress progress) throws IOException {
        File dir = dir(c);
        File apk = new File(dir, "update-" + u.version.replaceAll("[^A-Za-z0-9._-]", "_") + ".apk");
        if (apk.exists() && validApk(c, apk)) return apk;
        deleteDownloads(c);

        File raw = new File(dir, u.zip ? "download.zip" : "download.part");
        try (Body b = openBody(u.url, "application/octet-stream"); OutputStream out = new FileOutputStream(raw)) {
            long total = b.length > 0 ? b.length : u.size;
            long done = 0, lastReport = 0;
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = b.in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (done > MAX_DOWNLOAD) throw new IOException("The update file is too large");
                if (progress != null && done - lastReport > 128 * 1024) {
                    lastReport = done;
                    progress.onProgress(done, total);
                }
            }
            if (progress != null) progress.onProgress(done, total);
        }

        if (u.zip) {
            extractApk(raw, apk);
            //noinspection ResultOfMethodCallIgnored
            raw.delete();
        } else if (!raw.renameTo(apk)) {
            throw new IOException("Couldn't save the update");
        }
        if (!validApk(c, apk)) {
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
            throw new IOException("The downloaded file isn't a valid update for this app");
        }
        return apk;
    }

    private static void extractApk(File zip, File apk) throws IOException {
        try (ZipFile zf = new ZipFile(zip)) {
            List<String> names = new ArrayList<>();
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.isDirectory()) names.add(e.getName());
            }
            // match on the file name only, whatever folder it sits in
            List<String> base = new ArrayList<>();
            for (String n : names) base.add(n.substring(n.lastIndexOf('/') + 1));
            String want = UpdateLogic.pick(base, APK_PREFIX, Arrays.asList(Build.SUPPORTED_ABIS), ".apk");
            if (want == null) want = UpdateLogic.pick(base, "", Arrays.asList(Build.SUPPORTED_ABIS), ".apk");
            if (want == null) throw new IOException("No APK for this device in the download");
            ZipEntry entry = zf.getEntry(names.get(base.indexOf(want)));
            try (InputStream in = zf.getInputStream(entry); OutputStream out = new FileOutputStream(apk)) {
                byte[] buf = new byte[64 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                    if (total > MAX_DOWNLOAD) throw new IOException("The update file is too large");
                }
            }
        }
    }

    /** Must be a readable APK that belongs to this very app. (Android itself checks the signing key on install.) */
    private static boolean validApk(Context c, File apk) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            return pi != null && c.getPackageName().equals(pi.packageName);
        } catch (Exception e) {
            return false;
        }
    }

    // --------------------------------------------------------------- install

    static InstallResult install(Activity a, File apk) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
                try {
                    a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + a.getPackageName())));
                } catch (Exception ignored) {
                    // no such settings page on this device: the caller explains what to enable
                }
                return InstallResult.NEEDS_PERMISSION;
            }
            Uri uri = FileProvider.getUriForFile(a, a.getPackageName() + ".updates", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
            return InstallResult.STARTED;
        } catch (Exception e) {
            return InstallResult.FAILED;
        }
    }

    // ------------------------------------------------------------------ HTTP

    private static String readText(String url) throws IOException {
        try (Body b = openBody(url, "application/vnd.github+json")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = b.in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 4 * 1024 * 1024) throw new IOException("Response too large");
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // ---- HTTP BEGIN (the only part that differs between the apps)
    private static final class Body implements Closeable {
        InputStream in;
        long length;
        HttpURLConnection conn;

        @Override
        public void close() {
            try {
                if (in != null) in.close();
            } catch (IOException ignored) {
            }
            if (conn != null) conn.disconnect();
        }
    }

    private static Body openBody(String url, String accept) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "PingPong-Updater");
        c.setRequestProperty("Accept", accept);
        int code = c.getResponseCode();
        if (code >= 400) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        Body b = new Body();
        b.conn = c;
        b.in = c.getInputStream();
        b.length = c.getContentLength();
        return b;
    }
    // ---- HTTP END
}
