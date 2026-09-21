package cloud.pcie.openaiq;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    private static final String TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String VOICES_URL = "https://speech.platform.bing.com/consumer/speech/"
            + "synthesize/readaloud/voices/list?trustedclienttoken=" + TOKEN;
    private static final String SPEECH_URL = "wss://speech.platform.bing.com/consumer/speech/"
            + "synthesize/readaloud/edge/v1";
    private static final String CHROMIUM_VERSION = "143.0.3650.75";
    private static final String GEC_VERSION = "1-143.0.3650";
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

    EdgeTtsClient(Context context) {
        this.context = context.getApplicationContext();
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(70, TimeUnit.SECONDS)
                .build();
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

        String requestId = compactUuid();
        String connectionId = compactUuid();
        String url;
        try {
            url = SPEECH_URL
                    + "?TrustedClientToken=" + TOKEN
                    + "&Sec-MS-GEC=" + edgeGec()
                    + "&Sec-MS-GEC-Version=" + GEC_VERSION
                    + "&ConnectionId=" + connectionId;
        } catch (Exception exception) {
            listener.onError(readableError(exception));
            return () -> { };
        }

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent())
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Cookie", "MUID=" + compactUuid().toUpperCase(Locale.ROOT))
                .build();
        AtomicBoolean settled = new AtomicBoolean(false);
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
                        + "\r\nX-Timestamp:" + timestamp
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
                    listener.onError(readableError(throwable));
                }
            }

            @Override
            public void onClosed(WebSocket socket, int code, String reason) {
                if (settled.compareAndSet(false, true)) {
                    timeoutHandler.removeCallbacks(timeout);
                    listener.onError("Edge TTS 连接提前关闭");
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
        httpClient.dispatcher().cancelAll();
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    static List<Voice> fallbackVoices() {
        ArrayList<Voice> voices = new ArrayList<>();
        voices.add(new Voice("zh-CN-XiaoxiaoNeural", "zh-CN", "Female", "晓晓 · 女声 · 温柔"));
        voices.add(new Voice("zh-CN-YunxiNeural", "zh-CN", "Male", "云希 · 男声 · 清朗"));
        voices.add(new Voice("zh-CN-YunyangNeural", "zh-CN", "Male", "云扬 · 男声 · 阳光"));
        voices.add(new Voice("zh-CN-XiaoyiNeural", "zh-CN", "Female", "晓伊 · 女声 · 甜美"));
        voices.add(new Voice("zh-CN-YunjianNeural", "zh-CN", "Male", "云健 · 男声 · 稳重"));
        voices.add(new Voice("zh-CN-XiaochenNeural", "zh-CN", "Female", "晓辰 · 女声 · 知性"));
        voices.add(new Voice("zh-CN-XiaohanNeural", "zh-CN", "Female", "晓涵 · 女声 · 优雅"));
        voices.add(new Voice("zh-CN-XiaomengNeural", "zh-CN", "Female", "晓梦 · 女声 · 梦幻"));
        voices.add(new Voice("zh-CN-XiaomoNeural", "zh-CN", "Female", "晓墨 · 女声 · 文艺"));
        voices.add(new Voice("zh-CN-XiaoqiuNeural", "zh-CN", "Female", "晓秋 · 女声 · 成熟"));
        voices.add(new Voice("zh-CN-XiaoruiNeural", "zh-CN", "Female", "晓睿 · 女声 · 智慧"));
        voices.add(new Voice("zh-CN-XiaoshuangNeural", "zh-CN", "Female", "晓双 · 女声 · 活泼"));
        voices.add(new Voice("zh-CN-XiaoxuanNeural", "zh-CN", "Female", "晓萱 · 女声 · 清新"));
        voices.add(new Voice("zh-CN-XiaoyanNeural", "zh-CN", "Female", "晓颜 · 女声 · 柔美"));
        voices.add(new Voice("zh-CN-XiaoyouNeural", "zh-CN", "Female", "晓悠 · 女声 · 悠扬"));
        voices.add(new Voice("zh-CN-XiaozhenNeural", "zh-CN", "Female", "晓甄 · 女声 · 端庄"));
        voices.add(new Voice("zh-CN-YunfengNeural", "zh-CN", "Male", "云枫 · 男声 · 磁性"));
        voices.add(new Voice("zh-CN-YunhaoNeural", "zh-CN", "Male", "云皓 · 男声 · 豪迈"));
        voices.add(new Voice("zh-CN-YunxiaNeural", "zh-CN", "Male", "云夏 · 男声 · 热情"));
        voices.add(new Voice("zh-CN-liaoning-XiaobeiNeural", "zh-CN-liaoning", "Female", "晓北（辽宁）"));
        voices.add(new Voice("zh-CN-shaanxi-XiaoniNeural", "zh-CN-shaanxi", "Female", "晓妮（陕西）"));
        voices.add(new Voice("zh-HK-HiuGaaiNeural", "zh-HK", "Female", "曉佳（粤语）"));
        voices.add(new Voice("zh-HK-HiuMaanNeural", "zh-HK", "Female", "曉曼（粤语）"));
        voices.add(new Voice("zh-HK-WanLungNeural", "zh-HK", "Male", "云龙（粤语）"));
        voices.add(new Voice("zh-TW-HsiaoChenNeural", "zh-TW", "Female", "曉臻（台湾）"));
        voices.add(new Voice("zh-TW-HsiaoYuNeural", "zh-TW", "Female", "曉雨（台湾）"));
        voices.add(new Voice("zh-TW-YunJheNeural", "zh-TW", "Male", "云哲（台湾）"));
        return voices;
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
        long seconds = System.currentTimeMillis() / 1000L + 11_644_473_600L;
        long ticks = (seconds - seconds % 300L) * 10_000_000L;
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((Long.toString(ticks) + TOKEN).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(String.format(Locale.ROOT, "%02X", value & 0xff));
        }
        return hex.toString();
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

    private static String userAgent() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/" + CHROMIUM_VERSION
                + " Safari/537.36 Edg/" + CHROMIUM_VERSION;
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
