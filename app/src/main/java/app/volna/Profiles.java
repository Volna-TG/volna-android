package app.volna;

import android.content.Context;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

public final class Profiles {
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_PROFILES = 256;
    private static final String KEY = "profiles_v1";

    public static final class Entry {
        public final String id, link, source, name;

        private Entry(String mode, String label, String uri, boolean bundled) {
            id = mode;
            name = label;
            source = "";
            link = uri;
        }

        public boolean managed() { return link.isEmpty() || id.startsWith("bundled-"); }

        Entry(String id, String link, String source) throws Exception {
            this.id = id;
            this.link = link;
            this.source = source;
            ProxyProfile profile = ProxyProfile.parse(link);
            String title = URI.create(link).getFragment();
            title = title == null ? "" : title.replaceAll("[\\p{Cntrl}\\p{Cf}]", "").trim();
            name = title.isEmpty() ? profile.label + " · " + profile.host : title.substring(0, Math.min(80, title.length()));
        }
    }

    private static List<Entry> load(Context context) throws Exception {
        List<Entry> entries = new ArrayList<>();
        String saved = Prefs.get(context).getString(KEY, null);
        if (saved == null) return entries;
        {
            String plain;
            if (saved.startsWith(ProfileVault.PREFIX)) plain = ProfileVault.open(saved);
            else {
                plain = saved;
                if (!Prefs.get(context).edit().putString(KEY,ProfileVault.seal(plain)).commit())
                    throw new IllegalStateException("Профили недоступны");
            }
            JSONArray array = new JSONArray(plain);
            for (int i = 0; i < array.length(); i++) {
                try {
                    JSONObject item = array.getJSONObject(i);
                    entries.add(new Entry(item.getString("id"), item.getString("link"), item.getString("source")));
                } catch (Exception ignored) {}
            }
        }
        return entries;
    }

    static final String STANDARD = "cf80";

    private static final Entry DEFAULT = new Entry(STANDARD, "Стандарт", "", true);

    static Entry bundled(String id, String label, String uri) {
        return new Entry("bundled-" + id, label, uri, true);
    }

    public static synchronized List<Entry> list(Context context) {
        List<Entry> entries = new ArrayList<>();
        entries.add(DEFAULT);
        entries.addAll(ProfileCatalog.entries(context));
        try { entries.addAll(load(context)); } catch (Exception unavailable) {}
        return entries;
    }

    private static boolean builtin(Context context, String id) {
        if (STANDARD.equals(id)) return true;
        for (Entry entry : ProfileCatalog.entries(context)) if (entry.id.equals(id)) return true;
        return false;
    }

    public static synchronized Entry selected(Context context) {
        String id = Prefs.get(context).getString("profile_id", STANDARD);
        if (STANDARD.equals(id)) return DEFAULT;
        for (Entry entry : list(context)) if (entry.id.equals(id)) return entry;
        return DEFAULT;
    }

    public static synchronized void select(Context context, String id) {
        Prefs.get(context).edit().putString("profile_id", id).remove("last_error").apply();
    }

    private static void save(Context context, List<Entry> entries, String selectedId) throws Exception {
        JSONArray array = new JSONArray();
        for (Entry entry : entries)
            array.put(new JSONObject().put("id", entry.id).put("link", entry.link).put("source", entry.source));
        if (!Prefs.get(context).edit().putString(KEY, ProfileVault.seal(array.toString())).putString("profile_id", selectedId)
                .remove("last_error").commit()) throw new IllegalStateException("Не удалось сохранить профили");
    }

    public static synchronized void remove(Context context, Entry entry) throws Exception {
        List<Entry> entries = load(context);
        entries.removeIf(item -> entry.source.isEmpty() ? item.id.equals(entry.id) : item.source.equals(entry.source));
        String selectedId = Prefs.get(context).getString("profile_id", "");
        boolean retained = builtin(context, selectedId) || entries.stream().anyMatch(item -> item.id.equals(selectedId));
        save(context, entries, retained ? selectedId : entries.isEmpty() ? STANDARD : entries.get(0).id);
    }

    public static int importInput(Context context, String raw) throws Exception {
        String source = "";
        String body = raw.trim();
        if (body.length() > MAX_BYTES) throw new IllegalArgumentException("Список слишком большой");
        if (body.regionMatches(true, 0, "https://", 0, 8)) {
            source = body;
            body = download(source);
        }
        LinkedHashSet<String> links = parse(body);
        synchronized (Profiles.class) {
            List<Entry> old = load(context);
            List<Entry> entries = new ArrayList<>();
            for (Entry entry : old) if (source.isEmpty() || !source.equals(entry.source)) entries.add(entry);
            List<Entry> imported = new ArrayList<>();
            for (String link : links) {
                final String group = source;
                if (entries.stream().anyMatch(item -> item.source.equals(group) && item.link.equals(link))) continue;
                String id = old.stream().filter(item -> item.source.equals(group) && item.link.equals(link))
                        .map(item -> item.id).findFirst().orElseGet(() -> UUID.randomUUID().toString());
                imported.add(new Entry(id, link, source));
            }
            entries.addAll(imported);
            if (entries.size() > MAX_PROFILES) throw new IllegalArgumentException("Лимит — 256 профилей");
            String selectedId = Prefs.get(context).getString("profile_id", "");
            boolean retained = builtin(context, selectedId) || entries.stream().anyMatch(item -> item.id.equals(selectedId));
            save(context, entries, retained ? selectedId : !imported.isEmpty() ? imported.get(0).id : entries.get(0).id);
            return links.size();
        }
    }

    private static LinkedHashSet<String> parse(String raw) {
        String body = raw.trim().replace("\uFEFF", "");
        if (!body.contains("://")) {
            try {
                String encoded = body.replaceAll("\\s+", "");
                int flags = encoded.contains("_") || encoded.contains("-") ? Base64.URL_SAFE : Base64.DEFAULT;
                body = new String(Base64.decode(encoded, flags), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {}
        }
        LinkedHashSet<String> links = new LinkedHashSet<>();
        for (String line : body.split("\\r?\\n")) {
            String link = line.trim();
            if (link.length() > 8192) continue;
            try { ProxyProfile.parse(link); } catch (Exception unsupported) { continue; }
            links.add(link);
            if (links.size() > MAX_PROFILES) throw new IllegalArgumentException("В подписке больше 256 профилей");
        }
        if (links.isEmpty()) throw new IllegalArgumentException("Нет совместимых профилей VLESS или Hysteria 2");
        return links;
    }

    private static String download(String source) throws Exception {
        URI uri = URI.create(source);
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null)
            throw new IllegalArgumentException("Нужна корректная HTTPS-ссылка подписки");
        HttpsURLConnection connection = (HttpsURLConnection) new URL(source).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "Volna-Android/2.4");
        try {
            if (connection.getResponseCode() != 200) throw new IllegalArgumentException("Сервер подписки не вернул список");
            long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) != -1;) {
                    if (out.size() + n > MAX_BYTES) throw new IllegalArgumentException("Подписка больше 1 МБ");
                    if (android.os.SystemClock.elapsedRealtime() > deadline) throw new java.io.IOException("Время загрузки истекло");
                    out.write(buffer, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }
}
