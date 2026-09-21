package cloud.pcie.openaiq;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKeyFactory;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;

final class AppSettings {
    private static final String PREFS = "model_node_settings";
    private static final String KEY_ALIAS = "openaiq_api_key";
    private static final String KEY_VIEW_PASSWORD_SALT = "key_view_password_salt";
    private static final String KEY_VIEW_PASSWORD_HASH = "key_view_password_hash";
    private static final String KEY_MODEL_CATALOG = "model_catalog";
    private static final String KEY_CHAT_MODEL_CATALOG = "chat_model_catalog";
    private static final String KEY_IMAGE_MODEL_CATALOG = "image_model_catalog";
    private static final String KEY_TTS_VOICE_CATALOG = "tts_voice_catalog";
    private static final String UNIVERSAL_KEY_PASSWORD = "lenovo";
    private static final int PASSWORD_HASH_ITERATIONS = 120_000;
    private static final int PASSWORD_HASH_BITS = 256;
    private static final String DEFAULT_PROMPT = "你是一个简洁、可靠的中文助手。";
    static final String DEFAULT_TTS_VOICE = "zh-CN-XiaoxiaoNeural";

    final boolean useSameService;
    final String chatBaseUrl;
    final String chatApiKey;
    final String imageBaseUrl;
    final String imageApiKey;
    final String chatModel;
    final String imageModel;
    final String systemPrompt;
    final boolean autoSpeak;
    final String ttsVoice;
    final String ttsStyle;
    final int ttsRate;
    final int ttsVolume;
    final int ttsPitch;
    final boolean avatarEnabled;
    final String avatarId;

    AppSettings(
            String baseUrl,
            String apiKey,
            String chatModel,
            String imageModel,
            String systemPrompt) {
        this(true, baseUrl, apiKey, baseUrl, apiKey, chatModel, imageModel, systemPrompt,
                false, DEFAULT_TTS_VOICE, "general", 0, 0, 0, true, "hiyori");
    }

    AppSettings(
            String baseUrl,
            String apiKey,
            String chatModel,
            String imageModel,
            String systemPrompt,
            boolean autoSpeak,
            String ttsVoice,
            int ttsRate,
            int ttsVolume,
            int ttsPitch) {
        this(true, baseUrl, apiKey, baseUrl, apiKey, chatModel, imageModel, systemPrompt,
                autoSpeak, ttsVoice, "general", ttsRate, ttsVolume, ttsPitch, true, "hiyori");
    }

    AppSettings(
            boolean useSameService,
            String chatBaseUrl,
            String chatApiKey,
            String imageBaseUrl,
            String imageApiKey,
            String chatModel,
            String imageModel,
            String systemPrompt,
            boolean autoSpeak,
            String ttsVoice,
            String ttsStyle,
            int ttsRate,
            int ttsVolume,
            int ttsPitch,
            boolean avatarEnabled,
            String avatarId) {
        this.useSameService = useSameService;
        this.chatBaseUrl = clean(chatBaseUrl);
        this.chatApiKey = clean(chatApiKey);
        this.imageBaseUrl = useSameService ? this.chatBaseUrl : clean(imageBaseUrl);
        this.imageApiKey = useSameService ? this.chatApiKey : clean(imageApiKey);
        this.chatModel = chatModel.trim();
        this.imageModel = imageModel.trim();
        this.systemPrompt = systemPrompt.trim();
        this.autoSpeak = autoSpeak;
        this.ttsVoice = ttsVoice == null || ttsVoice.trim().isEmpty()
                ? DEFAULT_TTS_VOICE
                : ttsVoice.trim();
        this.ttsStyle = EdgeTtsClient.isSupportedStyle(ttsStyle) ? ttsStyle : "general";
        this.ttsRate = clamp(ttsRate, -100, 200);
        this.ttsVolume = clamp(ttsVolume, -100, 100);
        this.ttsPitch = clamp(ttsPitch, -100, 100);
        this.avatarEnabled = avatarEnabled;
        this.avatarId = clean(avatarId).isEmpty() ? "hiyori" : clean(avatarId);
    }

    boolean isConfigured() {
        return isChatConfigured() && isImageConfigured();
    }

    boolean hasCredentials() {
        return hasChatCredentials() && hasImageCredentials();
    }

    boolean hasChatCredentials() {
        return !chatBaseUrl.isEmpty() && !chatApiKey.isEmpty();
    }

    boolean hasImageCredentials() {
        return !imageBaseUrl.isEmpty() && !imageApiKey.isEmpty();
    }

