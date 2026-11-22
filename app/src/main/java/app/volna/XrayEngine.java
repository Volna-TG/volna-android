package app.volna;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class XrayEngine {
 private volatile Process process;
 private volatile int port;
 private File binary, control;
 private boolean direct;


 int getPort(){return port;}
 boolean isRunning(){Process p=process;return p!=null&&p.isAlive()&&port>0;}
 void start(Context context,org.json.JSONObject outbound,String password, boolean useVpn, Runnable onExit) throws Exception {
  binary=new File(context.getApplicationInfo().nativeLibraryDir,"libxray_exec.so");
  if(!binary.isFile())throw new IOException("В APK отсутствует libxray_exec.so ("+android.os.Build.SUPPORTED_ABIS[0]+")");
  if(!binary.canExecute())throw new IOException("Android не разрешает запуск встроенного Xray");
  int candidate=Prefs.get(context).getInt("socks_port",10808);
  try(ServerSocket s=new ServerSocket()){s.bind(new InetSocketAddress("127.0.0.1",candidate));}catch(IOException busy){try(ServerSocket s=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){candidate=s.getLocalPort();}}
  File stale = new File(context.getNoBackupFilesDir(),"xray-runtime.json");
  if (stale.exists() && !stale.delete()) throw new IOException("Не удалось очистить старые настройки");
  control = new File(context.getNoBackupFilesDir(),"routing.sock");
  java.nio.file.Files.deleteIfExists(control.toPath());
  direct = useVpn;
  byte[] settings = VlessProfile.config(candidate,password,outbound,control.getAbsolutePath(),useVpn).toString().getBytes(StandardCharsets.UTF_8);
  ProcessBuilder builder=new ProcessBuilder(binary.getAbsolutePath(),"run","-format","json","-config","stdin:");
  builder.directory(context.getNoBackupFilesDir());
  builder.redirectErrorStream(true);
  builder.redirectOutput(ProcessBuilder.Redirect.to(new File("/dev/null")));
  Process p=builder.start();process=p;
  try(OutputStream pipe=p.getOutputStream()){pipe.write(settings);}
  catch(IOException failure){stop();throw failure;}
  finally{java.util.Arrays.fill(settings,(byte)0);}
  try{
   for(int i=0;i<60;i++){
    if(!p.isAlive())throw new IOException("Xray завершился при запуске (код "+p.exitValue()+"). Проверьте совместимость ключа и ядра");
    try(Socket sock=new Socket()){sock.connect(new InetSocketAddress("127.0.0.1",candidate),150);sock.setSoTimeout(200);sock.getOutputStream().write(new byte[]{5,1,2});if(sock.getInputStream().read()==5&&sock.getInputStream().read()==2){port=candidate;Prefs.get(context).edit().putInt("socks_port",candidate).apply();
       Thread watcher = new Thread(() -> {
        try { p.waitFor(); if (process == p) onExit.run(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
       }, "volna-xray-exit");
       watcher.setDaemon(true);
       watcher.start();
       return;}}catch(IOException ignored){}
    Thread.sleep(100);
   }
   throw new IOException("Локальный SOCKS5 не запустился");
  }catch(Exception failure){stop();throw failure;}
 }
 void setDirect(boolean enable) throws Exception {
  if (direct == enable) return;
  Process command = new ProcessBuilder(binary.getAbsolutePath(), "api", "bo",
          "--server=unix://" + control.getAbsolutePath(), "--timeout=2", "-b", "active",
          enable ? "vpn" : "profile")
          .redirectErrorStream(true).redirectOutput(new File("/dev/null")).start();
  try {
   if (!command.waitFor(3, TimeUnit.SECONDS) || command.exitValue() != 0)
    throw new IOException("Не удалось переключить маршрут");
   direct = enable;
  } finally {
   if (command.isAlive()) command.destroyForcibly();
  }
 }
 void stop(){port=0;Process p=process;process=null;if(p!=null){p.destroy();try{if(!p.waitFor(300,TimeUnit.MILLISECONDS))p.destroyForcibly();}catch(InterruptedException interrupted){p.destroyForcibly();Thread.currentThread().interrupt();}}}
}
