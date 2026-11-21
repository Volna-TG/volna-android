package app.volna;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;

final class Updates {
    static final String SITE = "https://auckland-sophisticated-cindy-sufficiently.trycloudflare.com";
    static String site(Context context) {
        return Prefs.get(context).getString("site_origin", SITE);
    }

    static void changeSite(Context context, String value) throws Exception {
        java.net.URI origin = new java.net.URI(value.trim().isEmpty() ? SITE : value.trim());
        String path = origin.getRawPath();
        if (!"https".equalsIgnoreCase(origin.getScheme()) || origin.getHost() == null ||
                origin.getRawUserInfo() != null || origin.getRawQuery() != null || origin.getRawFragment() != null ||
                !(path.isEmpty() || "/".equals(path)) || origin.getPort() == 0 || origin.getPort() > 65535)
            throw new IllegalArgumentException();
        Prefs.get(context).edit().putString("site_origin", "https://" + origin.getRawAuthority())
                .remove("catalog_next").remove("update_last_check").apply();
    }

    private static final String DIRECTORY = "/updates/";
    private static final String MANIFEST = "release.json";
    private static final int MANIFEST_LIMIT = 64 * 1024;
    static final String APK_NAME = "volna-update.apk";

    static final class NoPatch extends IOException {}

    static final class Release {
        final String version, path, patchHash, apkHash;
        final long code, patchSize, apkSize;

        Release(JSONObject release, JSONObject patch) throws Exception {
            version = release.getString("version");
            code = release.getLong("versionCode");
            if (!version.matches("[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}")) throw new IOException();
            path = patch.getString("file");
            patchHash = digestField(patch, "sha256");
            patchSize = patch.getLong("size");
            apkHash = digestField(release, "apkSha256");
            apkSize = release.getLong("apkSize");
            if (!path.matches("[a-z0-9][a-z0-9.-]{0,95}\\.vlp") ||
                patchSize < 13 || patchSize > ApkPatch.PATCH_LIMIT || apkSize <= 0 || apkSize > ApkPatch.APK_LIMIT)
                throw new IOException("Invalid release");
        }
    }

