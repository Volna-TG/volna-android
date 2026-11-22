package app.volna;
import android.content.*;
public final class BootReceiver extends BroadcastReceiver{public void onReceive(Context c,Intent i){if(Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())&&Prefs.get(c).getBoolean("boot",false)&&Prefs.get(c).getBoolean("wanted",false))try{c.startForegroundService(new Intent(c,LinkService.class));}catch(Exception ignored){}}}