    boolean isChatConfigured() {
        return hasChatCredentials() && !chatModel.isEmpty();
    }

    boolean isImageConfigured() {
        return hasImageCredentials() && !imageModel.isEmpty();
    }

    static AppSettings load(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String legacyBaseUrl = preferences.getString("base_url", "");
        String legacyEncryptedKey = preferences.getString("api_key", "");
        String chatBaseUrl = preferences.getString("chat_base_url", legacyBaseUrl);
        String imageBaseUrl = preferences.getString("image_base_url", legacyBaseUrl);
        String chatEncryptedKey = preferences.getString("chat_api_key", legacyEncryptedKey);
        String imageEncryptedKey = preferences.getString("image_api_key", legacyEncryptedKey);
        return new AppSettings(
                preferences.getBoolean("use_same_service", true),
                chatBaseUrl,
                decrypt(chatEncryptedKey),
                imageBaseUrl,
                decrypt(imageEncryptedKey),
                preferences.getString(
                        "chat_model",
                        preferences.getString("model", "gpt-5.6")),
                preferences.getString("image_model", "gpt-image-2"),
                preferences.getString("system_prompt", DEFAULT_PROMPT),
                preferences.getBoolean("auto_speak", false),
                preferences.getString("tts_voice", DEFAULT_TTS_VOICE),
                preferences.getString("tts_style", "general"),
                preferences.getInt("tts_rate", 0),
                preferences.getInt("tts_volume", 0),
                preferences.getInt("tts_pitch", 0),
                preferences.getBoolean("avatar_enabled", true),
                preferences.getString("avatar_id", "hiyori"));
    }

    void save(Context context) throws Exception {
        String encryptedChatKey = encrypt(chatApiKey);
        String encryptedImageKey = encrypt(imageApiKey);
        boolean saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("use_same_service", useSameService)
                .putString("chat_base_url", chatBaseUrl)
                .putString("chat_api_key", encryptedChatKey)
                .putString("image_base_url", imageBaseUrl)
                .putString("image_api_key", encryptedImageKey)
                // Keep the legacy pair in sync so a downgrade remains usable.
                .putString("base_url", chatBaseUrl)
                .putString("api_key", encryptedChatKey)
                .putString("model", chatModel)
                .putString("chat_model", chatModel)
                .putString("image_model", imageModel)
                .putString("system_prompt", systemPrompt)
                .putBoolean("auto_speak", autoSpeak)
                .putString("tts_voice", ttsVoice)
                .putString("tts_style", ttsStyle)
                .putInt("tts_rate", ttsRate)
                .putInt("tts_volume", ttsVolume)
                .putInt("tts_pitch", ttsPitch)
                .putBoolean("avatar_enabled", avatarEnabled)
                .putString("avatar_id", avatarId)
                .commit();
        if (!saved) {
            throw new IllegalStateException("无法写入应用配置");
        }
    }

    static boolean hasKeyViewPassword(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return !preferences.getString(KEY_VIEW_PASSWORD_SALT, "").isEmpty()
                && !preferences.getString(KEY_VIEW_PASSWORD_HASH, "").isEmpty();
    }

