package com.halo.decomp;

import java.util.Map;
import java.util.TreeMap;

/** Canonical, bounded launch rules for the native game-variant bridge.
 * Killstreak announcements use their existing separate config subsystem. */
final class NativeMatchRules {
    static final int MAX_BYTES=4096;
    private NativeMatchRules(){}
    static String encode(MatchWorkshop.Preset preset) {
        Map<String,Integer> rules=preset.overrides;
        Integer radar=rules.get("radar"),players=rules.get("radar_players");
        if(radar!=null&&players!=null&&(radar!=0)!=(players!=2))
            throw new IllegalArgumentException("Motion sensor and players on radar disagree.");
        if(Integer.valueOf(0).equals(rules.get("loadout"))&&
                (rules.containsKey("primary_weapon")||rules.containsKey("secondary_weapon")))
            throw new IllegalArgumentException("Select Custom loadout for explicit starting weapons.");
        for(String side:new String[]{"red","blue"}){
            if(Integer.valueOf(254).equals(rules.get(side+"_vehicle_set")))
                for(String type:new String[]{"warthogs","ghosts","scorpions","rocket_warthogs","banshees","gun_turrets"})
                    if(rules.containsKey(side+"_"+type))
                        throw new IllegalArgumentException("Choose Custom vehicle counts for "+side+".");
        }
        StringBuilder result=new StringBuilder();
        for(Map.Entry<String,Integer> e:new TreeMap<>(preset.overrides).entrySet()){
            if(e.getKey().equals("killstreak_radio"))continue;
            MatchWorkshop.Rule rule=MatchWorkshop.rule(e.getKey());
            if(rule==null||!rule.available(preset.base)||!rule.accepts(e.getValue()))
                throw new IllegalArgumentException("Invalid native rule "+e.getKey());
            if(result.length()>0)result.append(';');
            result.append(e.getKey()).append('=').append(e.getValue());
        }
        if(result.length()>MAX_BYTES)throw new IllegalArgumentException("Match rules exceed native limit");
        return result.toString();
    }
    static Boolean radio(MatchWorkshop.Preset preset){
        Integer value=preset.overrides.get("killstreak_radio");
        return value==null?null:value!=0;
    }
}
