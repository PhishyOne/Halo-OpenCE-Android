package com.halo.decomp;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;

/** Random, app-private, persistent pseudonymous leaderboard identity.
 * Names can change without resetting XP or producing duplicate players.
 * UUID is NOT an authenticated account and is not a secret signing key. */
final class LanBoardIdentity {
    private static final String PREFS = "halo_lan_board_identity_v1";
    private static final String KEY = "player_id";
    private static final String CAREER_PREFS = "career_profile_v1";
    private static final SecureRandom RNG = new SecureRandom();

    private LanBoardIdentity() { }

    static synchronized String id(Context context) throws IOException {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = prefs.getString(KEY, null);
        if (LanBoardProtocol.idValid(saved)) return saved;
        if (saved != null)
            throw new IOException("Player identity is damaged; no leaderboard sync");
        byte[] random = new byte[16];
        RNG.nextBytes(random);
        StringBuilder hex = new StringBuilder(32);
        for (byte b : random)
            hex.append(Character.forDigit((b >> 4) & 15, 16))
                .append(Character.forDigit(b & 15, 16));
        String generated = hex.toString();
        if (!prefs.edit().putString(KEY, generated).commit())
            throw new IOException("Could not save permanent leaderboard identity");
        return generated;
    }

    static String name(Context context) {
        return LanBoardProtocol.safeName(context.getSharedPreferences(
            CAREER_PREFS, Context.MODE_PRIVATE).getString("name", "Spartan"));
    }

    static LanBoardProtocol.Entry card(Context context, File root)
            throws IOException {
        if (root == null || !root.isDirectory())
            throw new IOException("Halo app data unavailable");
        return LanBoardStore.current(id(context), name(context),
            CareerRecords.load(root), System.currentTimeMillis());
    }
}
