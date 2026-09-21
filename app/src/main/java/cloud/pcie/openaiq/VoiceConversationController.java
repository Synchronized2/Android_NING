package cloud.pcie.openaiq;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Locale;

final class VoiceConversationController implements RecognitionListener {
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

    private final Context context;
    private final Listener listener;
    private SpeechRecognizer recognizer;
    private State state = State.IDLE;
    private boolean cancelling;

    VoiceConversationController(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    boolean isAvailable() {
        return SpeechRecognizer.isRecognitionAvailable(context);
    }

    boolean isActive() {
        return state != State.IDLE;
    }

    void start() {
        if (isActive()) {
            return;
        }
        if (!isAvailable()) {
            listener.onError(context.getString(R.string.voice_not_available));
            return;
        }
        cancelling = false;
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context);
            recognizer.setRecognitionListener(this);
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        state = State.LISTENING;
        listener.onStateChanged(state);
        recognizer.startListening(intent);
    }

    void finish() {
        if (recognizer != null && state == State.LISTENING) {
            state = State.PROCESSING;
            listener.onStateChanged(state);
            recognizer.stopListening();
        }
    }

    void cancel() {
        cancelling = true;
        if (recognizer != null) {
            recognizer.cancel();
        }
        setIdle();
    }

    void release() {
        cancelling = true;
        if (recognizer != null) {
            recognizer.cancel();
            recognizer.destroy();
            recognizer = null;
        }
        state = State.IDLE;
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
        state = State.LISTENING;
        listener.onStateChanged(state);
    }

    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }

    @Override
    public void onEndOfSpeech() {
        if (state != State.IDLE) {
            state = State.PROCESSING;
            listener.onStateChanged(state);
        }
    }

    @Override
    public void onError(int error) {
        if (cancelling || state == State.IDLE) {
            cancelling = false;
            return;
        }
        setIdle();
        listener.onError(readableError(error));
    }

    @Override
    public void onResults(Bundle results) {
        String text = firstResult(results);
        setIdle();
        if (text.isEmpty()) {
            listener.onError(context.getString(R.string.voice_no_match));
        } else {
            listener.onResult(text);
        }
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        String text = firstResult(partialResults);
        if (!text.isEmpty()) {
            listener.onPartialResult(text);
        }
    }

    @Override public void onEvent(int eventType, Bundle params) { }

    private void setIdle() {
        state = State.IDLE;
        listener.onStateChanged(state);
    }

    private String firstResult(Bundle bundle) {
        if (bundle == null) {
            return "";
        }
        ArrayList<String> matches = bundle.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION);
        return matches == null || matches.isEmpty() || matches.get(0) == null
                ? ""
                : matches.get(0).trim();
    }

    private String readableError(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "麦克风录音失败";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return context.getString(R.string.voice_permission_denied);
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "语音识别网络不可用";
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return context.getString(R.string.voice_no_match);
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "语音识别服务正忙，请稍后重试";
            case SpeechRecognizer.ERROR_SERVER:
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED:
                return "系统语音识别服务暂时不可用";
            default:
                return "错误代码 " + error;
        }
    }
}
