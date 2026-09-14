package com.microsoft.xal.crypto;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.jetbrains.annotations.Contract;
import org.spongycastle.jce.provider.BouncyCastleProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;


public class Ecdsa {
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String ECDSA_SIGNATURE_NAME = "NONEwithECDSA";
    private static final String EC_ALGORITHM_NAME = "secp256r1";
    private static final String KEY_ALIAS_PREFIX = "xal_";

    static {
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
    }

    private KeyPair keyPair;
    private String uniqueId;

    private static SharedPreferences getCryptoPrefs(@NonNull Context context) {
        boolean takeover = false;
        try {
            SharedPreferences sp = context.getSharedPreferences("feature_settings", 0);
            String json = sp.getString("settings_json", null);
            if (json != null) {
                org.json.JSONObject obj = new org.json.JSONObject(json);
                takeover = obj.optBoolean("launcherManagedMcLoginEnabled", false);
            }
        } catch (Throwable ignored) {}
        String name = takeover ? "org.levimc.xal.crypto" : "com.microsoft.xal.crypto";
        // 启动器导出会把密钥对 XML 直写到「版本 data/shared_prefs」（getDataDir 重定向路径），
        // 进程内可能已缓存该文件的旧实例；先逐出缓存，确保读到磁盘最新内容。
        evictPrefsCache(context, name);
        SharedPreferences sp = context.getSharedPreferences(name, 0);
        File f = new File(context.getDataDir(), "shared_prefs/" + name + ".xml");
        Log.i("Ecdsa", "getCryptoPrefs: takeover=" + takeover + " name=" + name
                + " pkg=" + context.getPackageName() + " dataDir=" + context.getDataDir()
                + " prefsFile=" + f.getAbsolutePath() + " exists=" + f.exists()
                + " id=" + sp.getString("id", ""));
        return sp;
   }

    /** 逐出 SharedPreferences 进程内静态缓存，强制下次 getSharedPreferences 从磁盘重新加载。 */
    private static void evictPrefsCache(Context context, String name) {
        try {
            File f = new File(context.getDataDir(), "shared_prefs/" + name + ".xml");
            java.lang.reflect.Field field = Class.forName("android.app.ContextImpl").getDeclaredField("sSharedPrefsCache");
            field.setAccessible(true);
            Object cache = field.get(null);
            if (cache != null) {
                Object byPkg = cache.getClass().getMethod("get", Object.class)
                        .invoke(cache, context.getPackageName());
                if (byPkg != null) {
                    byPkg.getClass().getMethod("remove", Object.class).invoke(byPkg, f);
                }
            }
        } catch (Throwable ignored) {}
    }

    @Nullable
    public static Ecdsa restoreKeyAndId(@NonNull Context context) throws ClassCastException, IllegalArgumentException, NoSuchProviderException, NoSuchAlgorithmException, InvalidKeySpecException {
        SharedPreferences sharedPreferences = getCryptoPrefs(context);
        boolean hasId = sharedPreferences.contains("id");
        boolean hasPub = sharedPreferences.contains("public");
        boolean hasPriv = sharedPreferences.contains("private");
        Log.i("Ecdsa", "restoreKeyAndId: contains id=" + hasId + " pub=" + hasPub + " priv=" + hasPriv);
        if (!hasId || !hasPub || !hasPriv) {
            Log.w("Ecdsa", "restoreKeyAndId: MISSING keys, clearing and returning null");
            SharedPreferences.Editor edit = sharedPreferences.edit();
            edit.clear();
            edit.apply();
            return null;
        }
        String string = sharedPreferences.getString("public", "");
        String string2 = sharedPreferences.getString("private", "");
        String string3 = sharedPreferences.getString("id", "");
        if (string.isEmpty() || string2.isEmpty() || string3.isEmpty()) {
            Log.w("Ecdsa", "restoreKeyAndId: EMPTY values, returning null");
            SharedPreferences.Editor edit2 = sharedPreferences.edit();
            edit2.clear();
            edit2.apply();
            return null;
        }
        try {
            byte[] bytesFromBase64String = getBytesFromBase64String(string);
            byte[] bytesFromBase64String2 = getBytesFromBase64String(string2);
            Log.i("Ecdsa", "restoreKeyAndId: decoded pub=" + bytesFromBase64String.length + " priv=" + bytesFromBase64String2.length);
            KeyFactory keyFactory = KeyFactory.getInstance("ECDSA", "SC");
            Ecdsa ecdsa = new Ecdsa();
            ecdsa.uniqueId = string3;
            ecdsa.keyPair = new KeyPair(keyFactory.generatePublic(new X509EncodedKeySpec(bytesFromBase64String)), keyFactory.generatePrivate(new PKCS8EncodedKeySpec(bytesFromBase64String2)));
            Log.i("Ecdsa", "restoreKeyAndId: SUCCESS id=" + string3);
            return ecdsa;
        } catch (Throwable t) {
            Log.e("Ecdsa", "restoreKeyAndId: parse failed", t);
            throw t;
        }
    }

