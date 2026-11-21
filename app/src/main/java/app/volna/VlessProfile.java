package app.volna;

import java.net.URI;
import java.net.URLDecoder;
import java.util.*;
import org.json.*;

public final class VlessProfile {
 public final String host,id,transport,sni,publicKey,shortId,fingerprint,spider,path,mode,flow,httpHost,serviceName,authority;
 public final int port;
 private static String decode(String s) throws Exception {return URLDecoder.decode(s.replace("+","%2B"),"UTF-8");}
 public static VlessProfile parse(String input) throws Exception {
  try {return new VlessProfile(input.trim());}catch(Exception e){throw new IllegalArgumentException("Некорректный или неподдерживаемый VLESS REALITY ключ");}
 }
 private VlessProfile(String input) throws Exception {
  URI u=new URI(input);if(!"vless".equalsIgnoreCase(u.getScheme())||u.getHost()==null||u.getPort()<1||u.getPort()>65535)throw new Exception();
  host=u.getHost();port=u.getPort();id=UUID.fromString(u.getUserInfo()).toString();
  Map<String,String> q=new HashMap<>();if(u.getRawQuery()!=null)for(String pair:u.getRawQuery().split("&")){int k=pair.indexOf('=');if(k>0)q.put(decode(pair.substring(0,k)),decode(pair.substring(k+1)));}
  String t=q.getOrDefault("type","tcp");transport=t.equals("raw")?"tcp":t.equals("splithttp")?"xhttp":t;
  if(!Arrays.asList("tcp","xhttp","grpc").contains(transport))throw new Exception();
  if(!"reality".equals(q.get("security"))||!"none".equals(q.getOrDefault("encryption","none")))throw new Exception();
  sni=q.getOrDefault("sni",host);publicKey=q.getOrDefault("pbk","");shortId=q.getOrDefault("sid","");fingerprint=q.getOrDefault("fp","chrome");spider=q.getOrDefault("spx","/");
  httpHost=q.getOrDefault("host","");path=q.getOrDefault("path","/");mode=q.getOrDefault("mode",transport.equals("grpc")?"gun":"auto");flow=q.getOrDefault("flow","");
  serviceName=q.getOrDefault("serviceName","");authority=q.getOrDefault("authority","");
  if(sni.isEmpty()||!publicKey.matches("[A-Za-z0-9_-]{43}")||!shortId.matches("(?:[0-9a-fA-F]{2}){0,8}")||(!flow.isEmpty()&&!flow.equals("xtls-rprx-vision")))throw new Exception();
  if(!transport.equals("tcp")&&!flow.isEmpty())throw new Exception();
  if(transport.equals("xhttp")&&!Arrays.asList("auto","stream-one","stream-up","packet-up").contains(mode))throw new Exception();
  if(transport.equals("grpc")&&(!serviceName.matches("[A-Za-z0-9_.-]{1,128}")||
      !Arrays.asList("gun","multi").contains(mode)||(!authority.isEmpty()&&!authority.matches("[a-zA-Z0-9.:-]{1,253}"))))throw new Exception();
 }
 public String label(){return "VLESS "+transport.toUpperCase(Locale.ROOT)+" Reality";}
 public JSONObject outbound() throws JSONException {
  JSONObject reality=new JSONObject().put("serverName",sni).put("fingerprint",fingerprint).put("publicKey",publicKey).put("shortId",shortId).put("spiderX",spider);
  JSONObject stream=new JSONObject().put("network",transport).put("security","reality").put("realitySettings",reality);
  if(transport.equals("xhttp"))stream.put("xhttpSettings",new JSONObject().put("path",path).put("mode",mode).put("host",httpHost));
  if(transport.equals("grpc")){
   JSONObject grpc=new JSONObject().put("serviceName",serviceName).put("multiMode",mode.equals("multi"));
   if(!authority.isEmpty())grpc.put("authority",authority);
   stream.put("grpcSettings",grpc);
  }
  JSONObject user=new JSONObject().put("id",id).put("encryption","none");if(!flow.isEmpty())user.put("flow",flow);
  JSONObject remote=new JSONObject().put("address",host).put("port",port).put("users",new JSONArray().put(user));
  JSONObject outbound=new JSONObject().put("protocol","vless").put("settings",new JSONObject().put("vnext",new JSONArray().put(remote))).put("streamSettings",stream);
  return outbound;
 }
 static JSONObject config(int localPort,String localPassword,JSONObject outbound, String controlPath, boolean direct) throws JSONException {
  JSONObject socks=new JSONObject().put("auth","password").put("udp",false).put("accounts",new JSONArray().put(new JSONObject().put("user","volna").put("pass",localPassword)));
  JSONObject inbound=new JSONObject().put("listen","127.0.0.1").put("port",localPort).put("protocol","socks").put("settings",socks);
  inbound.put("tag", "telegram");
  String initial = direct ? "vpn" : "profile";
  JSONObject balancer = new JSONObject().put("tag", "active")
          .put("selector", new JSONArray().put(initial));
  JSONObject rule = new JSONObject().put("type", "field")
          .put("inboundTag", new JSONArray().put("telegram")).put("balancerTag", "active");
  return new JSONObject()
          .put("log", new JSONObject().put("loglevel", "none"))
          .put("inbounds", new JSONArray().put(inbound))
          .put("outbounds", new JSONArray().put(outbound.put("tag", "profile"))
                  .put(new JSONObject().put("tag", "vpn").put("protocol", "freedom")))
          .put("api", new JSONObject().put("tag", "control").put("listen", controlPath)
                  .put("services", new JSONArray().put("RoutingService")))
          .put("routing", new JSONObject().put("balancers", new JSONArray().put(balancer))
                  .put("rules", new JSONArray().put(rule)));
 }
}
