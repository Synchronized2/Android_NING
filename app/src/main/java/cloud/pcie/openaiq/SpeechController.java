package cloud.pcie.openaiq;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import java.io.File;

final class SpeechController {
    private static final int MAX_ATTEMPTS = 3;
    private static final long[] RETRY_DELAYS_MS = {350L, 900L};

    enum State {
        IDLE,
        PREPARING,
        PLAYING
    }

    interface Listener {
        void onStateChanged(ChatMessage message, State state, int attempt);
        void onFailure(ChatMessage message, String error);
    }

    private final EdgeTtsClient client;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private EdgeTtsClient.Handle request;
    private MediaPlayer player;
    private File audioFile;
    private ChatMessage activeMessage;
    private AppSettings activeSettings;
    private int generation;
    private int attempt;

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
        stopInternal(false);
        activeMessage = message;
        activeSettings = settings;
        attempt = 0;
        int currentGeneration = ++generation;
        startAttempt(currentGeneration);
    }

    void stop() {
        stopInternal(true);
    }

    void release() {
        stopInternal(false);
        client.shutdown();
    }

    private void startAttempt(int currentGeneration) {
        if (activeMessage == null || currentGeneration != generation) {
            return;
        }
        attempt++;
        listener.onStateChanged(activeMessage, State.PREPARING, attempt);
        request = client.synthesize(
                activeMessage.content,
                activeSettings.ttsVoice,
                activeSettings.ttsStyle,
                activeSettings.ttsRate,
                activeSettings.ttsVolume,
                activeSettings.ttsPitch,
                new EdgeTtsClient.AudioListener() {
                    @Override
                    public void onSuccess(File file) {
                        mainHandler.post(() -> {
                            if (currentGeneration != generation || activeMessage == null) {
                                file.delete();
                                return;
                            }
                            request = null;
                            audioFile = file;
                            play(file, currentGeneration);
                        });
                    }

                    @Override
                    public void onError(String message) {
                        mainHandler.post(() -> handleAttemptFailure(currentGeneration, message));
                    }
                });
    }

    private void handleAttemptFailure(int currentGeneration, String message) {
        if (currentGeneration != generation || activeMessage == null) {
            return;
        }
        request = null;
        if (attempt < MAX_ATTEMPTS) {
            long delay = RETRY_DELAYS_MS[Math.min(attempt - 1, RETRY_DELAYS_MS.length - 1)];
            mainHandler.postDelayed(() -> startAttempt(currentGeneration), delay);
            return;
        }
        ChatMessage failedMessage = activeMessage;
        stopInternal(false);
        listener.onFailure(failedMessage, message);
    }

    private void play(File file, int currentGeneration) {
        try {
            MediaPlayer nextPlayer = new MediaPlayer();
            nextPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            nextPlayer.setDataSource(file.getAbsolutePath());
            nextPlayer.setOnPreparedListener(ready -> {
                if (currentGeneration != generation || ready != player || activeMessage == null) {
                    return;
                }
                ready.start();
                listener.onStateChanged(activeMessage, State.PLAYING, attempt);
            });
            nextPlayer.setOnCompletionListener(done -> finishPlayback(currentGeneration));
            nextPlayer.setOnErrorListener((failed, what, extra) -> {
                if (currentGeneration == generation && activeMessage != null) {
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
            listener.onFailure(failedMessage, exception.getMessage() == null
                    ? "音频播放失败"
                    : exception.getMessage());
        }
    }

    private void finishPlayback(int currentGeneration) {
        if (currentGeneration != generation || activeMessage == null) {
            return;
        }
        stopInternal(true);
    }

    private void stopInternal(boolean notify) {
        generation++;
        mainHandler.removeCallbacksAndMessages(null);
        if (request != null) {
            request.cancel();
            request = null;
        }
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
                // Player may still be preparing.
            }
            player.reset();
            player.release();
            player = null;
        }
        if (audioFile != null) {
            audioFile.delete();
            audioFile = null;
        }
        ChatMessage stoppedMessage = activeMessage;
        activeMessage = null;
        activeSettings = null;
        attempt = 0;
        if (notify && stoppedMessage != null) {
            listener.onStateChanged(stoppedMessage, State.IDLE, 0);
        }
    }
}
