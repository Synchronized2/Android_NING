package cloud.pcie.openaiq;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

final class EdgeTtsClient {
    private static final String TAG = "EdgeTtsClient";
    private static final String TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String VOICES_URL = "https://speech.platform.bing.com/consumer/speech/"
            + "synthesize/readaloud/voices/list?trustedclienttoken=" + TOKEN;
    private static final String SPEECH_URL = "wss://speech.platform.bing.com/consumer/speech/"
            + "synthesize/readaloud/edge/v1";
    private static final int MAX_AUDIO_BYTES = 10 * 1024 * 1024;
    private static final int MAX_TEXT_LENGTH = 12_000;

    interface Handle {
        void cancel();
    }

    interface VoicesListener {
        void onSuccess(List<Voice> voices);
        void onError(String message);
    }

    interface AudioListener {
        void onSuccess(File file);
        void onError(String message);
    }

    static final class Voice {
        final String shortName;
        final String locale;
        final String gender;
        final String displayName;

        Voice(String shortName, String locale, String gender, String displayName) {
            this.shortName = shortName;
            this.locale = locale;
            this.gender = gender;
            this.displayName = displayName;
        }

        String label() {
            String genderLabel = "Female".equalsIgnoreCase(gender) ? "女声"
                    : "Male".equalsIgnoreCase(gender) ? "男声" : gender;
            String name = displayName == null || displayName.isEmpty() ? shortName : displayName;
            if (name.contains("（") || name.contains("(")) {
                return name;
            }
            return name + " · " + locale + (genderLabel.isEmpty() ? "" : " · " + genderLabel);
        }

        @Override
        public String toString() {
            return label();
        }
    }

    static final class Style {
        final String id;
        final String name;
        final int rateOffset;
        final int pitchOffset;
        final int volumeOffset;