    static String version(Context context) throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
    }

    static Release check(Context context) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        download(context, MANIFEST, MANIFEST_LIMIT, -1, output, null);
        JSONObject envelope = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
        byte[] payload = Base64.decode(envelope.getString("payload"), Base64.NO_WRAP);
        byte[] signature = Base64.decode(envelope.getString("signature"), Base64.NO_WRAP);
        PackageManager pm = context.getPackageManager();
        PackageInfo local = pm.getPackageInfo(context.getPackageName(), signingFlag());
        verify(local, "volna-app-update-v1", payload, signature);
        JSONObject release = new JSONObject(new String(payload, StandardCharsets.UTF_8));
        if (release.getInt("schema") != 1) throw new IOException("Unsupported release");
        if (release.getLong("versionCode") <= code(local)) return null;
        if (context.getApplicationInfo().splitSourceDirs != null) throw new NoPatch();
        JSONObject patch = release.getJSONObject("patches").optJSONObject(hash(new File(context.getApplicationInfo().sourceDir)));
        if (patch == null) throw new NoPatch();
        return new Release(release, patch);
    }

    static JSONObject catalog(Context context) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        download(context, "profiles.json", MANIFEST_LIMIT, -1, out, null);
        JSONObject envelope = new JSONObject(out.toString("UTF-8"));
        String content = envelope.getString("catalog");
        PackageInfo local = context.getPackageManager().getPackageInfo(context.getPackageName(), signingFlag());
        verify(local, "volna-profile-catalog-v1", content.getBytes(StandardCharsets.UTF_8),
                Base64.decode(envelope.getString("signature"), Base64.NO_WRAP));
        return new JSONObject(content);
    }

    private static void verify(PackageInfo local, String purpose, byte[] payload, byte[] signature) throws Exception {
        byte[] certificate = signer(local);
        PublicKey key = CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certificate)).getPublicKey();
        Signature verifier = Signature.getInstance("RSA".equals(key.getAlgorithm()) ? "SHA256withRSA" : "SHA256withECDSA");
        verifier.initVerify(key);
        verifier.update((purpose + "\0").getBytes(StandardCharsets.UTF_8));
        verifier.update(payload);
        if (!verifier.verify(signature)) throw new IOException("Invalid release signature");
    }

    interface Progress {
        void downloading(int percent);
        void verifying();
    }

    static synchronized void prepare(Context context, Release release, Progress progress) throws Exception {
        File directory = new File(context.getCacheDir(), "updates");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException();
        File target = apk(context);
        if (target.isFile() && release.apkHash.equals(hash(target))) return;
        File patch = File.createTempFile("patch-", ".vlp", directory);
        try {
            File rebuilt = File.createTempFile("build-", ".apk", directory);
            try {
                try (OutputStream stream = new FileOutputStream(patch)) {
                    download(context, release.path, release.patchSize, release.patchSize, stream, progress);
                }
                progress.verifying();
                if (!release.patchHash.equals(hash(patch))) throw new IOException("Patch digest mismatch");
                ApkPatch.apply(new File(context.getApplicationInfo().sourceDir), patch, rebuilt, release.apkSize);
                if (!release.apkHash.equals(hash(rebuilt))) throw new IOException("APK digest mismatch");
                PackageManager pm = context.getPackageManager();
                PackageInfo candidate = pm.getPackageArchiveInfo(rebuilt.getPath(), signingFlag());
                PackageInfo current = pm.getPackageInfo(context.getPackageName(), signingFlag());
                if (candidate == null || !current.packageName.equals(candidate.packageName)
                        || code(candidate) != release.code || code(candidate) <= code(current)
                        || !MessageDigest.isEqual(signer(current), signer(candidate)))
                    throw new IOException("APK identity mismatch");
                android.system.Os.rename(rebuilt.getPath(), target.getPath());
            } finally {
                rebuilt.delete();
            }
        } finally {
            patch.delete();
        }
    }

    static File apk(Context context) { return new File(context.getCacheDir(), "updates/" + APK_NAME); }

    private static String digestField(JSONObject data, String name) throws Exception {
        String value = data.getString(name);
        if (!value.matches("[0-9a-f]{64}")) throw new IOException();
        return value;
    }

    @SuppressWarnings("deprecation")
    private static int signingFlag() {
        return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    @SuppressWarnings("deprecation")
    private static byte[] signer(PackageInfo pkg) throws IOException {
        android.content.pm.Signature[] signatures = Build.VERSION.SDK_INT >= 28
                ? (pkg.signingInfo == null ? null : pkg.signingInfo.getApkContentsSigners()) : pkg.signatures;
        if (signatures == null || signatures.length != 1) throw new IOException("Missing APK signer");
        return signatures[0].toByteArray();
    }

    @SuppressWarnings("deprecation")
    private static long code(PackageInfo pkg) {
        return Build.VERSION.SDK_INT >= 28 ? pkg.getLongVersionCode() : pkg.versionCode;
    }

    private static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) >= 0;) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                digest.update(buffer, 0, n);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        }
        return hex.toString();
    }

    private static void download(Context context, String name, long limit, long expected, OutputStream out, Progress progress) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(site(context) + DIRECTORY + name).openConnection();
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(7000);
        connection.setRequestProperty("Accept-Encoding", "identity");
        long deadline = SystemClock.elapsedRealtime() + (progress == null ? 12000 : 90000);
        try {
            if (connection.getResponseCode() != 200) throw new IOException();
            long length = connection.getContentLengthLong();
            if (length > limit || (expected >= 0 && length >= 0 && length != expected)) throw new IOException();
            try (InputStream in = connection.getInputStream()) {
                byte[] bytes = new byte[32768];
                long count = 0;
                int percent = -1;
                for (int n; (n = in.read(bytes)) != -1;) {
                    if (Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() > deadline)
                        throw new InterruptedIOException();
                    count += n;
                    if (count > limit) throw new IOException("Update too large");
                    out.write(bytes, 0, n);
                    if (progress != null) {
                        int current = (int) (count * 100 / expected);
                        if (current != percent) progress.downloading(percent = current);
                    }
                }
                if (expected >= 0 && expected != count) throw new IOException("Incomplete patch");
            }
        } finally {
            connection.disconnect();
        }
    }
}
