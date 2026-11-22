package app.volna;
import android.content.*;
import java.security.SecureRandom;
public final class Prefs {
 public static SharedPreferences get(Context c){return c.getSharedPreferences("volna",Context.MODE_PRIVATE);}
 public static String secret(Context c){SharedPreferences p=get(c);String s=p.getString("secret","");if(!s.matches("[a-f0-9]{32}")){byte[] b=new byte[16];new SecureRandom().nextBytes(b);StringBuilder x=new StringBuilder();for(byte v:b)x.append(String.format(java.util.Locale.US,"%02x",v&255));s=x.toString();p.edit().putString("secret",s).commit();}return s;}
 public static String socksPassword(Context c){return secret(c);}
 public static String link(Context c){
  LinkService service=LinkService.live;
  if(service==null)throw new IllegalStateException("Сначала запустите подключение");
  return service.telegramLink(secret(c));
 }
}