    @NonNull
    @Contract(pure = true)
    private static String getKeyAlias(String str) {
        return KEY_ALIAS_PREFIX + str;
    }

    private static String getBase64StringFromBytes(byte[] bArr) {
        return Base64.encodeToString(bArr, 0, bArr.length, 11);
    }

    private static byte[] getBytesFromBase64String(String str) throws IllegalArgumentException {
        return Base64.decode(str, 11);
    }

    public void generateKey(String str) throws NoSuchProviderException, NoSuchAlgorithmException, InvalidAlgorithmParameterException {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("ECDSA", "SC");
        keyPairGenerator.initialize(new ECGenParameterSpec(EC_ALGORITHM_NAME));
        this.uniqueId = str;
        this.keyPair = keyPairGenerator.generateKeyPair();
    }

    public EccPubKey getPublicKey() {
        return new EccPubKey((ECPublicKey) this.keyPair.getPublic());
    }

    public String getUniqueId() {
        return this.uniqueId;
    }

    public boolean storeKeyPairAndId(@NonNull Context context, String str) {
        SharedPreferences.Editor edit = getCryptoPrefs(context).edit();
        edit.putString("id", str);
        edit.putString("public", getBase64StringFromBytes(this.keyPair.getPublic().getEncoded()));
        edit.putString("private", getBase64StringFromBytes(this.keyPair.getPrivate().getEncoded()));
        boolean ok = edit.commit();
        Log.i("Ecdsa", "storeKeyPairAndId: id=" + str + " committed=" + ok);
        return ok;
    }

    public byte[] sign(byte[] bArr) throws NoSuchAlgorithmException, InvalidKeyException, SignatureException {
        Signature signature = Signature.getInstance(ECDSA_SIGNATURE_NAME);
        signature.initSign(this.keyPair.getPrivate());
        signature.update(bArr);
        return toP1363SignedBuffer(signature.sign());
    }

    public byte[] hashAndSign(byte[] bArr) throws NoSuchAlgorithmException, InvalidKeyException, SignatureException {
        ShaHasher shaHasher = new ShaHasher();
        shaHasher.AddBytes(bArr);
        return sign(shaHasher.SignHash());
    }

    @NonNull
    private byte[] toP1363SignedBuffer(@NonNull byte[] bArr) {
        byte b = bArr[3];
        int i = 4 + b + 1;
        int i2 = i + 1;
        byte b2 = bArr[i];
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        writeAdjustedHalfOfAsn1ToP1363(bArr, 4, b, byteArrayOutputStream);
        writeAdjustedHalfOfAsn1ToP1363(bArr, i2, b2, byteArrayOutputStream);
        return byteArrayOutputStream.toByteArray();
    }

    private void writeAdjustedHalfOfAsn1ToP1363(byte[] bArr, int i, int i2, ByteArrayOutputStream byteArrayOutputStream) {
        if (i2 > 32) {
            byteArrayOutputStream.write(bArr, i + (i2 - 32), 32);
        } else if (i2 < 32) {
            int i3 = 32 - i2;
            byteArrayOutputStream.write(new byte[i3], 0, i3);
            byteArrayOutputStream.write(bArr, i, i2);
        } else {
            byteArrayOutputStream.write(bArr, i, i2);
        }
    }
}