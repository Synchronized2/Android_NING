package cloud.pcie.openaiq;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import com.konovalov.vad.webrtc.Vad;
import com.konovalov.vad.webrtc.VadWebRTC;
import com.konovalov.vad.webrtc.config.FrameSize;
import com.konovalov.vad.webrtc.config.Mode;
import com.konovalov.vad.webrtc.config.SampleRate;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

final class VoiceConversationController {
    private static final String ASR_URL = "https://tools.yeyupiaoling.cn/speech/api/asr";
    private static final int SAMPLE_RATE = 16_000;
    private static final int FRAME_SAMPLES = 320;
    private static final int PRE_ROLL_FRAMES = 15;
    private static final int SPEECH_START_FRAMES = 3;
    private static final int SILENCE_STOP_FRAMES = 40;
    private static final int WAIT_TIMEOUT_FRAMES = 750;
    private static final int MAX_SPEECH_FRAMES = 1_500;
    private static final MediaType WAV_MEDIA_TYPE = MediaType.get("audio/wav");

    enum State {
        IDLE,
        LISTENING,
        PROCESSING
    }

    interface Listener {
        void onStateChanged(State state);
        void onPartialResult(String text);
        void onResult(String text);
        void onError(String message);
    }

    private static final class Session {
        final ArrayDeque<short[]> preRoll = new ArrayDeque<>();
        final List<short[]> frames = new ArrayList<>();
        volatile boolean cancelled;
        volatile boolean stopRequested;
        volatile AudioRecord recorder;
        volatile Call uploadCall;
        volatile File wavFile;
        int waitedFrames;
        int speechRun;
        int silenceRun;
        boolean speechStarted;
    }

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(70, TimeUnit.SECONDS)
            .build();
    private final Object lock = new Object();
    private Session currentSession;
    private State state = State.IDLE;

