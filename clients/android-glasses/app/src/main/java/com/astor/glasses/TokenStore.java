package com.astor.glasses;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The backend access token of this phone. It is encrypted with a key that lives in the Android
 * Keystore and never leaves the device; the application is excluded from backups.
 */
final class TokenStore {
    private static final String KEY_ALIAS = "com.astor.glasses.backend", PREFS = "astor-access";

    private final SharedPreferences prefs;

    TokenStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** An empty token removes the stored one. False when the Keystore refused. */
    boolean save(String token) {
        if (token == null || token.isEmpty()) {
            prefs.edit().clear().apply();
            return true;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] sealed = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            prefs.edit().putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .putString("token", Base64.encodeToString(sealed, Base64.NO_WRAP)).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The stored token, or null when there is none or it can no longer be opened. */
    String load() {
        String iv = prefs.getString("iv", null), sealed = prefs.getString("token", null);
        if (iv == null || sealed == null) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(sealed, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.getKey(KEY_ALIAS, null) instanceof SecretKey) return (SecretKey) store.getKey(KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
