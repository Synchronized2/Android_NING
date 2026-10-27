package cloud.pcie.openaiq;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Real TTS round trips and playback; does not change saved settings or history. */
final class EdgeTtsAudit {
    private final Instrumentation instrumentation;

    EdgeTtsAudit(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    void run() throws Exception {
        EdgeTtsClient client = new EdgeTtsClient(instrumentation.getTargetContext());
        AtomicReference<File> audio = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        CountDownLatch synthesized = new CountDownLatch(1);
        try {
            CountDownLatch catalogReady = new CountDownLatch(1);
            AtomicReference<List<EdgeTtsClient.Voice>> catalog = new AtomicReference<>();
            client.listVoices(new EdgeTtsClient.VoicesListener() {
                public void onSuccess(List<EdgeTtsClient.Voice> voices) { catalog.set(voices); catalogReady.countDown(); }
                public void onError(String message) { error.set(message); catalogReady.countDown(); }
            });
            require(catalogReady.await(80, TimeUnit.SECONDS), "Voice catalog timeout");
            require(error.get() == null && catalog.get() != null, "Voice catalog failed: " + error.get());
            AppSettings.saveTtsVoiceCatalog(instrumentation.getTargetContext(), catalog.get());
            List<EdgeTtsClient.Voice> available = EdgeTtsClient.curatedVoices();
            for (EdgeTtsClient.Voice voice : available) {
                CountDownLatch ready = new CountDownLatch(1);
                AtomicReference<File> sample = new AtomicReference<>();
                client.synthesize("你好，音色测试。", voice.shortName, "general", 0, 0, 0,
                        new EdgeTtsClient.AudioListener() {
                            public void onSuccess(File file) { sample.set(file); ready.countDown(); }
                            public void onError(String failure) { error.set(failure); ready.countDown(); }
                        });
                require(ready.await(80, TimeUnit.SECONDS), "Voice timeout: " + voice.shortName);
                require(error.get() == null && sample.get() != null, "Voice failed: " + voice.shortName + ": " + error.get());
                MediaMetadataRetriever decoder = new MediaMetadataRetriever();
                try {
                    decoder.setDataSource(sample.get().getAbsolutePath());
                    String duration = decoder.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    require(duration != null && Long.parseLong(duration) > 0, "Invalid MP3: " + voice.shortName);
                } finally { decoder.release(); }
                Log.i("EdgeTtsAudit", "Voice PASS: " + voice.shortName + ", " + sample.get().length() + " bytes");
                sample.get().delete();
            }
            client.synthesize("你好，这是 NING 语音连接修复测试。", "zh-CN-XiaoxiaoNeural",
                    "general", 0, 0, 0, new EdgeTtsClient.AudioListener() {
                        public void onSuccess(File file) { audio.set(file); synthesized.countDown(); }
                        public void onError(String message) { error.set(message); synthesized.countDown(); }
                    });
            require(synthesized.await(80, TimeUnit.SECONDS), "Synthesis callback timeout");
            require(error.get() == null, "Synthesis failed: " + error.get());
            require(audio.get() != null && audio.get().length() > 1000, "Missing MP3");
            MediaMetadataRetriever metadata = new MediaMetadataRetriever();
            try {
                metadata.setDataSource(audio.get().getAbsolutePath());
                String duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                require(duration != null && Long.parseLong(duration) > 0, "MP3 duration invalid");
                Log.i("EdgeTtsAudit", "Synthesized MP3: " + audio.get().length() + " bytes, " + duration + " ms");
            } finally { metadata.release(); }
        } finally {
            client.shutdown();
            if (audio.get() != null) audio.get().delete();
        }

        CountDownLatch completed = new CountDownLatch(1);
        List<String> played = new ArrayList<>();
        AtomicReference<SpeechController> controller = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            SpeechController speech = new SpeechController(instrumentation.getTargetContext(),
                    new SpeechController.Listener() {
                        private boolean started;
                        public void onStateChanged(ChatMessage message, SpeechController.State state, int attempt) {
                            if (state == SpeechController.State.PREPARING) started = true;
                            if (started && state == SpeechController.State.IDLE) completed.countDown();
                        }
                        public void onFailure(ChatMessage message, String failure) {
                            error.set(failure); completed.countDown();
                        }
                        public void onPlaybackText(ChatMessage message, String text) { played.add(text); }
                    });
            controller.set(speech);
            speech.speak(new ChatMessage(ChatMessage.ROLE_ASSISTANT,
                    "这是第一段语音，用来验证当前的合成连接是否恢复正常。"
                            + "这是第二段语音，用来验证多路预取能够连续播放。"
                            + "这是最后一段，测试完成。"), new AppSettings("", "", "", "",
                    "", false, "zh-CN-XiaomoNeural", 0, 0, 0));
        });
        try {
            require(completed.await(120, TimeUnit.SECONDS), "Playback completion timeout");
            require(error.get() == null, "Playback failed: " + error.get());
            require(played.size() >= 3, "Expected three playback text segments: " + played.size());
            Log.i("EdgeTtsAudit", "Playback completed: " + played.size() + " synchronized segments");
        } finally {
            instrumentation.runOnMainSync(() -> controller.get().release());
        }

        Intent intent = new Intent(instrumentation.getTargetContext(), SettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity settings = instrumentation.startActivitySync(intent);
        instrumentation.runOnMainSync(settings::finish);
        instrumentation.waitForIdleSync();
        Log.i("EdgeTtsAudit", "PASS: synthesis, MP3 decoding, segmented playback, settings exit");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
