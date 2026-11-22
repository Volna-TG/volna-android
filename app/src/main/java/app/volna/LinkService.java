package app.volna;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import java.util.Set;
import java.util.concurrent.*;

public final class LinkService extends Service {
 public static final String STOP="app.volna.STOP", RESTART="app.volna.RESTART";
 public static volatile LinkService live;
 private static final Set<Runnable> listeners=new CopyOnWriteArraySet<>();
 public static void addListener(Runnable listener){listeners.add(listener);}
 public static void removeListener(Runnable listener){listeners.remove(listener);}
 private static void signal(){for(Runnable listener:listeners)listener.run();}
 private volatile XrayEngine xray;
 private volatile TgWebEngine standard;

 String telegramLink(String secret) {
  TgWebEngine engine = standard;
  if (engine != null && engine.isRunning()) {
   int port = engine.getPort();
   if (port > 0) return "tg://proxy?server=127.0.0.1&port=" + port + "&secret=dd" + secret;
  }
  int port = socksPort();
  if (port > 0) return "tg://socks?server=127.0.0.1&port=" + port + "&user=volna&pass=" + secret;
  throw new IllegalStateException("Сначала запустите подключение");
 }

 public boolean isStandard() { return standard != null; }
 public int socksPort(){XrayEngine x=xray;return x!=null&&x.isRunning()?x.getPort():0;}
 public boolean isRunning(){
  TgWebEngine engine = standard;
  return engine != null && engine.isRunning() || socksPort() > 0;
 }
 public boolean isReady(){
  TgWebEngine engine = standard;
  return engine != null ? engine.isRunning() && (paused || engine.isRouteReady()) : socksPort() > 0;
 }
 public volatile String status="Остановлено",error="";
 public volatile boolean paused,starting;
 private final ExecutorService serial=Executors.newSingleThreadExecutor();
 private final Handler main=new Handler(Looper.getMainLooper());
 private final Object engineLock=new Object();
 private volatile boolean destroyed;
 private String lastNotification="";
 private ConnectivityManager cm;
 private ConnectivityManager.NetworkCallback networkCallback;
 private android.content.SharedPreferences.OnSharedPreferenceChangeListener preferences;
 private PowerManager.WakeLock startupLock;
 private volatile Network defaultNetwork;
 private volatile Network directNetwork;
 private volatile boolean defaultIsVpn;
 private void setNetwork(Network network, NetworkCapabilities caps) {
  boolean changed = !java.util.Objects.equals(defaultNetwork, network);
  boolean vpn = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
  if (!changed && defaultIsVpn == vpn) return;
  defaultNetwork = network;
  defaultIsVpn = vpn;
  updatePolicy(changed);
 }

