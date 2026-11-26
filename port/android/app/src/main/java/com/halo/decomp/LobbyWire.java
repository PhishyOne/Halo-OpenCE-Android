package com.halo.decomp;

import java.io.*;
import java.util.*;

/** Bounded versioned LAN lobby frames. No host-only commands on the wire,
 * no file operations, and never send another member's secret token. */
final class LobbyWire {
    static final int MAGIC=0x484c5035, MAX_FRAME=1024*1024;
    static final int JOIN=1,POLL=2,UPDATE=3,LEAVE=4,ACK=5,RETURN=6;
    static final class Request {
        final int op;final long knownRevision;final String secret,name;final int color,requestTeam;final boolean ready;
        Request(int op,String secret,String name,int color,boolean ready,int team,long knownRevision){
            if(knownRevision < -1) throw new IllegalArgumentException("Invalid revision");
            this.knownRevision=knownRevision;
            if(op<JOIN||op>RETURN)throw new IllegalArgumentException("Invalid lobby action");
            this.op=op;this.secret=op==JOIN?"":LobbyModel.validId(secret);
            this.name=(op==JOIN||op==UPDATE)?LobbyModel.name(name):(op==ACK||op==RETURN)?LobbyModel.validId(name):"";
            this.color=LobbyModel.color(color);this.ready=ready;requestTeam=LobbyModel.team(team);
        }
        static Request join(String name,int color){return new Request(JOIN,"",name,color,false,LobbyModel.NO_TEAM,-1);}
        static Request poll(String secret,long known){return new Request(POLL,secret,"",0,false,LobbyModel.NO_TEAM,known);}
        static Request update(String secret,String name,int color,boolean ready,int team,long known){return new Request(UPDATE,secret,name,color,ready,team,known);}
        static Request acknowledge(String secret,String round,boolean accepted){
            return new Request(ACK,secret,round,0,accepted,LobbyModel.NO_TEAM,-1);
        }
        static Request returned(String secret,String round,boolean completed){
            return new Request(RETURN,secret,round,0,completed,LobbyModel.NO_TEAM,-1);
        }
        static Request leave(String secret){return new Request(LEAVE,secret,"",0,false,LobbyModel.NO_TEAM,-1);}
    }
    static final class Response {
        final String secret,error;final LobbyModel.Snapshot snapshot;
        Response(String token,LobbyModel.Snapshot snapshot,String error){this.secret=token;this.snapshot=snapshot;this.error=error;}
    }
    private static class VersionMismatch extends IOException {
        VersionMismatch(){super("Incompatible lobby version. Update all phones to the same Halo build.");}
    }
    private interface Writer {void write(DataOutputStream out)throws IOException;}
    private static void frame(OutputStream stream,Writer write)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(bytes)){out.writeInt(MAGIC);write.write(out);}
        if(bytes.size()>MAX_FRAME)throw new IOException("Lobby frame too large");
        DataOutputStream out=new DataOutputStream(stream);out.writeInt(bytes.size());bytes.writeTo(out);out.flush();
    }
    private static DataInputStream readFrame(InputStream stream,int max)throws IOException{
        DataInputStream input=new DataInputStream(stream);int n=input.readInt();
        if(n<5||n>max)throw new IOException("Invalid lobby frame size");
        byte[] b=new byte[n];input.readFully(b);
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(b));
        if(in.readInt()!=MAGIC)throw new VersionMismatch();
        return in;
    }
    private static void end(DataInputStream in)throws IOException{if(in.read()!=-1)throw new IOException("Unexpected lobby payload");}
    static void writeRequest(OutputStream out,Request r)throws IOException{
        frame(out,d->{d.writeByte(r.op);d.writeUTF(r.secret);d.writeUTF(r.name);d.writeByte(r.color);d.writeBoolean(r.ready);d.writeByte(r.requestTeam);d.writeLong(r.knownRevision);});
    }
    static Request readRequest(InputStream in)throws IOException{
        try(DataInputStream d=readFrame(in,1024)){
            Request r=new Request(d.readUnsignedByte(),d.readUTF(),d.readUTF(),d.readUnsignedByte(),d.readBoolean(),d.readByte(),d.readLong());
            end(d);return r;
        }catch(IllegalArgumentException invalid){throw new IOException("Invalid lobby request",invalid);}
    }
    static Response execute(LobbyModel model,Request r,long now){
        return execute(model,r,now,"","");
    }
    private static Response execute(LobbyModel model,Request r,long now,String localAddress,String peerAddress){
        try{
            switch(r.op){
                case JOIN: LobbyModel.Joined j=model.join(r.name,r.color,now,localAddress,peerAddress);return new Response(j.secret,j.snapshot,"");
                case POLL: LobbyModel.Snapshot current=model.poll(r.secret,now);return new Response("",current.revision==r.knownRevision?null:current,"");
                case UPDATE:return new Response("",model.guestUpdate(r.secret,r.name,r.color,r.ready,r.requestTeam,r.knownRevision,now),"");
                case ACK:return new Response("",model.acknowledge(r.secret,r.name,r.ready,now),"");
                case RETURN:return new Response("",model.guestReturned(r.secret,r.name,r.ready,now),"");
                case LEAVE:model.leave(r.secret,now);return new Response("",null,"");
                default:throw new IllegalArgumentException("Unknown lobby action");
            }
        }catch(IllegalArgumentException invalid){return new Response("",null,invalid.getMessage());}
    }
    static void writeResponse(OutputStream out,Response r)throws IOException{
        frame(out,d->{
            d.writeUTF(r.error);d.writeUTF(r.secret);d.writeBoolean(r.snapshot!=null);
            if(r.snapshot==null)return;
            LobbyModel.Snapshot s=r.snapshot;
            d.writeUTF(s.session);d.writeUTF(s.self);d.writeUTF(s.lobbyName);d.writeLong(s.revision);
            d.writeUTF(s.round);d.writeInt(s.startIndex);d.writeBoolean(s.released);d.writeUTF(s.startMessage);
            d.writeBoolean(s.hostBack);d.writeInt(s.nextIndex);d.writeInt(s.secondsToNext);
            d.writeUTF(s.plan.title);d.writeBoolean(s.plan.playlist);d.writeBoolean(s.plan.shuffle);d.writeBoolean(s.plan.loop);
            d.writeInt(s.plan.entries.size());
            for(MatchWorkshop.Entry e:s.plan.entries)MatchWorkshop.writeEntry(d,e);
            d.writeInt(s.members.size());
            for(LobbyModel.Member m:s.members){
                d.writeUTF(m.id);d.writeUTF(m.name);d.writeByte(m.color);d.writeByte(m.team);
                d.writeByte(m.requestedTeam);d.writeBoolean(m.host);d.writeBoolean(m.ready);
            }
        });
    }
    static Response readResponse(InputStream stream)throws IOException{
        try(DataInputStream in=readFrame(stream,MAX_FRAME)){
            String error=in.readUTF(),secret=in.readUTF();boolean has=in.readBoolean();
            if(error.length()>160||!secret.isEmpty()&&!LobbyModel.validId(secret).equals(secret)||!error.isEmpty()&&has)
                throw new IOException("Invalid lobby response");
            LobbyModel.Snapshot snapshot=null;
            if(has){
                String session=in.readUTF(),self=in.readUTF(),lobbyName=in.readUTF();long revision=in.readLong();
                String round=in.readUTF();int startIndex=in.readInt();boolean released=in.readBoolean();String message=in.readUTF();
                boolean hostBack=in.readBoolean();int nextIndex=in.readInt(),secondsToNext=in.readInt();
                String title=in.readUTF();boolean playlist=in.readBoolean(),shuffle=in.readBoolean(),loop=in.readBoolean();
                int n=in.readInt();if(n<0||n>MatchWorkshop.MAX_ENTRIES)throw new IOException("Invalid lobby playlist");
                ArrayList<MatchWorkshop.Entry> entries=new ArrayList<>();
                for(int i=0;i<n;i++)entries.add(MatchWorkshop.readEntry(in));
                LobbyModel.Plan p=new LobbyModel.Plan(title,playlist,shuffle,loop,entries);
                int count=in.readInt();if(count<1||count>LobbyModel.MAX_HUMANS)throw new IOException("Invalid lobby roster");
                ArrayList<LobbyModel.Member> members=new ArrayList<>();
                for(int i=0;i<count;i++)members.add(new LobbyModel.Member(in.readUTF(),in.readUTF(),in.readUnsignedByte(),in.readByte(),in.readByte(),in.readBoolean(),in.readBoolean()));
                snapshot=new LobbyModel.Snapshot(session,self,lobbyName,revision,p,members,round,startIndex,released,message,hostBack,nextIndex,secondsToNext);
            }
            end(in);return new Response(secret,snapshot,error);
        }catch(EOFException closed){throw new IOException("Host closed the connection. Check that both phones use the same Halo build.",closed);}
        catch(IllegalArgumentException|NullPointerException invalid){throw new IOException("Invalid lobby response",invalid);}
    }
    static void serve(LobbyModel model,InputStream in,OutputStream out,long now)throws IOException{
        serve(model,in,out,now,"","");
    }
    static void serve(LobbyModel model,InputStream in,OutputStream out,long now,String localAddress,String peerAddress)throws IOException{
        try{writeResponse(out,execute(model,readRequest(in),now,localAddress,peerAddress));}
        catch(VersionMismatch mismatch){
            // An older guest can identify this host's frame version before decoding any state.
            writeResponse(out,new Response("",null,mismatch.getMessage()));
        }
    }
}