    VoiceConversationController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    boolean isAvailable() {
        return AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT) > 0;
    }

    boolean isActive() {
        synchronized (lock) {
            return currentSession != null;
        }
    }

    void start() {
        final Session session;
        synchronized (lock) {
            if (currentSession != null) {
                return;
            }
            session = new Session();
            currentSession = session;
            state = State.LISTENING;
        }
        notifyState(State.LISTENING);
        Thread thread = new Thread(() -> capture(session), "ning-voice-capture");
        thread.start();
    }

    void finish() {
        Session session;
        synchronized (lock) {
            session = currentSession;
            if (session == null || state != State.LISTENING) {
                return;
            }
            session.stopRequested = true;
            state = State.PROCESSING;
        }
        notifyState(State.PROCESSING);
    }

    void cancel() {
        Session session;
        synchronized (lock) {
            session = currentSession;
            currentSession = null;
            state = State.IDLE;
        }
        if (session != null) {
            session.cancelled = true;
            Call call = session.uploadCall;
            if (call != null) {
                call.cancel();
            }
            AudioRecord recorder = session.recorder;
            if (recorder != null) {
                try {
                    recorder.stop();
                } catch (Exception ignored) {
                    // The capture thread may already have stopped and released it.
                }
            }
            deleteQuietly(session.wavFile);
            notifyState(State.IDLE);
        }
    }

    void release() {
        cancel();
    }

    private void capture(Session session) {
        AudioRecord recorder = null;
        VadWebRTC vad = null;
        try {
            recorder = createRecorder();
            session.recorder = recorder;
            vad = Vad.builder()
                    .setSampleRate(SampleRate.SAMPLE_RATE_16K)
                    .setFrameSize(FrameSize.FRAME_SIZE_320)
                    .setMode(Mode.AGGRESSIVE)
                    .build();
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("麦克风无法开始录音");
            }

            short[] readBuffer = new short[FRAME_SAMPLES];
            short[] frame = new short[FRAME_SAMPLES];
            int frameOffset = 0;
            while (!session.cancelled && !session.stopRequested) {
                int read = recorder.read(
                        readBuffer,
                        0,
                        readBuffer.length,
                        AudioRecord.READ_BLOCKING);
                if (read < 0) {
                    if (session.cancelled || session.stopRequested) {
                        break;
                    }
                    throw new IllegalStateException("麦克风读取失败：" + read);
                }
                int offset = 0;
                while (offset < read && !session.cancelled && !session.stopRequested) {
                    int count = Math.min(FRAME_SAMPLES - frameOffset, read - offset);
                    System.arraycopy(readBuffer, offset, frame, frameOffset, count);
                    offset += count;
                    frameOffset += count;
                    if (frameOffset == FRAME_SAMPLES) {
                        boolean speech = vad.isSpeech(frame);
                        String result = collectFrame(session, frame, speech);
                        frame = new short[FRAME_SAMPLES];
                        frameOffset = 0;
                        if ("complete".equals(result)) {
                            session.stopRequested = true;
                            moveToProcessing(session);
                        } else if ("timeout".equals(result)) {
                            throw new IllegalStateException("未检测到语音，请重试");
                        }
                    }
                }
            }

            if (session.cancelled) {
                return;
            }
            if (!session.speechStarted) {
                session.frames.addAll(session.preRoll);
                session.preRoll.clear();
                session.speechStarted = !session.frames.isEmpty();
            }
            if (!session.speechStarted || session.frames.isEmpty()) {
                throw new IllegalStateException("未检测到语音，请重试");
            }
            moveToProcessing(session);
            File wavFile = writeWav(session.frames);
            session.wavFile = wavFile;
            upload(session, wavFile);
        } catch (SecurityException error) {
            fail(session, context.getString(R.string.voice_permission_denied));
        } catch (Throwable error) {
            if (!session.cancelled) {
                String message = error.getMessage();
                fail(session, message == null || message.trim().isEmpty()
                        ? "麦克风录音失败"
                        : message.trim());
            }
        } finally {
            session.recorder = null;
            if (recorder != null) {
                try {
                    if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop();
                    }
                } catch (Exception ignored) {
                    // Release remains safe even if another thread already stopped recording.
                }
                recorder.release();
            }
            if (vad != null) {
                try {
                    vad.close();
                } catch (Exception ignored) {
                    // Native VAD resources may already be closed after an initialization error.
                }
            }
        }
    }

    private AudioRecord createRecorder() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(context.getString(R.string.voice_permission_denied));
        }
        int minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) {
            throw new IllegalStateException("此设备不支持 16 kHz 单声道录音");
        }
        int bufferBytes = Math.max(minimum * 2, FRAME_SAMPLES * 2 * 10);
        AudioRecord recorder = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes);
        if (recorder.getState() == AudioRecord.STATE_INITIALIZED) {
            return recorder;
        }
        recorder.release();
        recorder = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes);
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            throw new IllegalStateException("麦克风初始化失败");
        }
        return recorder;
    }

    private String collectFrame(Session session, short[] frame, boolean speech) {
        session.waitedFrames++;
        if (!session.speechStarted) {
            session.preRoll.addLast(frame.clone());
            while (session.preRoll.size() > PRE_ROLL_FRAMES) {
                session.preRoll.removeFirst();
            }
            session.speechRun = speech ? session.speechRun + 1 : 0;
            if (session.speechRun >= SPEECH_START_FRAMES) {
                session.speechStarted = true;
                session.frames.addAll(session.preRoll);
                session.preRoll.clear();
                return "started";
            }
            return session.waitedFrames >= WAIT_TIMEOUT_FRAMES ? "timeout" : "waiting";
        }

        session.frames.add(frame.clone());
        session.silenceRun = speech ? 0 : session.silenceRun + 1;
        if (session.silenceRun >= SILENCE_STOP_FRAMES
                || session.frames.size() >= MAX_SPEECH_FRAMES) {
            return "complete";
        }
        return "recording";
    }

    private void moveToProcessing(Session session) {
        synchronized (lock) {
            if (currentSession != session || state == State.PROCESSING) {
                return;
            }
            state = State.PROCESSING;
        }
        notifyState(State.PROCESSING);
    }

    private File writeWav(List<short[]> frames) throws Exception {
        File directory = new File(context.getCacheDir(), "voice");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建语音缓存目录");
        }
        File output = new File(directory, "ning-voice-" + System.currentTimeMillis() + ".wav");
        int pcmBytes = frames.size() * FRAME_SAMPLES * 2;
        try (DataOutputStream stream = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(output)))) {
            writeAscii(stream, "RIFF");
            writeIntLe(stream, 36 + pcmBytes);
            writeAscii(stream, "WAVE");
            writeAscii(stream, "fmt ");
            writeIntLe(stream, 16);
            writeShortLe(stream, 1);
            writeShortLe(stream, 1);
            writeIntLe(stream, SAMPLE_RATE);
            writeIntLe(stream, SAMPLE_RATE * 2);
            writeShortLe(stream, 2);
            writeShortLe(stream, 16);
            writeAscii(stream, "data");
            writeIntLe(stream, pcmBytes);
            for (short[] frame : frames) {
                for (short sample : frame) {
                    writeShortLe(stream, sample);
                }
            }
        }
        return output;
    }

    private void upload(Session session, File wavFile) {
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("language", "16k_zh")
                .addFormDataPart(
                        "file",
                        wavFile.getName(),
                        RequestBody.create(wavFile, WAV_MEDIA_TYPE))
                .build();
        Request request = new Request.Builder()
                .url(ASR_URL)
                .post(body)
                .build();
        Call call = httpClient.newCall(request);
        session.uploadCall = call;
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, java.io.IOException error) {
                deleteQuietly(wavFile);
                if (!session.cancelled) {
                    fail(session, call.isCanceled()
                            ? "语音识别已取消"
                            : "语音上传失败：" + readableMessage(error));
                }
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (response) {
                    String responseText = response.body() == null ? "" : response.body().string();
                    if (!response.isSuccessful()) {
                        throw new IllegalStateException(
                                "语音识别服务返回 HTTP " + response.code()
                                        + responseError(responseText));
                    }
                    complete(session, transcriptFromResponse(responseText));
                } catch (Throwable error) {
                    if (!session.cancelled) {
                        fail(session, readableMessage(error));
                    }
                } finally {
                    deleteQuietly(wavFile);
                }
            }
        });
    }

    static String transcriptFromResponse(String responseText) throws Exception {
        JSONObject data = new JSONObject(responseText);
        if (data.has("success") && !data.optBoolean("success", true)) {
            String error = firstNonEmpty(data.optString("message"), data.optString("error"));
            throw new IllegalStateException(error.isEmpty() ? "语音识别失败" : error);
        }
        String transcript = firstNonEmpty(data.optString("full_text"), data.optString("text"));
        if (transcript.isEmpty()) {
            JSONArray segments = data.optJSONArray("segments");
            if (segments != null) {
                StringBuilder joined = new StringBuilder();
                for (int index = 0; index < segments.length(); index++) {
                    JSONObject segment = segments.optJSONObject(index);
                    String text = segment == null ? "" : segment.optString("text").trim();
                    if (!text.isEmpty()) {
                        if (joined.length() > 0) {
                            joined.append('\n');
                        }
                        joined.append(text);
                    }
                }
                transcript = joined.toString();
            }
        }
        if (transcript.trim().isEmpty()) {
            throw new IllegalStateException("语音识别未返回文字，请重试");
        }
        return transcript.trim();
    }

    private void complete(Session session, String text) {
        synchronized (lock) {
            if (currentSession != session || session.cancelled) {
                return;
            }
            currentSession = null;
            state = State.IDLE;
        }
        mainHandler.post(() -> {
            listener.onStateChanged(State.IDLE);
            listener.onResult(text);
        });
    }

    private void fail(Session session, String message) {
        synchronized (lock) {
            if (currentSession != session || session.cancelled) {
                return;
            }
            currentSession = null;
            state = State.IDLE;
        }
        session.cancelled = true;
        Call call = session.uploadCall;
        if (call != null && !call.isCanceled()) {
            call.cancel();
        }
        deleteQuietly(session.wavFile);
        mainHandler.post(() -> {
            listener.onStateChanged(State.IDLE);
            listener.onError(message);
        });
    }

    private void notifyState(State next) {
        mainHandler.post(() -> listener.onStateChanged(next));
    }

    private static String responseError(String responseText) {
        try {
            JSONObject json = new JSONObject(responseText);
            String message = firstNonEmpty(json.optString("message"), json.optString("error"));
            return message.isEmpty() ? "" : "：" + message;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String readableMessage(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.trim().isEmpty() ? "语音识别失败" : message.trim();
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            // Cache cleanup is best effort; Android will also reclaim this directory.
            file.delete();
        }
    }

    private static void writeAscii(DataOutputStream stream, String text) throws Exception {
        stream.writeBytes(text);
    }

    private static void writeShortLe(DataOutputStream stream, int value) throws Exception {
        stream.writeByte(value & 0xff);
        stream.writeByte((value >>> 8) & 0xff);
    }

    private static void writeIntLe(DataOutputStream stream, int value) throws Exception {
        stream.writeByte(value & 0xff);
        stream.writeByte((value >>> 8) & 0xff);
        stream.writeByte((value >>> 16) & 0xff);
        stream.writeByte((value >>> 24) & 0xff);
    }
}