 private void updatePolicy(boolean networkChanged) {
  if (destroyed) return;
  directNetwork = defaultIsVpn && Prefs.get(this).getBoolean("sleep_vpn", false) ? defaultNetwork : null;
  serial.execute(() -> {
   if (destroyed) return;
   boolean direct = directNetwork != null;
   XrayEngine current = xray;
   try {
    if (current != null && current.isRunning()) current.setDirect(direct);
   } catch (Exception failure) {
    if (!destroyed) {
     closeEngine();
     startEngine();
    }
    return;
   }
   boolean policyChanged = paused != direct;
   paused = direct;
   TgWebEngine mt = standard;
   if (mt != null && (policyChanged || networkChanged)) mt.networkChanged(direct || defaultNetwork == null);
   if (isRunning()) {
    status = direct ? "Напрямую через VPN" : mt == null ? "Локальный SOCKS5 запущен"
            : mt.isRouteReady() ? "Маршрут доступен" : "Ищем маршрут";
    if (direct || networkChanged) error = "";
    notifyState();
   }
  });
 }
 public void onCreate(){
  super.onCreate();live=this;
  getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("connection","Подключение",NotificationManager.IMPORTANCE_LOW));
  startForeground(1,notification("Запуск подключения"));lastNotification="Запуск подключения";
  Prefs.secret(this);cm=getSystemService(ConnectivityManager.class);
  Network initial = cm.getActiveNetwork();
  setNetwork(initial, initial == null ? null : cm.getNetworkCapabilities(initial));
  networkCallback=new ConnectivityManager.NetworkCallback(){
   private Network newest;
   public void onAvailable(Network network){ newest = network; }
   public void onCapabilitiesChanged(Network network, NetworkCapabilities caps){
    if (network.equals(newest)) setNetwork(network, caps);
   }
   public void onLost(Network network){
    if (network.equals(newest)) newest = null;
    if (network.equals(defaultNetwork)) setNetwork(null, null);
   }
  };
  cm.registerDefaultNetworkCallback(networkCallback, main);
  preferences=(prefs,key)->{if("sleep_vpn".equals(key))main.post(()->updatePolicy(false));};
  Prefs.get(this).registerOnSharedPreferenceChangeListener(preferences);
  signal();
 }
 public int onStartCommand(Intent intent,int flags,int id){
  if(intent!=null&&STOP.equals(intent.getAction())){Prefs.get(this).edit().putBoolean("wanted",false).apply();stopSelf();return START_NOT_STICKY;}
  Prefs.get(this).edit().putBoolean("wanted",true).apply();
  serial.execute(()->{if(destroyed)return;if(intent!=null&&RESTART.equals(intent.getAction()))closeEngine();if(!isRunning())startEngine();});
  return START_STICKY;
 }
 private void startEngine(){
  if(destroyed)return;
  starting=true;status="Запуск";error="";Prefs.get(this).edit().remove("last_error").apply();notifyState();
  try{
   startupLock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"volna:startup");startupLock.acquire(15000);
    Profiles.Entry profile = Profiles.selected(this);
    if (profile == null) throw new IllegalStateException("Сначала добавьте профиль");
    if (Profiles.STANDARD.equals(profile.id)) {
     startStandard();
     return;
    }
    boolean direct = directNetwork != null;
    org.json.JSONObject outbound = ProxyProfile.parse(profile.link).outbound;
    synchronized(engineLock){
     if (destroyed) return;
     XrayEngine current=new XrayEngine();xray=current;
     current.start(this, outbound, Prefs.socksPassword(this), direct, () -> main.post(() -> {
      if (destroyed || xray != current) return;
      error = "Ядро подключения остановилось";
      Prefs.get(this).edit().putString("last_error", error).putBoolean("wanted", false).apply();
      stopSelf();
     }));
     paused = direct;
     status = direct ? "Напрямую через VPN" : "Локальный SOCKS5 запущен";
   }
  }catch(Exception|LinkageError failure){
   if (destroyed) return;
   error="Не удалось запустить подключение. Проверьте выбранный профиль.";status="Ошибка запуска";
   Prefs.get(this).edit().putString("last_error",error).putBoolean("wanted",false).apply();closeEngine();main.post(()->stopSelf());
  }finally{starting=false;if(startupLock!=null&&startupLock.isHeld())startupLock.release();notifyState();}
 }
 private void startStandard() throws Exception {
  synchronized (engineLock) {
   if (destroyed) return;
   TgWebEngine current = new TgWebEngine();
   standard = current;
   current.setFatalErrorListener(message -> main.post(() -> {
    if (standard != current || destroyed) return;
    Prefs.get(this).edit().putString("last_error", message).putBoolean("wanted", false).apply();
    stopSelf();
   }));
   current.setRouteStateListener((ready, message) -> main.post(() -> {
    if (standard != current || destroyed) return;
    if (directNetwork != null) return;
    status = ready ? "Маршрут доступен" : "Маршрут недоступен";
    error = ready ? "" : message;
    notifyState();
   }));
   RouteOptions options = new RouteOptions();
   options.vpnNetwork = () -> directNetwork;
   paused = directNetwork != null;
   int port = Prefs.get(this).getInt("standard_port", 1443);
   current.start(port, Prefs.secret(this), options);
   Prefs.get(this).edit().putInt("standard_port", current.getPort()).apply();
   status = paused ? "Напрямую через VPN" : "Ищем маршрут";
  }
 }

 private void closeEngine() {
  synchronized (engineLock) {
   TgWebEngine mtproto = standard;
   standard = null;
   if (mtproto != null) mtproto.stop();
   XrayEngine socks = xray;
   xray = null;
   if (socks != null) socks.stop();
  }
 }
 private Notification notification(String text){
  PendingIntent content=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
  PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,LinkService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE);
  return new Notification.Builder(this,"connection").setSmallIcon(R.drawable.ic_stat).setContentTitle("Волна").setContentText(text).setContentIntent(content).setOngoing(true).addAction(new Notification.Action.Builder(null,"Остановить",stop).build()).build();
 }
 private synchronized void notifyState(){
  if(destroyed)return;
  String text=status;
  if(!text.equals(lastNotification)){getSystemService(NotificationManager.class).notify(1,notification(text));lastNotification=text;}
  signal();
 }
 public void onDestroy(){
  destroyed=true;main.removeCallbacksAndMessages(null);
  if(cm!=null){try{cm.unregisterNetworkCallback(networkCallback);}catch(Exception ignored){}}
  if(preferences!=null)Prefs.get(this).unregisterOnSharedPreferenceChangeListener(preferences);
  closeEngine();serial.shutdownNow();
  if(live==this)live=null;stopForeground(STOP_FOREGROUND_REMOVE);signal();super.onDestroy();
 }
 public IBinder onBind(Intent intent){return null;}
}
