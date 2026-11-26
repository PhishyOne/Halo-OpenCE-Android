package com.halo.decomp;

/** Pure sharing policy and persistent format, independent of Android.
 * Video choice is independent, while actual serving also requires
 * local career discovery to be enabled. Invalid data fails closed. */
final class LanShareState {
    final boolean career, videoAllowed, reels;
    LanShareState(boolean career, boolean videoAllowed) {
        this.career = career;
        this.videoAllowed = videoAllowed;
        this.reels = career && videoAllowed;
    }
    static LanShareState defaults(boolean legacyCareer) {
        return new LanShareState(legacyCareer, true);
    }
    static LanShareState decode(String value) {
        if ("LS1|1|1\n".equals(value)) return new LanShareState(true, true);
        if ("LS1|1|0\n".equals(value)) return new LanShareState(true, false);
        if ("LS1|0|1\n".equals(value)) return new LanShareState(false, true);
        return new LanShareState(false, false);
    }
    String encode() {
        return "LS1|" + (career ? "1" : "0") + "|" + (videoAllowed ? "1" : "0") + "\n";
    }
    LanShareState withCareer(boolean enabled) { return new LanShareState(enabled, videoAllowed); }
    LanShareState withVideos(boolean enabled) { return new LanShareState(career, enabled); }
}
