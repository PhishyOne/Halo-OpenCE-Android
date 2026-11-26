package com.halo.decomp;

import java.nio.charset.StandardCharsets;

final class LobbyName {
    private LobbyName() {}
    static String checked(String raw) {
        String name=raw==null?"":raw.trim();
        if(name.isEmpty()||name.codePointCount(0,name.length())>32||
                name.codePoints().anyMatch(c->Character.isISOControl(c)||c==0x2028||c==0x2029)||
                !StandardCharsets.UTF_8.newEncoder().canEncode(name))
            throw new IllegalArgumentException("Use a lobby name of 1–32 printable characters.");
        return name;
    }
}
