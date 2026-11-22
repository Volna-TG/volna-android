package app.volna;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class ProfileVault {
    private static final String ALIAS = "volna.imports.v1";
    static final String PREFIX = "gcm1.";

    private static SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey key = (SecretKey)store.getKey(ALIAS,null);
        if (key != null || !create) return key;
        KeyGenerator gen = KeyGenerator.getInstance("AES","AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return gen.generateKey();
    }

    static String seal(String profiles) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,key(true));
        byte[] bytes = profiles.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] data = cipher.doFinal(bytes);
            byte[] blob = ByteBuffer.allocate(12+data.length).put(cipher.getIV()).put(data).array();
            return PREFIX+Base64.encodeToString(blob,Base64.NO_WRAP);
        } finally { Arrays.fill(bytes,(byte)0); }
    }

    static String open(String value) throws Exception {
        byte[] blob = Base64.decode(value.substring(PREFIX.length()),Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(false),new GCMParameterSpec(128,blob,0,12));
        byte[] bytes = cipher.doFinal(blob,12,blob.length-12);
        try { return new String(bytes,StandardCharsets.UTF_8); }
        finally { Arrays.fill(bytes,(byte)0); }
    }
}
