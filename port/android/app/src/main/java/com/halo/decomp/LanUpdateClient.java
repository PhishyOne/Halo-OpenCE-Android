package com.halo.decomp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

/** Joins a known LAN host: compare before game launch; offer a user-approved
 * download and Android package install. Never install silently, and never
 * trust the host's SHA alone: verify the new APK's actual installed signer. */
final class LanUpdateClient {
    private static final String LOG = "HaloLanUpdate";
    private static final AtomicBoolean BUSY = new AtomicBoolean();

    private LanUpdateClient() { }

    /** The optional manual check and automatic pre-join check share one path.
     * When there's no update listener (old builds), pre-join continues as
     * before; protocol compatibility remains the game's separate gate.
     * On errors or a declined update, the Play button is reenabled. */
    static void check(Activity activity, String host, boolean manual,
            Runnable continuePlay, Runnable reenablePlay) {
        if (LanUpdateProtocol.privateAddress(host) == null) {
            if (reenablePlay != null)
                reenablePlay.run();
            new AlertDialog.Builder(activity)
                .setTitle("Halo LAN updates")
                .setMessage("Enter your host's private IPv4 address first.")
                .setPositiveButton("OK", null).show();
            return;
        }
        if (!BUSY.compareAndSet(false, true)) {
            if (reenablePlay != null)
                reenablePlay.run();
            return;
        }
        new Thread(() -> {
            LanUpdateProtocol.Manifest offer = null;
            IOException failure = null;
            try { offer = LanUpdateSocket.fetchManifest(host); }
            catch (IOException error) { failure = error; }
            LanUpdateProtocol.Manifest found = offer;
            IOException exception = failure;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) {
                    complete(reenablePlay);
                    return;
                }
                if (exception != null) {
                    complete(reenablePlay);
                    if (manual) {
                        message(activity, "Host update unavailable",
                            "The host isn't offering a Halo LAN update. It may be " +
                            "running an older build, not hosting, or unreachable.");
                    } else if (continuePlay != null) {
                        continuePlay.run(); // old peers still use normal network checks
                    }
                    Log.i(LOG, "No update endpoint: " + exception);
                    return;
                }
                if (!activity.getPackageName().equals(found.packageName)) {
                    complete(reenablePlay);
                    message(activity, "Different Halo package",
                        "This host offers a different Halo app package. " +
                        "It cannot update your installed game.");
                    return;
                }
                int installed;
                try {
                    installed = (int)activity.getPackageManager()
                        .getPackageInfo(activity.getPackageName(), 0).getLongVersionCode();
                } catch (Exception e) {
                    complete(reenablePlay);
                    message(activity, "Can't check version", "Couldn't read this Halo version.");
                    return;
                }
                if (found.version < installed) {
                    complete(reenablePlay);
                    message(activity, "Host has an older Halo",
                        "Your Halo is v" + installed + ", but the host is v" +
                        found.version + ". Have the host update first.");
                    return;
                }
                if (found.version == installed) {
                    complete(reenablePlay);
                    if (manual)
                        message(activity, "Halo up to date",
                            "You and the host both have Halo v" + installed + ".");
                    else if (continuePlay != null)
                        continuePlay.run();
                    return;
                }
                new AlertDialog.Builder(activity)
                    .setTitle("Halo LAN update available")
                    .setMessage("Your Halo is v" + installed + ". The host has v" +
                        found.version + " (" + (found.size + 1048575) / 1048576 +
                        " MB). Download directly from their phone over local Wi-Fi? " +
                        "Android will ask you to approve installation. " +
                        "Your maps and settings should be preserved.")
                    .setCancelable(false)
                    .setPositiveButton("Download update", (dialog, which) ->
                        download(activity, host, found, reenablePlay))
                    .setNegativeButton("Not now", (dialog, which) ->
                        complete(reenablePlay))
                    .show();
            });
        }, "Halo-LAN-update-check").start();
    }

    private static void complete(Runnable reset) {
        BUSY.set(false);
        if (reset != null)
            reset.run();
    }

    private static void message(Activity activity, String title, String body) {
        if (activity.isFinishing()) return;
        new AlertDialog.Builder(activity).setTitle(title).setMessage(body)
            .setPositiveButton("OK", null).show();
    }

    private static void verifyPackage(Activity activity, File apk,
            LanUpdateProtocol.Manifest offer) throws IOException {
        PackageManager pm = activity.getPackageManager();
        int flags = PackageManager.GET_SIGNING_CERTIFICATES;
        PackageInfo candidate = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        PackageInfo current;
        try { current = pm.getPackageInfo(activity.getPackageName(), flags); }
        catch (PackageManager.NameNotFoundException e) {
            throw new IOException("Installed Halo package was not found", e);
        }
        if (candidate == null || !activity.getPackageName().equals(candidate.packageName) ||
            candidate.getLongVersionCode() != offer.version ||
            current.getLongVersionCode() >= candidate.getLongVersionCode())
            throw new IOException("The downloaded APK is not a newer version of this Halo");
        if (current.signingInfo == null || candidate.signingInfo == null)
            throw new IOException("APK signing certificate was unavailable");

        Signature[] oldSigners = current.signingInfo.getApkContentsSigners();
        Signature[] newSigners = candidate.signingInfo.getApkContentsSigners();
        if (oldSigners == null || newSigners == null || oldSigners.length == 0 ||
            oldSigners.length != newSigners.length)
            throw new IOException("APK signing certificate mismatch");
        for (int i = 0; i < oldSigners.length; i++)
            if (!MessageDigest.isEqual(oldSigners[i].toByteArray(),
                    newSigners[i].toByteArray()))
                throw new IOException("APK signing certificate mismatch");
    }

    private static void download(Activity activity, String host,
            LanUpdateProtocol.Manifest offer, Runnable reset) {
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(20 * activity.getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, pad);
        TextView label = new TextView(activity);
        ProgressBar progress = new ProgressBar(activity, null,
            android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        label.setText("Downloading from Halo host...");
        container.addView(label);
        container.addView(progress);
        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle("Halo LAN update")
            .setView(container)
            .setCancelable(false).show();

        new Thread(() -> {
            File folder = new File(activity.getCacheDir(), UpdateProvider.DIRECTORY);
            File partial = new File(folder, "lan-update.pending.apk");
            File apk = new File(folder, UpdateProvider.APK);
            try {
                if (!folder.isDirectory() && !folder.mkdirs())
                    throw new IOException("Couldn't prepare APK download folder");
                LanUpdateSocket.download(host, offer, partial,
                    (received, total) -> activity.runOnUiThread(() -> {
                        if (!activity.isFinishing()) {
                            progress.setProgress((int)(received * 1000 / total));
                            label.setText("From host: " + (received / 1048576) +
                                " / " + (total / 1048576) + " MB");
                        }
                    }));
                verifyPackage(activity, partial, offer);
                try {
                    Files.move(partial.toPath(), apk.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException crossFs) {
                    Files.move(partial.toPath(), apk.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                }
                activity.runOnUiThread(() -> {
                    dialog.dismiss();
                    complete(reset);
                    if (!activity.isFinishing()) {
                        try {
                            Updater.install(activity); // existing, read-only provider
                        } catch (RuntimeException installerMissing) {
                            message(activity, "Android installer unavailable",
                                "Open Halo again after checking Android app installation settings.");
                        }
                    }
                });
            } catch (Exception problem) {
                Log.w(LOG, "LAN update rejected", problem);
                partial.delete();
                activity.runOnUiThread(() -> {
                    dialog.dismiss();
                    complete(reset);
                    message(activity, "Halo update failed",
                        problem.getMessage() == null ? "The update couldn't be verified." :
                        problem.getMessage());
                });
            }
        }, "Halo-LAN-update-download").start();
    }
}
