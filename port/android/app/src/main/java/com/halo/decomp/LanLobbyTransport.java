package com.halo.decomp;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Foreground-only pregame service. Bound to eligible private LAN interfaces,
 * same-subnet peers only. This protocol carries match drafts and roster
 * metadata, never files, accounts, or remote host controls. */
final class LanLobbyTransport {
    static final int PORT=5167;
    static long now(){return System.nanoTime()/1000000L;}
    private static void shut(Closeable c){try{c.close();}catch(IOException ignored){}}
    private static final class Link {
        final InetAddress ip;final int prefix;
        Link(InetAddress ip,int prefix){this.ip=ip;this.prefix=prefix;}
        boolean accepts(byte[] peer){return LanUpdateProtocol.sameSubnet(ip.getAddress(),peer,prefix);}
    }
    private static List<Link> links()throws SocketException{
        List<Link> out=new ArrayList<>();
        Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();
        while(all!=null&&all.hasMoreElements()&&out.size()<8){
            NetworkInterface nic=all.nextElement();
            if(!nic.isUp()||nic.isLoopback()||!LanUpdateProtocol.lanInterface(nic.getName()))continue;
            for(InterfaceAddress address:nic.getInterfaceAddresses()){
                InetAddress ip=address.getAddress();
                int prefix=LanUpdateProtocol.usableHostPrefix(nic.getName(),address.getNetworkPrefixLength());
                if(ip instanceof Inet4Address&&prefix>=0&&LanUpdateProtocol.privateAddress(ip.getHostAddress())!=null)
                    out.add(new Link(ip,prefix));
                if(out.size()>=8)break;
            }
        }
        return out;
    }
    static final class Host implements Closeable {
        final LobbyModel model;
        private final List<ServerSocket> listeners=new ArrayList<>();
        private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
        private final ThreadPoolExecutor workers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(12));
        private final ScheduledThreadPoolExecutor deadlines=new ScheduledThreadPoolExecutor(1);
        private volatile boolean closed;
        private LanDiscoveryServer discovery;
        private String address="";
        Host(LobbyModel model){this.model=model;deadlines.setRemoveOnCancelPolicy(true);}
        synchronized String open()throws IOException{
            if(closed||!listeners.isEmpty())throw new IOException("Lobby already opened or closed");
            List<String> ips=new ArrayList<>();
            for(Link link:links()){
                try{
                    ServerSocket server=new ServerSocket();
                    try{server.bind(new InetSocketAddress(link.ip,PORT),16);}
                    catch(IOException unavailable){shut(server);continue;}
                    listeners.add(server);ips.add(link.ip.getHostAddress());
                    Thread thread=new Thread(()->accept(server,link),"Halo-lobby-listener");
                    thread.setDaemon(true);thread.start();
                }catch(IOException unavailable){}
            }
            if(ips.isEmpty()){close();throw new IOException("Connect Wi-Fi or enable your hotspot before creating a lobby.");}
            address=String.join(" / ",ips);
            discovery=LanDiscoveryServer.start(PORT);
            return address;
        }
        String address(){return address;}
        private void accept(ServerSocket listener,Link link){
            while(!closed){
                Socket socket=null;
                try{
                    socket=listener.accept();
                    if(closed||!link.accepts(socket.getInetAddress().getAddress())){shut(socket);continue;}
                    socket.setSoTimeout(2500);
                    Socket accepted=socket;sockets.add(accepted);
                    ScheduledFuture<?> expiry=deadlines.schedule(()->shut(accepted),5,TimeUnit.SECONDS);
                    try{
                        workers.execute(()->{
                            try(Socket s=accepted){LobbyWire.serve(model,s.getInputStream(),s.getOutputStream(),now());}
                            catch(IOException|RuntimeException ignored){}
                            finally{expiry.cancel(false);sockets.remove(accepted);}
                        });
                    }catch(RejectedExecutionException busy){expiry.cancel(false);sockets.remove(accepted);shut(accepted);}
                }catch(IOException|RejectedExecutionException stopped){
                    if(socket!=null){sockets.remove(socket);shut(socket);}
                    if(listener.isClosed()||closed)break;
                }
            }
        }
        @Override public synchronized void close(){
            closed=true;
            if(discovery!=null){discovery.stop();discovery=null;}
            for(ServerSocket listener:listeners)shut(listener);listeners.clear();
            for(Socket socket:sockets)shut(socket);sockets.clear();
            workers.shutdownNow();deadlines.shutdownNow();
        }
    }
    static final class Client implements Closeable {
        final String address;
        private String secret="",session="";
        private volatile boolean closed;
        private volatile Socket pending;
        private final ScheduledThreadPoolExecutor deadline=new ScheduledThreadPoolExecutor(1,r->{
            Thread t=new Thread(r,"Halo-lobby-deadline");t.setDaemon(true);return t;
        });
        Client(String ip)throws IOException{
            if(LanUpdateProtocol.privateAddress(ip)==null)throw new IOException("Enter a private LAN host IP");
            address=ip;deadline.setRemoveOnCancelPolicy(true);
        }
        private LobbyWire.Response request(LobbyWire.Request request)throws IOException{
            if(closed)throw new IOException("Lobby connection closed");
            byte[] peer=LanUpdateProtocol.privateAddress(address);
            Link chosen=null;
            for(Link link:links())if(link.accepts(peer)){chosen=link;break;}
            if(chosen==null)throw new IOException("Host is not on this Wi-Fi or hotspot network");
            try(Socket socket=new Socket()){
                pending=socket;
                if(closed)throw new IOException("Lobby connection closed");
                ScheduledFuture<?> expiry=deadline.schedule(()->shut(socket),6,TimeUnit.SECONDS);
                try {
                socket.bind(new InetSocketAddress(chosen.ip,0));
                socket.connect(new InetSocketAddress(InetAddress.getByAddress(peer),PORT),2000);
                socket.setSoTimeout(3000);
                LobbyWire.writeRequest(socket.getOutputStream(),request);
                LobbyWire.Response response=LobbyWire.readResponse(socket.getInputStream());
                if(!response.error.isEmpty())throw new IOException("Host declined: "+response.error);
                if(response.snapshot!=null&&!session.isEmpty()&&!session.equals(response.snapshot.session))
                    throw new IOException("Host created another lobby");
                return response;
                }finally{expiry.cancel(false);}
            }finally{pending=null;}
        }
        LobbyModel.Snapshot join(String name,int color)throws IOException{
            LobbyWire.Response r=request(LobbyWire.Request.join(name,color));
            if(r.snapshot==null||r.secret.isEmpty()||r.snapshot.me().host)throw new IOException("Invalid join acknowledgement");
            secret=r.secret;session=r.snapshot.session;return r.snapshot;
        }
        LobbyModel.Snapshot poll(long known)throws IOException{return request(LobbyWire.Request.poll(secret,known)).snapshot;}
        LobbyModel.Snapshot update(String name,int color,boolean ready,int team,long known)throws IOException{
            LobbyModel.Snapshot s=request(LobbyWire.Request.update(secret,name,color,ready,team,known)).snapshot;
            if(s==null)throw new IOException("Missing profile acknowledgement");
            return s;
        }
        void leave()throws IOException{if(!secret.isEmpty())request(LobbyWire.Request.leave(secret));}
        @Override public void close(){closed=true;Socket socket=pending;if(socket!=null)shut(socket);deadline.shutdownNow();}
    }
}
