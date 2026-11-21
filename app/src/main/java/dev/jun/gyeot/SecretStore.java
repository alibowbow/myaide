package dev.jun.gyeot;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Credentials encrypted with an Android Keystore AES-GCM key; preferences are excluded from backup. */
final class SecretStore {
    static final String GROK = "grokOAuth", NIM = "nvidiaNim";
    /** 네이버 길안내: the user's NAVER API HUB application, {"id","secret"} (NaverPlacesClient). */
    static final String NAVER_PLACES = "naverPlaces";
    /** The retired Gemini subscription sign-in's slot: AppStore deletes it at start, and nothing reads it. */
    static final String GEMINI_PLAN = "geminiPlan";
    /**
     * ARCHIVE is the 마이아카이브 connection address, which carries its key; GITHUB_CODE is the token 작업창 codes on GitHub with;
     * GEMINI_WORK is an optional second Gemini key for 작업창, so its usage can come from another project than the voice's.
     */
    static final String GEMINI = "gemini", OPENAI = "openai", CODEX_IMAGES = "codexImages", GITHUB = "github", ARCHIVE = "myarchive", CLAUDE = "claude", GITHUB_CODE = "githubCode", GEMINI_WORK = "geminiWork";
    /** The retired Decisions API key's slot: AppStore deletes it at start, and nothing reads it. */
    static final String RETIRED_DECISIONS = "openaiDecisions";
    /** API keys for the main chat's spoken conversation only (VoiceChat), used before a sign-in when one is saved. */
    static final String OPENAI_VOICE = "openaiVoice", XAI_VOICE = "xaiVoice";
    private static final String ALIAS = "gyeot.gemini.key.v1";
    private final Context context;
    SecretStore(Context context) { this.context = context; }
    private SharedPreferences preferences() { return context.getSharedPreferences("secret", Context.MODE_PRIVATE); }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
    String get() throws Exception { return get(GEMINI); }
    void put(String value) throws Exception { checkKey(value); put(GEMINI, value); }
    boolean hasKey() { return has(GEMINI); }
    /** 작업창's Gemini key: its own when one was saved, else the voice key. */
    String workKey() throws Exception { String own = get(GEMINI_WORK); return own.isBlank() ? get(GEMINI) : own; }
    boolean hasWorkKey() { return has(GEMINI_WORK) || hasKey(); }
    void putWork(String value) throws Exception { checkKey(value); put(GEMINI_WORK, value); }
    private static void checkKey(String value) {
        if (!value.isBlank() && (value.contains("\n") || value.contains(" ") || value.length() < 16)) throw new IllegalArgumentException("API 키 형식을 확인해 주세요.");
    }
    synchronized String get(String name) throws Exception {
        String value = preferences().getString(name, "");
        if (value.isBlank()) return "";
        String[] parts = value.split(":", 2);
        if (parts.length != 2) throw new IllegalStateException("저장된 키를 읽지 못했어요. 설정에서 다시 입력해 주세요.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), java.nio.charset.StandardCharsets.UTF_8);
    }
    synchronized void put(String name, String value) throws Exception {
        if (value.isBlank()) { if (!preferences().edit().remove(name).commit()) throw new IllegalStateException("키를 지우지 못했어요."); return; }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        String saved = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)), Base64.NO_WRAP);
        if (!preferences().edit().putString(name, saved).commit()) throw new IllegalStateException("키를 저장하지 못했어요.");
    }
    boolean has(String name) { return preferences().contains(name); }
    /**
     * What is kept under name, still encrypted: every write changes it (a fresh IV), so a cache of something worked out from
     * the value can tell it changed without decrypting it (AppStore.usageIdentity). Never a credential in the clear.
     */
    String stamp(String name) { return preferences().getString(name, ""); }
}
