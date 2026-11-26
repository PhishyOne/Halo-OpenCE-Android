package com.halo.decomp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Canonical, host-issued LAN match result. Not an authenticated public
 * leaderboard. Match identity is independent of names and career HC3 IDs. */
final class GroupRatingReceipt {
    static final int NEUTRAL=0, LOSS=1, DRAW=2, WIN=3, FORFEIT=4;
    static final int MAX_LINE=1024, MAX_PLAYERS=16;
    final String id, host;
    final long epoch;
    final int mode, ticks;
    final boolean abandoned;
    final List<Seat> seats;
    static final class Seat {
        final String id;
        final int team, outcome;
        Seat(String id,int team,int outcome) {
            this.id=id; this.team=team; this.outcome=outcome;
        }
        boolean opposes(Seat other) {
            return !id.equals(other.id) && (team<0 || team!=other.team);
        }
    }
    private GroupRatingReceipt(String id,String host,long epoch,int mode,int ticks,
                               boolean abandoned,List<Seat> seats) {
        this.id=id; this.host=host; this.epoch=epoch; this.mode=mode;
        this.ticks=ticks; this.abandoned=abandoned;
        seats.sort(Comparator.comparing(s->s.id));
        this.seats=Collections.unmodifiableList(seats);
    }
    static long number(String value,long max) throws IOException {
        if(value==null||!value.matches("0|[1-9][0-9]{0,12}"))
            throw new IOException("Bad group rating number");
        try {
            long n=Long.parseLong(value);
            if(n>max) throw new NumberFormatException();
            return n;
        } catch(NumberFormatException e) { throw new IOException("Rating number out of range",e); }
    }
    static GroupRatingReceipt parse(String row) throws IOException {
        if(row==null||row.length()>MAX_LINE||row.indexOf('\n')>=0||row.indexOf('\r')>=0)
            throw new IOException("Bad rating receipt length");
        String[] p=row.split("\\|",-1);
        if(p.length<10||!"GR1".equals(p[0])||!LanBoardProtocol.idValid(p[1])||
           !LanBoardProtocol.idValid(p[2])) throw new IOException("Bad rating receipt identity");
        long epoch=number(p[3],4102444800L);
        int mode=(int)number(p[4],5), ticks=(int)number(p[5],2592000),
            abandoned=(int)number(p[6],1), count=(int)number(p[7],MAX_PLAYERS);
        if(epoch<1500000000L||mode<1||count<2||p.length!=8+count)
            throw new IOException("Bad rating receipt match");
        List<Seat> seats=new ArrayList<>();
        Set<String> ids=new HashSet<>(), teams=new HashSet<>();
        int forfeits=0,winners=0,ffa=0;
        for(int i=0;i<count;i++) {
            String[] v=p[8+i].split(":",-1);
            if(v.length!=3||!LanBoardProtocol.idValid(v[0])||!ids.add(v[0]))
                throw new IOException("Duplicate/invalid rating player");
            int team="-1".equals(v[1])?-1:(int)number(v[1],15);
            int outcome=(int)number(v[2],4);
            if(team<0) ffa++; else teams.add(v[1]);
            if(outcome==FORFEIT) forfeits++;
            if(outcome==WIN||outcome==DRAW) winners++;
            if(abandoned==1 ? outcome!=NEUTRAL&&outcome!=FORFEIT : outcome==NEUTRAL)
                throw new IOException("Invalid rating outcome");
            seats.add(new Seat(v[0],team,outcome));
        }
        if(!ids.contains(p[2])||!(ffa==count||(ffa==0&&teams.size()>=2))||
           (abandoned==1?forfeits==0:winners==0))
            throw new IOException("Unusable group result");
        return new GroupRatingReceipt(p[1],p[2],epoch,mode,ticks,abandoned==1,seats);
    }
    String row() {
        StringBuilder r=new StringBuilder("GR1|").append(id).append('|').append(host)
            .append('|').append(epoch).append('|').append(mode).append('|').append(ticks)
            .append('|').append(abandoned?1:0).append('|').append(seats.size());
        for(Seat s:seats) r.append('|').append(s.id).append(':').append(s.team).append(':').append(s.outcome);
        return r.toString();
    }
    Seat seat(String id) {
        for(Seat s:seats) if(s.id.equals(id)) return s;
        return null;
    }
}
