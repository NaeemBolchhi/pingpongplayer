package nb.pingpong.ping;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;

/** Background update check plus the "Update available" prompt, in the app's own Material dialog style. */
final class UpdateDialog {
    private static boolean started; // one check per app launch

    private UpdateDialog() {
    }

    static void checkOnce(final Activity a) {
        if (started || !UpdateChecker.enabled(a) || !UpdateChecker.due(a)) return;
        started = true;
        new Thread(() -> {
            try {
                final UpdateChecker.Update u = UpdateChecker.check(a);
                if (u == null) {
                    UpdateChecker.markChecked(a);
                    return;
                }
                a.runOnUiThread(() -> {
                    if (!a.isFinishing() && !a.isDestroyed()) show(a, u);
                });
            } catch (Exception ignored) {
                // offline or GitHub unreachable: try again next launch
            }
        }, "update-check").start();
    }

    private static void show(final Activity a, final UpdateChecker.Update u) {
        View v = LayoutInflater.from(a).inflate(R.layout.dialog_update, null);
        final TextView message = v.findViewById(R.id.update_message);
        final TextView status = v.findViewById(R.id.update_status);
        final LinearProgressIndicator bar = v.findViewById(R.id.update_progress);
        String size = UpdateLogic.humanSize(u.size);
        message.setText("Ping! " + u.version + " is available (you have " + BuildConfig.VERSION_NAME + ")."
                + (size.isEmpty() ? "" : "\nDownload size: " + size));

        final AlertDialog d = new MaterialAlertDialogBuilder(a)
                .setTitle("Update available")
                .setView(v)
                .setPositiveButton("Update", null) // set below so the dialog stays open while downloading
                .setNegativeButton("Not now", null)
                .setNeutralButton("Never show again", (dd, w) -> UpdateChecker.neverShowAgain(a))
                .create();
        d.show();

        final Button update = d.getButton(AlertDialog.BUTTON_POSITIVE);
        final Button later = d.getButton(AlertDialog.BUTTON_NEGATIVE);
        final Button never = d.getButton(AlertDialog.BUTTON_NEUTRAL);
        update.setOnClickListener(x -> {
            update.setEnabled(false);
            later.setEnabled(false);
            never.setEnabled(false);
            d.setCancelable(false);
            bar.setVisibility(View.VISIBLE);
            bar.setIndeterminate(true);
            status.setVisibility(View.VISIBLE);
            status.setText("Downloading…");
            new Thread(() -> {
                try {
                    final File apk = UpdateChecker.download(a, u, (done, total) -> a.runOnUiThread(() -> {
                        if (total > 0) {
                            bar.setIndeterminate(false);
                            bar.setProgressCompat((int) (done * 100 / total), true);
                            status.setText("Downloading… " + (done * 100 / total) + "%");
                        }
                    }));
                    a.runOnUiThread(() -> {
                        UpdateChecker.InstallResult r = UpdateChecker.install(a, apk);
                        if (r == UpdateChecker.InstallResult.STARTED) {
                            d.dismiss();
                            return;
                        }
                        finish(d, update, later, never, bar, status, r == UpdateChecker.InstallResult.NEEDS_PERMISSION
                                ? "Allow Ping! to install apps in the settings that just opened, then tap Update again."
                                : "Couldn't start the installer.");
                    });
                } catch (Exception e) {
                    a.runOnUiThread(() -> finish(d, update, later, never, bar, status,
                            "Download failed: " + (e.getMessage() == null ? "unknown error" : e.getMessage())));
                }
            }, "update-download").start();
        });
    }

    private static void finish(AlertDialog d, Button update, Button later, Button never, LinearProgressIndicator bar,
                               TextView status, String text) {
        bar.setVisibility(View.GONE);
        status.setText(text);
        status.setVisibility(View.VISIBLE);
        update.setEnabled(true);
        later.setEnabled(true);
        never.setEnabled(true);
        d.setCancelable(true);
    }
}
