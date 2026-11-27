package com.halo.decomp;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/** Keep the process owning the live LAN roster and playlist bound to its foreground game. */
public final class LobbySessionService extends Service {
    private final IBinder binder = new Binder();

    @Override public IBinder onBind(Intent intent) { return binder; }
}
