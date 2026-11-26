package com.halo.decomp;

import android.app.Activity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.util.List;

/** A reusable UI coordinator: no IP typing in the common one-host case. */
final class LanHostPicker {
    private final Activity activity;
    private final EditText address;
    private final TextView status;
    private final Button refresh;
    private final View manual;
    private boolean searching;
    private Runnable playWhenFound;

    LanHostPicker(Activity activity, EditText address, TextView status,
                  Button refresh, View manual) {
        this.activity = activity;
        this.address = address;
        this.status = status;
        this.refresh = refresh;
        this.manual = manual;
    }

    boolean isSearching() { return searching; }

    void search(boolean startGameAfterSelection, Runnable play) {
        if (startGameAfterSelection) playWhenFound = play;
        if (searching) return;
        searching = true;
        refresh.setEnabled(false);
        status.setText("Looking for Halo hosts on this Wi-Fi / hotspot...");
        final String previous = address.getText().toString().trim();
        LanDiscoveryClient.search(activity, found -> {
            searching = false;
            refresh.setEnabled(true);
            if (found.isEmpty()) {
                playWhenFound = null;
                manual.setVisibility(View.VISIBLE);
                status.setText("No Halo host answered. Check the hotspot and that " +
                    "the host is running v36+. Manual IP is available below.");
            } else if (found.size() == 1) {
                String current = found.get(0);
                // Do not overwrite a new manual edit made during discovery.
                if (!address.getText().toString().trim().equals(previous)) {
                    playWhenFound = null;
                    status.setText("Host found at " + current +
                        ". Your manual address was left unchanged.");
                    return;
                }
                address.setText(current);
                status.setText("Host found: " + current + "  •  Ready to join");
                runPendingPlay();
            } else {
                String[] labels = found.toArray(new String[0]);
                status.setText(found.size() + " Halo hosts found. Choose one.");
                new HaloDialog.Builder(activity)
                    .setTitle("Choose a Halo LAN host")
                    .setItems(labels, (dialog, choice) -> {
                        address.setText(labels[choice]);
                        status.setText("Selected host: " + labels[choice]);
                        runPendingPlay();
                    })
                    .setOnCancelListener(dialog -> playWhenFound = null)
                    .show();
            }
        });
    }

    private void runPendingPlay() {
        Runnable next = playWhenFound;
        playWhenFound = null;
        if (next != null) next.run();
    }
}
