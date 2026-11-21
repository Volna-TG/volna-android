package app.volna;

import java.net.URI;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

final class ProxyProfile {
    final String host, label;
    final JSONObject outbound;

    private ProxyProfile(String host, String label, JSONObject outbound) {
        this.host = host;
        this.label = label;
        this.outbound = outbound;
    }

    static ProxyProfile parse(String link) {
        try {
            URI uri = new URI(link);
            if ("vless".equalsIgnoreCase(uri.getScheme())) {
                VlessProfile profile = VlessProfile.parse(link);
                return new ProxyProfile(profile.host, profile.label(), profile.outbound());
            }
            if (!"hy2".equalsIgnoreCase(uri.getScheme()) && !"hysteria2".equalsIgnoreCase(uri.getScheme()))
                throw new IllegalArgumentException();
            String host = uri.getHost(), auth = uri.getUserInfo();
            int port = uri.getPort() == -1 ? 443 : uri.getPort();
            if (host == null || auth == null || auth.isEmpty() || port < 1 || port > 65535)
                throw new IllegalArgumentException();
            Map<String,String> params = new HashMap<>();
            if (uri.getRawQuery() != null) for (String item : uri.getRawQuery().split("&")) {
                String[] parts = item.split("=", 2);
                if (parts.length != 2 || !parts[0].matches("sni|pinSHA256|pcs|insecure") ||
                        params.put(parts[0], URLDecoder.decode(parts[1].replace("+","%2B"), "UTF-8")) != null)
                    throw new IllegalArgumentException();
            }
            JSONObject tls = new JSONObject().put("serverName",params.getOrDefault("sni",host))
                    .put("alpn",new JSONArray().put("h3"));
            String pin = params.getOrDefault("pinSHA256",params.getOrDefault("pcs","")).replace(":","").toLowerCase(Locale.ROOT);
            if (!pin.isEmpty()) {
                if (!pin.matches("[0-9a-f]{64}") || !pin.equalsIgnoreCase(params.getOrDefault("pcs",pin).replace(":","")))
                    throw new IllegalArgumentException();
                tls.put("pinnedPeerCertSha256",pin);
            } else if (!params.getOrDefault("insecure","0").matches("0|false")) throw new IllegalArgumentException();
            JSONObject stream = new JSONObject().put("network","hysteria").put("security","tls")
                    .put("tlsSettings",tls).put("hysteriaSettings",new JSONObject().put("version",2).put("auth",auth));
            return new ProxyProfile(host,"Hysteria 2",new JSONObject().put("protocol","hysteria")
                    .put("settings",new JSONObject().put("version",2).put("address",host).put("port",port))
                    .put("streamSettings",stream));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Проверьте параметры VLESS или Hysteria 2");
        }
    }
}
