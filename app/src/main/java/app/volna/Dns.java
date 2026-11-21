package app.volna;
import java.net.*;
import java.util.concurrent.*;
public final class Dns {
 private static final ExecutorService pool=Executors.newFixedThreadPool(3,r->{Thread t=new Thread(r,"dns");t.setDaemon(true);return t;});
 private static final class Entry{final String ip;final long time;Entry(String ip){this.ip=ip;time=System.currentTimeMillis();}}
 private static final ConcurrentHashMap<String,Entry> cache=new ConcurrentHashMap<>();
 static void clear(){ cache.clear(); }
 public static String resolve(String host){
  if(host==null||host.isEmpty())return null;
  if(host.matches("[0-9.]+"))return host;
  Entry old=cache.get(host);long age=old==null?Long.MAX_VALUE:System.currentTimeMillis()-old.time;
  if(age<300000)return old.ip;
  Future<String> future=pool.submit(()->{InetAddress[] all=InetAddress.getAllByName(host);for(InetAddress a:all)if(a instanceof Inet4Address)return a.getHostAddress();return all[0].getHostAddress();});
  try{String ip=future.get(2000,TimeUnit.MILLISECONDS);cache.put(host,new Entry(ip));return ip;}
  catch(Exception e){future.cancel(true);if(e instanceof InterruptedException)Thread.currentThread().interrupt();return age<3600000?old.ip:null;}
 }
}