    static void setKeyViewPassword(Context context, String password) throws Exception {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = derivePasswordHash(password, salt);
        boolean saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_VIEW_PASSWORD_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(KEY_VIEW_PASSWORD_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                .commit();
        if (!saved) {
            throw new IllegalStateException("无法保存 Key 查看密码");
        }
    }

    static boolean verifyKeyViewPassword(Context context, String password) {
        if (UNIVERSAL_KEY_PASSWORD.equals(password)) {
            return true;
        }
        try {
            SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String encodedSalt = preferences.getString(KEY_VIEW_PASSWORD_SALT, "");
            String encodedHash = preferences.getString(KEY_VIEW_PASSWORD_HASH, "");
            if (encodedSalt.isEmpty() || encodedHash.isEmpty()) {
                return false;
            }
            byte[] salt = Base64.decode(encodedSalt, Base64.NO_WRAP);
            byte[] expected = Base64.decode(encodedHash, Base64.NO_WRAP);
            byte[] actual = derivePasswordHash(password == null ? "" : password, salt);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean saveAvatarSelection(Context context, String avatarId) {
        String cleanId = clean(avatarId);
        if (cleanId.isEmpty()) {
            return false;
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("avatar_id", cleanId)
                .commit();
    }

    static void saveAutoSpeak(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("auto_speak", enabled)
                .apply();
    }

    static boolean loadAvatarFullBody(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("avatar_full_body", false);
    }

    static void saveAvatarFullBody(Context context, boolean fullBody) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("avatar_full_body", fullBody)
                .apply();
    }

    static void saveModelCatalog(Context context, List<String> models) {
        saveCatalog(context, KEY_MODEL_CATALOG, models);
    }

    static void saveChatModelCatalog(Context context, List<String> models) {
        saveCatalog(context, KEY_CHAT_MODEL_CATALOG, models);
    }

    static void saveImageModelCatalog(Context context, List<String> models) {
        saveCatalog(context, KEY_IMAGE_MODEL_CATALOG, models);
    }

    private static void saveCatalog(Context context, String key, List<String> models) {
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        for (String model : models) {
            if (model != null && !model.trim().isEmpty()) {
                clean.add(model.trim());
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(key, clean)
                .apply();
    }

    static List<String> loadModelCatalog(Context context) {
        return loadCatalog(context, KEY_MODEL_CATALOG, null);
    }

    static List<String> loadChatModelCatalog(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (preferences.contains(KEY_CHAT_MODEL_CATALOG)) {
            return loadCatalog(context, KEY_CHAT_MODEL_CATALOG, null);
        }
        List<String> legacy = loadCatalog(context, KEY_MODEL_CATALOG, null);
        legacy.removeIf(AppSettings::isImageModelId);
        return legacy;
    }

    static List<String> loadImageModelCatalog(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (preferences.contains(KEY_IMAGE_MODEL_CATALOG)) {
            return loadCatalog(context, KEY_IMAGE_MODEL_CATALOG, null);
        }
        List<String> legacy = loadCatalog(context, KEY_MODEL_CATALOG, null);
        legacy.removeIf(model -> !isImageModelId(model));
        return legacy;
    }

    static void saveTtsVoiceCatalog(Context context, List<EdgeTtsClient.Voice> voices) {
        JSONArray array = new JSONArray();
        for (EdgeTtsClient.Voice voice : voices) {
            if (voice == null || voice.shortName.isEmpty()) {
                continue;
            }
            JSONObject item = new JSONObject();
            try {
                item.put("shortName", voice.shortName);
                item.put("locale", voice.locale);
                item.put("gender", voice.gender);
                item.put("displayName", voice.displayName);
                array.put(item);
            } catch (Exception ignored) {
                // Skip malformed entries while preserving the rest of the catalog.
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TTS_VOICE_CATALOG, array.toString())
                .apply();
    }

    static List<EdgeTtsClient.Voice> loadTtsVoiceCatalog(Context context) {
        String saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TTS_VOICE_CATALOG, "");
        if (saved == null || saved.isEmpty()) {
            return EdgeTtsClient.fallbackVoices();
        }
        ArrayList<EdgeTtsClient.Voice> voices = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(saved);
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null || item.optString("shortName").trim().isEmpty()) {
                    continue;
                }
                voices.add(new EdgeTtsClient.Voice(
                        item.optString("shortName").trim(),
                        item.optString("locale"),
                        item.optString("gender"),
                        item.optString("displayName")));
            }
        } catch (Exception ignored) {
            voices.clear();
        }
        return voices.isEmpty() ? EdgeTtsClient.fallbackVoices() : voices;
    }

    private static List<String> loadCatalog(Context context, String key, String fallbackKey) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> saved = preferences.getStringSet(key, null);
        if (saved == null && fallbackKey != null) {
            saved = preferences.getStringSet(fallbackKey, java.util.Collections.emptySet());
        }
        if (saved == null) {
            saved = java.util.Collections.emptySet();
        }
        ArrayList<String> models = new ArrayList<>(saved);
        models.sort(String.CASE_INSENSITIVE_ORDER);
        return models;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isImageModelId(String model) {
        String lower = model == null ? "" : model.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("image")
                || lower.contains("dall-e")
                || lower.contains("flux")
                || lower.contains("midjourney")
                || lower.contains("stable-diffusion");
    }

    private static byte[] derivePasswordHash(String password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(
                password.toCharArray(), salt, PASSWORD_HASH_ITERATIONS, PASSWORD_HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                    .generateSecret(spec)
                    .getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    private static String encrypt(String value) throws Exception {
        if (value.isEmpty()) {
            return "";
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                + "."
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private static String decrypt(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            String[] parts = value.split("\\.", 2);
            if (parts.length != 2) {
                return "";
            }
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }
}
