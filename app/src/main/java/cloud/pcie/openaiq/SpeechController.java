package cloud.pcie.openaiq;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SpeechController {
    private static final int MAX_ATTEMPTS = 3;
    private static final int PREFETCH_LIMIT = 3;
    private static final int MIN_CHARS = 15;
    private static final int MAX_CHUNK_CHARS = 48;
    private static final int MAX_SPEECH_CHARS = 5_000;
    private static final long[] RETRY_DELAYS_MS = {350L, 900L};
    private static final Pattern BOUNDARY = Pattern.compile("[。！？!?；;\\n]+");
    private static final Pattern NON_CONTENT = Pattern.compile("[，。！？!?；;：:、\\s]");
    private static final Pattern FENCED_CODE = Pattern.compile("\\x60\\x60\\x60[\\s\\S]*?\\x60\\x60\\x60");
    private static final Pattern INLINE_CODE = Pattern.compile("\\x60([^\\x60]+)\\x60");
    private static final Pattern MARKDOWN_IMAGE =
            Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");
    private static final Pattern MARKDOWN_LINK =
            Pattern.compile("\\[([^\\]]+)\\]\\([^)]*\\)");
    private static final Pattern MARKDOWN_HEADING =
            Pattern.compile("^#{1,6}\\s+", Pattern.MULTILINE);
    private static final Pattern MARKDOWN_DECORATION = Pattern.compile("[>*_~]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    enum State {
        IDLE,
        PREPARING,
        PLAYING
    }

    interface Listener {
        void onStateChanged(ChatMessage message, State state, int attempt);
        void onFailure(ChatMessage message, String error);

        default void onPlaybackText(ChatMessage message, String text) {
            // Only the immersive conversation UI needs synchronized segment text.
        }
    }

    static final class ChunkResult {
        final List<String> chunks;
        final String rest;

        ChunkResult(List<String> chunks, String rest) {
            this.chunks = chunks;
            this.rest = rest;
        }
    }

    private static final class Job {
        final int index;
        final String text;
        int attempt;
        EdgeTtsClient.Handle request;
        File file;

        Job(int index, String text) {
            this.index = index;
            this.text = text;
        }
    }

    private final EdgeTtsClient client;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final Map<Integer, Job> preparing = new HashMap<>();
    private final Map<Integer, Job> ready = new HashMap<>();
    private MediaPlayer player;
    private Job activeJob;
    private ChatMessage activeMessage;
    private AppSettings activeSettings;
    private String streamBuffer = "";
    private boolean streaming;
    private boolean generating;
    private boolean receivedText;
    private int nextJobIndex;
    private int nextPlayIndex;
    private int generation;

    SpeechController(Context context, Listener listener) {
        client = new EdgeTtsClient(context);
        this.listener = listener;
    }

    boolean isActive(ChatMessage message) {
        return activeMessage == message;
    }

    boolean isActive() {
        return activeMessage != null;
    }

    boolean isStreaming(ChatMessage message) {
        return streaming && activeMessage == message;
    }

    void toggle(ChatMessage message, AppSettings settings) {
        if (isActive(message)) {
            stop();
        } else {
            speak(message, settings);
        }
    }

    void speak(ChatMessage message, AppSettings settings) {
        if (message == null || !message.isSpeakable()) {
            return;
        }
        startSession(message, settings, false);
        ChunkResult parsed = takeSpeechChunks(message.content, true);
        enqueueChunks(parsed.chunks);
        generating = false;
        pumpSynthesis(generation);
        pumpPlayback(generation);
    }

    void startStreaming(ChatMessage message, AppSettings settings) {
        if (message != null) {
            startSession(message, settings, true);
        }
    }

    void appendStreamingText(ChatMessage message, String delta) {
        if (!isStreaming(message) || delta == null || delta.isEmpty()) {
            return;
        }
        receivedText = true;
        ChunkResult parsed = takeSpeechChunks(streamBuffer + delta, false);
        streamBuffer = parsed.rest;
        enqueueChunks(parsed.chunks);
        pumpSynthesis(generation);
    }

    void finishStreaming(ChatMessage message, String finalText) {
        if (!isStreaming(message)) {
            return;
        }
        if (!receivedText && finalText != null && !finalText.isEmpty()) {
            streamBuffer += finalText;
        }
        ChunkResult parsed = takeSpeechChunks(streamBuffer, true);
        streamBuffer = parsed.rest;
        enqueueChunks(parsed.chunks);
        generating = false;
        pumpSynthesis(generation);
        pumpPlayback(generation);
    }

    void stop() {
        stopInternal(true);
    }

    void release() {
        stopInternal(false);
        client.shutdown();
    }

    private void startSession(ChatMessage message, AppSettings settings, boolean stream) {
        stopInternal(false);
        activeMessage = message;
        activeSettings = settings;
        streaming = stream;
        generating = stream;
        generation++;
        listener.onStateChanged(message, State.PREPARING, 1);
    }

    private void pumpSynthesis(int currentGeneration) {
        if (!isCurrent(currentGeneration)) {
            return;
        }
        while (!queue.isEmpty() && preparing.size() + ready.size() < PREFETCH_LIMIT) {
            Job job = new Job(nextJobIndex++, queue.removeFirst());
            preparing.put(job.index, job);
            startAttempt(job, currentGeneration);
        }
        pumpPlayback(currentGeneration);
        finishIfDrained(currentGeneration);
    }

    private void startAttempt(Job job, int currentGeneration) {
        if (!isCurrent(currentGeneration) || preparing.get(job.index) != job) {
            return;
        }
        job.attempt++;
        if (player == null && job.index == nextPlayIndex) {
            listener.onStateChanged(activeMessage, State.PREPARING, job.attempt);
        }
        job.request = client.synthesize(
                speechText(job.text),
                activeSettings.ttsVoice,
                activeSettings.ttsStyle,
                activeSettings.ttsRate,
                activeSettings.ttsVolume,
                activeSettings.ttsPitch,
                new EdgeTtsClient.AudioListener() {
                    @Override
                    public void onSuccess(File file) {
                        mainHandler.post(() -> handlePrepared(job, file, currentGeneration));
                    }

                    @Override
                    public void onError(String message) {
                        mainHandler.post(() ->
                                handleAttemptFailure(job, currentGeneration, message));
                    }
                });
    }

    private void handlePrepared(Job job, File file, int currentGeneration) {
        if (!isCurrent(currentGeneration) || preparing.get(job.index) != job) {
            file.delete();
            return;
        }
        job.request = null;
        job.file = file;
        preparing.remove(job.index);
        ready.put(job.index, job);
        pumpPlayback(currentGeneration);
        pumpSynthesis(currentGeneration);
    }

    private void enqueueChunks(List<String> chunks) {
        for (String chunk : chunks) {
            if (!speechText(chunk).isEmpty()) {
                queue.addLast(chunk);
            }
        }
    }

    private void handleAttemptFailure(Job job, int currentGeneration, String message) {
        if (!isCurrent(currentGeneration) || preparing.get(job.index) != job) {
            return;
        }
        job.request = null;
        if (job.attempt < MAX_ATTEMPTS) {
            long delay = RETRY_DELAYS_MS[
                    Math.min(job.attempt - 1, RETRY_DELAYS_MS.length - 1)];
            mainHandler.postDelayed(() -> startAttempt(job, currentGeneration), delay);
            return;
        }
        ChatMessage failedMessage = activeMessage;
        stopInternal(false);
        listener.onFailure(failedMessage, message);
    }

    private void pumpPlayback(int currentGeneration) {
        if (!isCurrent(currentGeneration) || player != null || activeJob != null) {
            return;
        }
        Job job = ready.remove(nextPlayIndex);
        if (job == null) {
            if (preparing.containsKey(nextPlayIndex)) {
                Job waiting = preparing.get(nextPlayIndex);
                listener.onStateChanged(
                        activeMessage,
                        State.PREPARING,
                        waiting == null ? 1 : Math.max(1, waiting.attempt));
            }
            finishIfDrained(currentGeneration);
            return;
        }
        nextPlayIndex++;
        activeJob = job;
        play(job, currentGeneration);
        // The active segment no longer counts toward the three future prefetched segments.
        pumpSynthesis(currentGeneration);
    }

    private void play(Job job, int currentGeneration) {
        try {
            MediaPlayer nextPlayer = new MediaPlayer();
            nextPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            nextPlayer.setDataSource(job.file.getAbsolutePath());
            nextPlayer.setOnPreparedListener(readyPlayer -> {
                if (!isCurrent(currentGeneration) || readyPlayer != player
                        || activeJob != job) {
                    return;
                }
                listener.onPlaybackText(activeMessage, job.text);
                readyPlayer.start();
                listener.onStateChanged(activeMessage, State.PLAYING, job.attempt);
            });
            nextPlayer.setOnCompletionListener(done ->
                    completePlayback(job, currentGeneration));
            nextPlayer.setOnErrorListener((failed, what, extra) -> {
                if (isCurrent(currentGeneration) && activeJob == job) {
                    ChatMessage failedMessage = activeMessage;
                    stopInternal(false);
                    listener.onFailure(failedMessage, "音频播放失败");
                }
                return true;
            });
            player = nextPlayer;
            nextPlayer.prepareAsync();
        } catch (Exception exception) {
            ChatMessage failedMessage = activeMessage;
            stopInternal(false);
            listener.onFailure(failedMessage, readableError(exception));
        }
    }

    private void completePlayback(Job job, int currentGeneration) {
        if (!isCurrent(currentGeneration) || activeJob != job) {
            return;
        }
        releasePlayer();
        releaseJob(job);
        activeJob = null;
        pumpPlayback(currentGeneration);
        pumpSynthesis(currentGeneration);
    }

    private void finishIfDrained(int currentGeneration) {
        if (!isCurrent(currentGeneration) || generating || !queue.isEmpty()
                || !preparing.isEmpty() || !ready.isEmpty()
                || activeJob != null || player != null) {
            return;
        }
        stopInternal(true);
    }

    private boolean isCurrent(int currentGeneration) {
        return activeMessage != null && currentGeneration == generation;
    }

    private void stopInternal(boolean notify) {
        generation++;
        mainHandler.removeCallbacksAndMessages(null);
        for (Job job : new ArrayList<>(preparing.values())) {
            releaseJob(job);
        }
        for (Job job : new ArrayList<>(ready.values())) {
            releaseJob(job);
        }
        preparing.clear();
        ready.clear();
        queue.clear();
        releasePlayer();
        releaseJob(activeJob);
        activeJob = null;
        ChatMessage stoppedMessage = activeMessage;
        activeMessage = null;
        activeSettings = null;
        streamBuffer = "";
        streaming = false;
        generating = false;
        receivedText = false;
        nextJobIndex = 0;
        nextPlayIndex = 0;
        if (stoppedMessage != null) {
            listener.onPlaybackText(stoppedMessage, "");
            if (notify) {
                listener.onStateChanged(stoppedMessage, State.IDLE, 0);
            }
        }
    }

    private void releasePlayer() {
        if (player == null) {
            return;
        }
        try {
            player.stop();
        } catch (Exception ignored) {
            // A player that is still preparing cannot always be stopped.
        }
        player.reset();
        player.release();
        player = null;
    }

    private static void releaseJob(Job job) {
        if (job == null) {
            return;
        }
        if (job.request != null) {
            job.request.cancel();
            job.request = null;
        }
        if (job.file != null) {
            job.file.delete();
            job.file = null;
        }
    }

    static ChunkResult takeSpeechChunks(String value, boolean finish) {
        String source = value == null ? "" : value;
        List<String> chunks = new ArrayList<>();
        Matcher boundary = BOUNDARY.matcher(source);
        int start = 0;
        String pending = "";
        while (boundary.find()) {
            String text = cleanSpeechText(source.substring(start, boundary.end()));
            if (!text.isEmpty()) {
                pending += text;
                if (NON_CONTENT.matcher(pending).replaceAll("").length() >= MIN_CHARS) {
                    addBoundedChunk(chunks, pending);
                    pending = "";
                }
            }
            start = boundary.end();
        }
        String rest = pending + source.substring(start);
        while (cleanSpeechText(rest).length() > MAX_CHUNK_CHARS) {
            int split = preferredSplit(rest);
            String text = cleanSpeechText(rest.substring(0, split));
            if (!text.isEmpty()) {
                chunks.add(text);
            }
            rest = rest.substring(split);
        }
        if (finish && !rest.trim().isEmpty()) {
            String text = cleanSpeechText(rest);
            if (!text.isEmpty()) {
                addBoundedChunk(chunks, text);
            }
            rest = "";
        }
        return new ChunkResult(chunks, rest);
    }

    private static void addBoundedChunk(List<String> chunks, String text) {
        String remaining = text;
        while (remaining.length() > MAX_CHUNK_CHARS) {
            int split = preferredSplit(remaining);
            String part = cleanSpeechText(remaining.substring(0, split));
            if (!part.isEmpty()) {
                chunks.add(part);
            }
            remaining = remaining.substring(split);
        }
        if (!remaining.isEmpty()) {
            chunks.add(remaining);
        }
    }

    private static int preferredSplit(String text) {
        int limit = Math.min(MAX_CHUNK_CHARS, text.length());
        for (int index = limit - 1; index >= MIN_CHARS; index--) {
            char character = text.charAt(index);
            if (character == '，' || character == ',' || character == '：'
                    || character == ':' || character == '、') {
                return index + 1;
            }
        }
        if (limit < text.length() && Character.isLowSurrogate(text.charAt(limit))) {
            limit++;
        }
        return limit;
    }

    private static String cleanSpeechText(String value) {
        String text = value == null ? "" : value;
        text = FENCED_CODE.matcher(text).replaceAll(" 代码片段 ");
        text = INLINE_CODE.matcher(text).replaceAll("$1");
        text = MARKDOWN_IMAGE.matcher(text).replaceAll("");
        text = MARKDOWN_LINK.matcher(text).replaceAll("$1");
        text = MARKDOWN_HEADING.matcher(text).replaceAll("");
        text = MARKDOWN_DECORATION.matcher(text).replaceAll("");
        text = WHITESPACE.matcher(text).replaceAll(" ").trim();
        return text.length() <= MAX_SPEECH_CHARS
                ? text
                : text.substring(0, MAX_SPEECH_CHARS);
    }

    static String speechText(String text) {
        return WHITESPACE.matcher(stripEmoji(text)).replaceAll(" ").trim();
    }

    static String stripEmoji(String text) {
        StringBuilder spoken = new StringBuilder();
        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            int nextIndex = index + Character.charCount(codePoint);
            if ((codePoint == '#' || codePoint == '*' || codePoint >= '0' && codePoint <= '9')
                    && nextIndex < text.length()) {
                int next = text.codePointAt(nextIndex);
                int keycapIndex = next == 0xfe0f || next == 0xfe0e
                        ? nextIndex + Character.charCount(next) : nextIndex;
                if (keycapIndex < text.length() && text.codePointAt(keycapIndex) == 0x20e3) {
                    spoken.append(' ');
                    index = keycapIndex + Character.charCount(0x20e3);
                    continue;
                }
            }
            if (isEmojiCodePoint(codePoint)) {
                spoken.append(' ');
            } else if (codePoint != 0x200d && codePoint != 0xfe0e && codePoint != 0xfe0f
                    && codePoint != 0x20e3 && (codePoint < 0xe0020 || codePoint > 0xe007f)) {
                spoken.appendCodePoint(codePoint);
            }
            index = nextIndex;
        }
        return spoken.toString();
    }

    private static boolean isEmojiCodePoint(int codePoint) {
        return codePoint >= 0x1f000 && codePoint <= 0x1faff
                || codePoint >= 0x2600 && codePoint <= 0x27bf
                || codePoint >= 0x2300 && codePoint <= 0x23ff
                || codePoint == 0x00a9 || codePoint == 0x00ae
                || codePoint == 0x203c || codePoint == 0x2049
                || codePoint == 0x2122 || codePoint == 0x2139
                || codePoint == 0x24c2
                || codePoint >= 0x2934 && codePoint <= 0x2935
                || codePoint >= 0x2b05 && codePoint <= 0x2b55;
    }

    private static String readableError(Exception exception) {
        String message = exception == null ? "" : exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? "音频播放失败"
                : message.trim();
    }
}
