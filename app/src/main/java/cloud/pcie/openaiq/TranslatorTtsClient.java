package cloud.pcie.openaiq;

import android.util.Log;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.ByteString;

/** Microsoft speech transport used by VoiceCraft; see assets/licenses/VoiceCraft-MIT.txt. */
final class TranslatorTtsClient {
    private static final String ENDPOINT_URL =
            "https://dev.microsofttranslator.com/apps/endpoint?api-version=1.0";
    // Public client signature material from the referenced MIT-licensed implementation.
    private static final String SIGNING_KEY =
            "oik6PdDdMnOXemTbwvMn9de/h9lFnfBaCWbGMMZqqoSaQaqUOqjVGm5NqsmjcBI1x+sS9ugjB55HEJWRiFXYFw==";
    private static final Object TOKEN_LOCK = new Object();
    private static Endpoint cachedEndpoint;
    private final OkHttpClient http;
    private final ExecutorService workers = Executors.newFixedThreadPool(3);

    interface Listener {
        File save(byte[] bytes) throws Exception;
        void onSuccess(File file);
        void onError(String message);
    }

    private static final class Endpoint {
        final String region;
        final String token;
        final long expiresAt;
        Endpoint(String region, String token, long expiresAt) {
            this.region = region;
            this.token = token;
            this.expiresAt = expiresAt;
        }
    }

    TranslatorTtsClient(OkHttpClient http) { this.http = http; }

    EdgeTtsClient.Handle synthesize(String text, String voice, String style,
            int rate, int volume, int pitch, Listener listener) {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<Call> activeCall = new AtomicReference<>();
        Future<?> future = workers.submit(() -> {
            try {
                Endpoint endpoint = endpoint();
                if (cancelled.get()) return;
                Request request = new Request.Builder()
                        .url("https://" + endpoint.region + ".tts.speech.microsoft.com/cognitiveservices/v1")
                        .header("Authorization", endpoint.token)
                        .header("X-Microsoft-OutputFormat", "audio-24khz-48kbitrate-mono-mp3")
                        .post(RequestBody.create(ssml(text, voice, style, rate, volume, pitch),
                                MediaType.get("application/ssml+xml; charset=utf-8")))
                        .build();
                Call call = http.newCall(request);
                activeCall.set(call);
                if (cancelled.get()) { call.cancel(); return; }
                byte[] bytes;
                try (Response response = call.execute()) {
                    if (response.code() == 401 || response.code() == 403) {
                        synchronized (TOKEN_LOCK) {
                            if (cachedEndpoint == endpoint) cachedEndpoint = null;
                        }
                    }
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IOException("微软语音合成失败（HTTP " + response.code() + "）");
                    }
                    if (!response.header("Content-Type", "").toLowerCase(Locale.ROOT).startsWith("audio/")) {
                        throw new IOException("微软语音服务未返回音频");
                    }
                    ByteArrayOutputStream audio = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = response.body().byteStream().read(buffer)) != -1) {
                        if (cancelled.get()) return;
                        if (audio.size() + count > 10 * 1024 * 1024) throw new IOException("语音文件超过 10 MB");
                        audio.write(buffer, 0, count);
                    }
                    bytes = audio.toByteArray();
                    if (bytes.length == 0) throw new IOException("微软语音服务返回了空音频");
                }
                if (cancelled.get()) return;
                File file = listener.save(bytes);
                if (cancelled.get()) { file.delete(); return; }
                Log.d("EdgeTtsClient", "Translator TTS audio: voice=" + voice + ", " + bytes.length + " bytes");
                listener.onSuccess(file);
            } catch (Exception exception) {
                if (!cancelled.get()) {
                    // Never log authorization headers, token payloads or user text.
                    Log.w("EdgeTtsClient", "Translator TTS failed: voice=" + voice
                            + ", " + exception.getClass().getSimpleName());
                    String message = exception.getMessage();
                    listener.onError(message == null ? "微软语音合成失败，请稍后重试" : message);
                }
            }
        });
        return () -> {
            cancelled.set(true);
            Call call = activeCall.get();
            if (call != null) call.cancel();
            future.cancel(true);
        };
    }

    private Endpoint endpoint() throws Exception {
        // One refresh for all concurrent prefetches; no network work runs on the UI thread.
        synchronized (TOKEN_LOCK) {
            if (cachedEndpoint != null && System.currentTimeMillis() < cachedEndpoint.expiresAt - 180_000L) {
                return cachedEndpoint;
            }
            SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("GMT"));
            String date = format.format(new Date()).toLowerCase(Locale.ROOT);
            String id = UUID.randomUUID().toString().replace("-", "");
            Request request = new Request.Builder().url(ENDPOINT_URL)
                    .header("Accept-Language", "zh-Hans")
                    .header("X-ClientVersion", "4.0.530a 5fe1dc6c")
                    .header("X-UserId", "0f04d16a175c411e")
                    .header("X-HomeGeographicRegion", "zh-Hans-CN")
                    .header("X-ClientTraceId", id)
                    .header("X-MT-Signature", signature(date, id))
                    .post(RequestBody.create(new byte[0], MediaType.get("application/json; charset=utf-8")))
                    .build();
            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IOException("获取微软语音令牌失败（HTTP " + response.code() + "）");
                }
                JSONObject value = new JSONObject(response.body().string());
                String region = value.getString("r");
                String token = value.getString("t");
                if (!region.matches("[a-z0-9-]{1,50}")) throw new IOException("微软语音区域无效");
                String[] parts = token.split("\\.");
                ByteString payload = parts.length >= 2 ? ByteString.decodeBase64(parts[1]) : null;
                if (payload == null) throw new IOException("微软语音令牌无效");
                long expiry = new JSONObject(payload.utf8()).getLong("exp") * 1000L;
                cachedEndpoint = new Endpoint(region, token, expiry);
                return cachedEndpoint;
            }
        }
    }

    static String signature(String date, String id) throws Exception {
        String encodedUrl = URLEncoder.encode(ENDPOINT_URL.substring("https://".length()), "UTF-8");
        String message = ("MSTranslatorAndroidApp" + encodedUrl + date + id).toLowerCase(Locale.ROOT);
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(ByteString.decodeBase64(SIGNING_KEY).toByteArray(), "HmacSHA256"));
        String signed = ByteString.of(hmac.doFinal(message.getBytes(StandardCharsets.UTF_8))).base64();
        return "MSTranslatorAndroidApp::" + signed + "::" + date + "::" + id;
    }

    static String ssml(String text, String voice, String style, int rate, int volume, int pitch) {
        String content = "<prosody rate=\"" + signed(rate, -100, 200, "%")
                + "\" volume=\"" + signed(volume, -100, 100, "%")
                + "\" pitch=\"" + signed(pitch, -100, 100, "Hz") + "\">" + xml(text) + "</prosody>";
        if (EdgeTtsClient.isSupportedStyle(style) && !"general".equals(style)) {
            content = "<mstts:express-as style=\"" + xml(style)
                    + "\" styledegree=\"2.0\">" + content + "</mstts:express-as>";
        }
        return "<speak xmlns=\"http://www.w3.org/2001/10/synthesis\""
                + " xmlns:mstts=\"http://www.w3.org/2001/mstts\" version=\"1.0\" xml:lang=\"zh-CN\">"
                + "<voice name=\"" + xml(voice) + "\">" + content + "</voice></speak>";
    }

    private static String signed(int value, int min, int max, String unit) {
        int bounded = Math.max(min, Math.min(max, value));
        return (bounded >= 0 ? "+" : "") + bounded + unit;
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    void shutdown() { workers.shutdownNow(); }
}
