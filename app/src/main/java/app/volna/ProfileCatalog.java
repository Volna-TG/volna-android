package app.volna;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class ProfileCatalog {
    private static List<Profiles.Entry> current;
    private static long revision = -1;
    private static final String CACHE = "public_catalog";

    private static List<Profiles.Entry> parse(JSONObject document) throws Exception {
        if (document.getLong("revision") < 1) throw new IllegalArgumentException();
        JSONArray rows = document.getJSONArray("profiles");
        if (rows.length() > 64) throw new IllegalArgumentException();
        List<Profiles.Entry> result = new ArrayList<>();
        HashSet<String> ids = new HashSet<>();
        for (int i=0; i<rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String id = row.getString("id"), name = row.getString("name"), uri = row.getString("link");
            if (!id.matches("[a-z0-9-]{1,40}") || !ids.add(id) || name.isEmpty() || name.length() > 80 ||
                    uri.length() > 8192) throw new IllegalArgumentException();
            ProxyProfile.parse(uri);
            result.add(Profiles.bundled(id, name, uri));
        }
        return Collections.unmodifiableList(result);
    }

    private static void load(JSONObject document) throws Exception {
        if (document.getLong("revision") <= revision) return;
        current = parse(document);
        revision = document.getLong("revision");
    }

    static synchronized List<Profiles.Entry> entries(Context context) {
        if (current != null) return current;
        current = Collections.emptyList();
        try (InputStream input = context.getAssets().open("profiles.json");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            for (int n; (n=input.read(buffer))!=-1;) {
                if (out.size()+n > 65536) throw new java.io.IOException();
                out.write(buffer,0,n);
            }
            load(new JSONObject(out.toString("UTF-8")));
        } catch (Exception absent) {}
        String cached = Prefs.get(context).getString(CACHE,null);
        if (cached != null) try { load(new JSONObject(cached)); } catch (Exception invalid) {}
        return current;
    }

    static boolean refresh(Context context) throws Exception {
        JSONObject document = Updates.catalog(context);
        synchronized (ProfileCatalog.class) {
            entries(context);
            long next = document.getLong("revision");
            if (next <= revision) return false;
            List<Profiles.Entry> parsed = parse(document);
            if (!Prefs.get(context).edit().putString(CACHE,document.toString()).commit())
                throw new java.io.IOException("Не удалось сохранить каталог");
            current = parsed;
            revision = next;
            return true;
        }
    }
}