        Style(String id, String name, int rateOffset, int pitchOffset, int volumeOffset) {
            this.id = id;
            this.name = name;
            this.rateOffset = rateOffset;
            this.pitchOffset = pitchOffset;
            this.volumeOffset = volumeOffset;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final Context context;
    private final OkHttpClient httpClient;
    private final EdgeTtsVersionProvider versionProvider;
    private final TranslatorTtsClient translator;
    private static volatile long clockOffsetMillis;

    EdgeTtsClient(Context context) {
        this.context = context.getApplicationContext();
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(70, TimeUnit.SECONDS)
                .build();
        versionProvider = new EdgeTtsVersionProvider(this.context, httpClient);
        translator = new TranslatorTtsClient(httpClient);
        versionProvider.refreshIfNeeded();
    }

    Handle listVoices(VoicesListener listener) {
        Request request = new Request.Builder()
                .url(VOICES_URL)
                .header("User-Agent", userAgent())
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .build();
        Call call = httpClient.newCall(request);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call ignored, java.io.IOException exception) {
                if (!cancelled.get()) {
                    listener.onError(readableError(exception));
                }
            }

            @Override
            public void onResponse(Call ignored, Response response) {
                try (response) {
                    if (cancelled.get()) {
                        return;
                    }
                    if (!response.isSuccessful() || response.body() == null) {
                        listener.onError("获取音色失败（HTTP " + response.code() + "）");
                        return;
                    }
                    JSONArray array = new JSONArray(response.body().string());
                    ArrayList<Voice> voices = new ArrayList<>();
                    for (int index = 0; index < array.length(); index++) {
                        JSONObject item = array.optJSONObject(index);
                        if (item == null) {
                            continue;
                        }
                        String shortName = item.optString("ShortName").trim();
                        if (shortName.isEmpty()) {
                            continue;
                        }
                        voices.add(new Voice(
                                shortName,
                                item.optString("Locale"),
                                item.optString("Gender"),
                                item.optString("FriendlyName", item.optString("LocalName"))));
                    }
                    voices.sort((left, right) -> left.shortName.compareToIgnoreCase(right.shortName));
                    if (voices.isEmpty()) {
                        listener.onError("Edge TTS 没有返回可用音色");
                    } else {
                        listener.onSuccess(voices);
                    }
                } catch (Exception exception) {
                    if (!cancelled.get()) {
                        listener.onError("解析音色列表失败：" + readableError(exception));
                    }
                }
            }
        });
        return () -> {
            cancelled.set(true);
            call.cancel();
        };
    }

    Handle synthesize(
            String text,
            String voice,
            String styleId,
            int rate,
            int volume,
            int pitch,
            AudioListener listener) {
        String cleanedText = text == null ? "" : text.trim();
        if (cleanedText.length() > MAX_TEXT_LENGTH) {
            cleanedText = cleanedText.substring(0, MAX_TEXT_LENGTH);
        }
        if (cleanedText.isEmpty()) {
            listener.onError("没有可朗读的文本");
            return () -> { };
        }
        if (requiresTranslator(voice)) {
            return translator.synthesize(cleanedText, voice, styleId, rate, volume, pitch,
                    new TranslatorTtsClient.Listener() {
                        public File save(byte[] bytes) throws Exception { return saveAudio(bytes); }
                        public void onSuccess(File file) { listener.onSuccess(file); }
                        public void onError(String message) { listener.onError(message); }
                    });
        }

        String requestId = compactUuid();
        String connectionId = compactUuid();
        EdgeTtsVersionProvider.VersionInfo edgeVersion = versionProvider.current();
        String url;
        try {
            url = SPEECH_URL
                    + "?TrustedClientToken=" + TOKEN
                    + "&Sec-MS-GEC=" + edgeGec()
                    + "&Sec-MS-GEC-Version=" + edgeVersion.gecVersion
                    + "&ConnectionId=" + connectionId;
        } catch (Exception exception) {
            listener.onError(readableError(exception));
            return () -> { };
        }

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent(edgeVersion.browserVersion))
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Cookie", "MUID=" + compactUuid().toUpperCase(Locale.ROOT))
                .build();
        AtomicBoolean settled = new AtomicBoolean(false);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Handler timeoutHandler = new Handler(Looper.getMainLooper());
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        String finalText = cleanedText;
        WebSocket[] socketHolder = new WebSocket[1];
        Runnable timeout = () -> {
            if (settled.compareAndSet(false, true)) {
                WebSocket activeSocket = socketHolder[0];
                if (activeSocket != null) {
                    activeSocket.cancel();
                }
                listener.onError("Edge TTS 合成超时");
            }
        };
        timeoutHandler.postDelayed(timeout, 60_000L);
        WebSocketListener socketListener = new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response response) {
                socketHolder[0] = socket;
                Log.d(TAG, "TTS WebSocket connected with Edge "
                        + edgeVersion.browserVersion + " / " + edgeVersion.gecVersion);
                String timestamp = utcTimestamp();
                String config = "{\"context\":{\"synthesis\":{\"audio\":{"
                        + "\"metadataoptions\":{\"sentenceBoundaryEnabled\":false,"
                        + "\"wordBoundaryEnabled\":false},"
                        + "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}";
                socket.send("X-Timestamp:" + timestamp
                        + "\r\nContent-Type:application/json; charset=utf-8"
                        + "\r\nPath:speech.config\r\n\r\n" + config);
                Style style = styleById(styleId);
                String ssml = "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\""
                        + " xml:lang=\"zh-CN\"><voice name=\"" + escapeXml(voice) + "\">"
                        + "<prosody pitch=\"" + signed(pitch + style.pitchOffset, -100, 100, "Hz")
                        + "\" rate=\"" + signed(rate + style.rateOffset, -100, 200, "%")
                        + "\" volume=\"" + signed(volume + style.volumeOffset, -100, 100, "%") + "\">"
                        + escapeXml(finalText) + "</prosody></voice></speak>";
                socket.send("X-RequestId:" + requestId
                        + "\r\nContent-Type:application/ssml+xml"
                        + "\r\nX-Timestamp:" + timestamp + "Z"
                        + "\r\nPath:ssml\r\n\r\n" + ssml);
            }

            @Override
            public void onMessage(WebSocket socket, ByteString bytes) {
                if (settled.get()) {
                    return;
                }
                try {
                    byte[] frame = bytes.toByteArray();
                    if (frame.length < 2) {
                        throw new IllegalStateException("音频帧缺少头部");
                    }
                    int headerLength = ((frame[0] & 0xff) << 8) | (frame[1] & 0xff);
                    if (headerLength <= 0 || headerLength > frame.length - 2) {
                        throw new IllegalStateException("音频帧头部无效");
                    }
                    String headers = new String(frame, 2, headerLength, StandardCharsets.UTF_8);
                    if (!"audio".equalsIgnoreCase(headerValue(headers, "Path"))) {
                        return;
                    }
                    int payloadOffset = headerLength + 2;
                    int payloadLength = frame.length - payloadOffset;
                    if (audio.size() + payloadLength > MAX_AUDIO_BYTES) {
                        throw new IllegalStateException("语音文件超过 10 MB");
                    }
                    audio.write(frame, payloadOffset, payloadLength);
                } catch (Exception exception) {
                    fail(socket, exception.getMessage());
                }
            }

            @Override
            public void onMessage(WebSocket socket, String textMessage) {
                String headers = textMessage.split("\r\n\r\n", 2)[0];
                if ("turn.end".equalsIgnoreCase(headerValue(headers, "Path"))) {
                    if (settled.compareAndSet(false, true)) {
                        timeoutHandler.removeCallbacks(timeout);
                        if (audio.size() == 0) {
                            listener.onError("Edge TTS 没有返回音频");
                        } else {
                            try {
                                Log.d(TAG, "TTS audio received: " + audio.size() + " bytes");
                                listener.onSuccess(saveAudio(audio.toByteArray()));
                            } catch (Exception exception) {
                                listener.onError("保存语音失败：" + readableError(exception));
                            }
                        }
                        socket.close(1000, "complete");
                    }
                }
            }

            @Override
            public void onFailure(WebSocket socket, Throwable throwable, Response response) {
                if (settled.compareAndSet(false, true)) {
                    timeoutHandler.removeCallbacks(timeout);
                    int status = response == null ? 0 : response.code();
                    String failure = status > 0
                            ? "Edge TTS 连接失败（HTTP " + status + "）"
                            : readableError(throwable);
                    Log.w(TAG, "TTS WebSocket failed with Edge "
                            + edgeVersion.browserVersion + " / " + edgeVersion.gecVersion
                            + (status > 0 ? ", HTTP " + status : ""), throwable);
                    if (status == 401 || status == 403) {
                        correctClock(response);
                        versionProvider.forceRefresh(new EdgeTtsVersionProvider.Listener() {
                            @Override
                            public void onSuccess(EdgeTtsVersionProvider.VersionInfo version) {
                                Log.d(TAG, "Version refreshed after HTTP " + status + ": "
                                        + version.browserVersion + " / " + version.gecVersion);
                                if (!cancelled.get()) {
                                    listener.onError(failure + "，已刷新 Edge 版本");
                                }
                            }

                            @Override
                            public void onError(String message) {
                                Log.w(TAG, "Version refresh after HTTP " + status
                                        + " failed: " + message);
                                if (!cancelled.get()) {
                                    listener.onError(failure + "；版本刷新失败：" + message);
                                }
                            }
                        });
                    } else {
                        listener.onError(failure);
                    }
                }
            }

            @Override
            public void onClosing(WebSocket socket, int code, String reason) {
                socket.close(code, reason);
            }

            @Override
            public void onClosed(WebSocket socket, int code, String reason) {
                if (settled.compareAndSet(false, true)) {
                    timeoutHandler.removeCallbacks(timeout);
                    Log.w(TAG, "TTS closed: code=" + code + ", voice=" + voice
                            + ", audioBytes=" + audio.size());
                    listener.onError(audio.size() == 0
                            ? "Edge 未返回音频并关闭连接（" + code + "），请刷新音色或稍后重试"
                            : "Edge TTS 音频传输中断（" + code + "）");
                }
            }

            private void fail(WebSocket socket, String message) {
                if (settled.compareAndSet(false, true)) {
                    timeoutHandler.removeCallbacks(timeout);
                    socket.cancel();
                    listener.onError(message == null ? "Edge TTS 合成失败" : message);
                }
            }
        };
        WebSocket socket = httpClient.newWebSocket(request, socketListener);
        socketHolder[0] = socket;
        return () -> {
            cancelled.set(true);
            if (settled.compareAndSet(false, true)) {
                timeoutHandler.removeCallbacks(timeout);
                WebSocket activeSocket = socketHolder[0];
                if (activeSocket != null) {
                    activeSocket.cancel();
                }
            }
        };
    }

    void shutdown() {
        translator.shutdown();
        httpClient.dispatcher().cancelAll();
        httpClient.dispatcher().executorService().shutdown();
    }

    static List<Voice> curatedVoices() {
        ArrayList<Voice> voices = new ArrayList<>();
        voices.add(new Voice("zh-CN-XiaoxiaoNeural", "zh-CN", "Female", "晓晓 Xiaoxiao (女声·温柔)"));
        voices.add(new Voice("zh-CN-YunxiNeural", "zh-CN", "Male", "云希 Yunxi (男声·清朗)"));
        voices.add(new Voice("zh-CN-YunyangNeural", "zh-CN", "Male", "云扬 Yunyang (男声·阳光)"));
        voices.add(new Voice("zh-CN-XiaoyiNeural", "zh-CN", "Female", "晓伊 Xiaoyi (女声·甜美)"));
        voices.add(new Voice("zh-CN-YunjianNeural", "zh-CN", "Male", "云健 Yunjian (男声·稳重)"));
        voices.add(new Voice("zh-CN-XiaochenNeural", "zh-CN", "Female", "晓辰 Xiaochen (女声·知性)"));
        voices.add(new Voice("zh-CN-XiaohanNeural", "zh-CN", "Female", "晓涵 Xiaohan (女声·优雅)"));
        voices.add(new Voice("zh-CN-XiaomengNeural", "zh-CN", "Female", "晓梦 Xiaomeng (女声·梦幻)"));
        voices.add(new Voice("zh-CN-XiaomoNeural", "zh-CN", "Female", "晓墨 Xiaomo (女声·文艺)"));
        voices.add(new Voice("zh-CN-XiaoqiuNeural", "zh-CN", "Female", "晓秋 Xiaoqiu (女声·成熟)"));
        voices.add(new Voice("zh-CN-XiaoruiNeural", "zh-CN", "Female", "晓睿 Xiaorui (女声·智慧)"));
        voices.add(new Voice("zh-CN-XiaoshuangNeural", "zh-CN", "Female", "晓双 Xiaoshuang (女声·活泼)"));
        voices.add(new Voice("zh-CN-XiaoxuanNeural", "zh-CN", "Female", "晓萱 Xiaoxuan (女声·清新)"));
        voices.add(new Voice("zh-CN-XiaoyanNeural", "zh-CN", "Female", "晓颜 Xiaoyan (女声·柔美)"));
        voices.add(new Voice("zh-CN-XiaoyouNeural", "zh-CN", "Female", "晓悠 Xiaoyou (女声·悠扬)"));
        voices.add(new Voice("zh-CN-XiaozhenNeural", "zh-CN", "Female", "晓甄 Xiaozhen (女声·端庄)"));
        voices.add(new Voice("zh-CN-YunfengNeural", "zh-CN", "Male", "云枫 Yunfeng (男声·磁性)"));
        voices.add(new Voice("zh-CN-YunhaoNeural", "zh-CN", "Male", "云皓 Yunhao (男声·豪迈)"));
        voices.add(new Voice("zh-CN-YunxiaNeural", "zh-CN", "Male", "云夏 Yunxia (男声·热情)"));
        return voices;
    }

    static List<Voice> fallbackVoices() {
        return curatedVoices();
    }

    static boolean requiresTranslator(String voice) {
        // These curated voices are supported by the Read Aloud consumer endpoint.
        // Other curated voices require the Translator speech endpoint, as in VoiceCraft.
        return !("zh-CN-XiaoxiaoNeural".equals(voice) || "zh-CN-XiaoyiNeural".equals(voice)
                || "zh-CN-YunxiNeural".equals(voice) || "zh-CN-YunyangNeural".equals(voice)
                || "zh-CN-YunjianNeural".equals(voice) || "zh-CN-YunxiaNeural".equals(voice));
    }

    static List<Style> styles() {
        ArrayList<Style> styles = new ArrayList<>();
        styles.add(new Style("general", "通用风格", 0, 0, 0));
        styles.add(new Style("assistant", "智能助手", -5, 5, 0));
        styles.add(new Style("chat", "聊天对话", 5, 2, 0));
        styles.add(new Style("customerservice", "客服专业", -8, 3, 0));
        styles.add(new Style("newscast", "新闻播报", 0, -5, 5));
        styles.add(new Style("affectionate", "亲切温暖", -10, 6, 0));
        styles.add(new Style("calm", "平静舒缓", -15, -2, 0));
        styles.add(new Style("cheerful", "愉快欢乐", 10, 8, 0));
        styles.add(new Style("gentle", "温和柔美", -10, 2, 0));
        styles.add(new Style("lyrical", "抒情诗意", -12, 4, 0));
        styles.add(new Style("serious", "严肃正式", -8, -6, 0));
        return styles;
    }

    static boolean isSupportedStyle(String styleId) {
        for (Style style : styles()) {
            if (style.id.equals(styleId)) {
                return true;
            }
        }
        return false;
    }

    private static Style styleById(String styleId) {
        for (Style style : styles()) {
            if (style.id.equals(styleId)) {
                return style;
            }
        }
        return styles().get(0);
    }

    private File saveAudio(byte[] bytes) throws Exception {
        File directory = new File(context.getCacheDir(), "edge_tts");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建语音缓存目录");
        }
        File file = new File(directory, "speech-" + System.currentTimeMillis() + "-" + compactUuid() + ".mp3");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
        return file;
    }

    private static String edgeGec() throws Exception {
        return EdgeTtsGec.generate(System.currentTimeMillis() + clockOffsetMillis, TOKEN);
    }

    private static void correctClock(Response response) {
        if (response == null) {
            return;
        }
        Date serverDate = response.headers().getDate("Date");
        if (serverDate != null) {
            clockOffsetMillis = serverDate.getTime() - System.currentTimeMillis();
            Log.d(TAG, "TTS server clock offset: " + clockOffsetMillis + " ms");
        }
    }

    private static String headerValue(String headers, String target) {
        for (String line : headers.split("\r\n")) {
            int separator = line.indexOf(':');
            if (separator > 0 && target.equalsIgnoreCase(line.substring(0, separator).trim())) {
                return line.substring(separator + 1).trim();
            }
        }
        return "";
    }

    private static String escapeXml(String value) {
        String safe = value == null ? "" : value;
        return safe.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String signed(int value, int minimum, int maximum, String unit) {
        int bounded = Math.max(minimum, Math.min(maximum, value));
        return (bounded >= 0 ? "+" : "") + bounded + unit;
    }

    private static String utcTimestamp() {
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        return format.format(new Date());
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String userAgent() {
        return userAgent(versionProvider.current().browserVersion);
    }

    private static String userAgent(String browserVersion) {
        String major = browserVersion.split("\\.")[0];
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/" + major + ".0.0.0"
                + " Safari/537.36 Edg/" + major + ".0.0.0";
    }

    private static String readableError(Throwable throwable) {
        String message = throwable == null ? "" : throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "Edge TTS 连接失败，请稍后重试";
        }
        message = message.trim();
        return message.length() <= 180 ? message : message.substring(0, 180);
    }
}
