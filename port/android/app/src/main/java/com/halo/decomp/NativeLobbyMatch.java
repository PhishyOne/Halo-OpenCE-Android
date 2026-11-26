package com.halo.decomp;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Set;

/** One immutable, local-process launch. The native player name has 11 UTF-16
 * units; the full lobby name and career identity are never rewritten. */
final class NativeLobbyMatch {
    private static final String HEX="0123456789abcdef";
    // Native armor palette (player_profile.c), indexed by the launcher's palette.
    private static final int[] ARMOR={0,1,2,3,4,5,6,11,8,9,7,14};
    private NativeLobbyMatch() {}

    static void checkMap(File root,String map) throws IOException {
        MatchWorkshop.checkedMap(map);
        boolean custom=map.startsWith("ce:");
        String id=custom?map.substring(3):map;
        File dir=new File(root,custom?"custom_maps":"maps");
        File file=new File(dir,id+".map");
        if(root==null||Files.isSymbolicLink(dir.toPath())||Files.isSymbolicLink(file.toPath())||
                !file.isFile()||file.length()<=2048)
            throw new IOException("Missing "+id+". Use Tools to receive or repair the map.");
        if(custom)CustomMapImporter.validate(file,file.getName());
        byte[] header=new byte[2048];
        try(RandomAccessFile input=new RandomAccessFile(file,"r")){input.readFully(header);}
        int end=32;
        while(end<64&&header[end]!=0)end++;
        String scenario=new String(header,32,end-32,StandardCharsets.US_ASCII);
        java.nio.ByteBuffer bytes=java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        if(bytes.getInt(0)!=0x68656164||bytes.getInt(2044)!=0x666f6f74||
                bytes.getInt(4)!=(custom?609:5)||bytes.getShort(96)!=1||!scenario.equalsIgnoreCase(id))
            throw new IOException(id+" has an incompatible multiplayer map header.");
    }

    private static String shorten(String value,int units) {
        int end=Math.min(value.length(),units);
        if(end>0&&Character.isHighSurrogate(value.charAt(end-1)))end--;
        return value.substring(0,end);
    }
    private static String uniqueName(String name,Set<String> used) {
        String candidate=shorten(name,11);
        for(int n=2;!used.add(candidate.toLowerCase(Locale.ROOT));n++){
            String suffix="~"+n;
            candidate=shorten(name,11-suffix.length())+suffix;
        }
        return candidate;
    }
    static String returnMessage(File directory,String round){
        File receipt=new File(directory,"lobby-return.txt");
        if(receipt.length()>0&&receipt.length()<512){
            try(java.io.BufferedReader reader=Files.newBufferedReader(receipt.toPath(),StandardCharsets.UTF_8)){
                String tag=reader.readLine(),message=reader.readLine();
                if(tag!=null&&tag.length()==13&&round.replace("-","").startsWith(tag)&&message!=null&&message.length()<=150)
                    return message;
            }catch(IOException ignored){}
        }
        return "Back in the lobby.";
    }

    static String[] arguments(LobbyModel.Snapshot snapshot,String local,String peer,List<String> guestAddresses){
        LobbyModel.StartReview review=LobbyModel.StartReview.selected(snapshot);
        if(!review.blocker.isEmpty())throw new IllegalArgumentException(review.blocker);
        boolean hosting=snapshot.me().host;
        if(LanUpdateProtocol.privateAddress(local)==null||(!hosting&&LanUpdateProtocol.privateAddress(peer)==null))
            throw new IllegalArgumentException("The lobby host needs a private LAN address.");
        MatchWorkshop.Entry e=review.entry;
        Set<String> names=new HashSet<>();
        StringBuilder roster=new StringBuilder();
        int seat=-1;
        for(int i=0;i<review.members.size();i++){
            LobbyModel.Member member=review.members.get(i);
            if(member.host!=(i==0))throw new IllegalArgumentException("Invalid host seat");
            if(member.id.equals(snapshot.self))seat=i;
            if(i!=0)roster.append(',');
            int armor=ARMOR[member.color];
            roster.append(member.team==LobbyModel.NO_TEAM?'2':(char)('0'+member.team))
                .append(HEX.charAt(armor>>>4)).append(HEX.charAt(armor&15));
            String name=uniqueName(member.name,names);
            for(int j=0;j<name.length();j++){
                char c=name.charAt(j);
                for(int shift=12;shift>=0;shift-=4)roster.append(HEX.charAt((c>>>shift)&15));
            }
        }
        if(seat<0||(hosting!=(seat==0)))throw new IllegalArgumentException("Missing local seat");
        StringBuilder bindings=new StringBuilder();
        if(hosting){
            if(guestAddresses.size()!=review.members.size()-1)throw new IllegalArgumentException("Player connections changed.");
            bindings.append("00000000");
            Set<String> addresses=new HashSet<>();
            for(String address:guestAddresses){
                byte[] ip=LanUpdateProtocol.privateAddress(address);
                if(ip==null||!addresses.add(address))throw new IllegalArgumentException("Review duplicate or missing player connections.");
                for(byte octet:ip)bindings.append(HEX.charAt((octet&255)>>>4)).append(HEX.charAt(octet&15));
            }
        }
        Boolean radio=NativeMatchRules.radio(e.preset);
        return new String[]{"--launcher-lan",e.map,e.preset.base,NativeMatchRules.encode(e.preset),
            snapshot.round.replace("-","").substring(0,13),Integer.toString(seat),roster.toString(),
            Integer.toString(review.allocation.bots()),Integer.toString(review.allocation.red),
            MatchWorkshop.DIFFICULTIES[e.bots.difficulty].toLowerCase(Locale.ROOT),
            hosting?"":peer,Boolean.toString(e.killstreak),Boolean.toString(radio==null||radio),local,bindings.toString()};
    }
}
