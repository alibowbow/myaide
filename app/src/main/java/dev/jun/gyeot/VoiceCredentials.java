package dev.jun.gyeot;

import dev.jun.gyeot.core.RealtimeVoice;

/**
 * What a realtime voice connects with, for the main chat's 음성 대화 (VoiceChat) and the voice assistant on GPT or Grok
 * (RealtimeSession) alike. GPT (OpenAI Realtime): a saved OpenAI voice key, else the Codex sign-in's token (the ChatGPT
 * plan), else the OpenAI connection's own key when that connection is in API-key mode. Grok (xAI Voice Agent): a saved xAI
 * voice key, else the Grok sign-in's token.
 */
final class VoiceCredentials {
    private VoiceCredentials() {}
    /** The bearer token, and whether it is a sign-in's (refreshed once when the server turns it down) rather than a key. */
    record Credential(String token, boolean signIn) {}

    /** Whether this voice has a key or a sign-in to start with. Reads no token, so the main thread may ask. */
    static boolean usable(AppStore store, RealtimeVoice.Kind kind) {
        if (kind == RealtimeVoice.Kind.GPT)
            return store.secrets.has(SecretStore.OPENAI_VOICE) || store.codexImages.connected() || "key".equals(store.openai.describe().optString("mode"));
        return store.secrets.has(SecretStore.XAI_VOICE) || store.grok.connected();
    }
    /** Whether the voice assistant can start as it is set: its realtime voice's key or sign-in, or for Gemini Live the Gemini key. */
    static boolean assistantUsable(AppStore store) {
        RealtimeVoice.Kind kind = store.assistantRealtime();
        return kind == null ? store.secrets.hasKey() : usable(store, kind);
    }
    /**
     * The token to connect with, or null when there is neither a key nor a sign-in. rejected is a sign-in's token the server
     * just turned down, which is refreshed; null for the saved one. A sign-in may refresh its token: never on the main thread.
     */
    static Credential get(AppStore store, RealtimeVoice.Kind kind, String rejected) throws Exception {
        if (kind == RealtimeVoice.Kind.GPT) {
            String key = store.secrets.get(SecretStore.OPENAI_VOICE);
            if (!key.isBlank()) return new Credential(key, false);
            if (store.codexImages.connected()) return new Credential(store.codexImages.voiceToken(rejected), true);
            if ("key".equals(store.openai.describe().optString("mode"))) return new Credential(store.openai.bearer(), false);
            return null;
        }
        String key = store.secrets.get(SecretStore.XAI_VOICE);
        if (!key.isBlank()) return new Credential(key, false);
        if (store.grok.connected()) return new Credential(store.grok.voiceToken(rejected), true);
        return null;
    }
    /** What the voice assistant says when its realtime voice has neither a key nor a sign-in. */
    static String assistantMissing(RealtimeVoice.Kind kind) {
        return kind == RealtimeVoice.Kind.GPT
            ? "GPT 음성으로 음성 비서를 쓰려면 Codex 로그인(ChatGPT 구독)이나 OpenAI API 키가 필요해요. 설정 → 목소리와 대화에서 로그인해 주세요."
            : "Grok 실시간 음성으로 음성 비서를 쓰려면 Grok 구독 로그인이나 xAI API 키가 필요해요. 설정 → 목소리와 대화에서 로그인해 주세요.";
    }
}
