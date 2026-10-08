package cloud.pcie.openaiq;

import android.app.Instrumentation;
import android.content.Context;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;

/** Exercises retry and cancellation without calling the configured image provider. */
final class ImageGenerationServiceAudit {
    private final Instrumentation instrumentation;
    private final Context context;

    ImageGenerationServiceAudit(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        this.context = instrumentation.getTargetContext();
    }

    void run() throws Exception {
        File history = new File(context.getFilesDir(), "conversation_history.json");
        File backup = new File(context.getFilesDir(), "conversation_history.json.bak");
        boolean existed = history.isFile();
        byte[] originalHistory = existed ? Files.readAllBytes(history.toPath()) : null;
        byte[] originalBackup = backup.isFile() ? Files.readAllBytes(backup.toPath()) : null;
        AppSettings original = AppSettings.load(context);
        try {
            ImageGenerationService.cancel(context);
            waitForStopped(5_000L);
            Files.deleteIfExists(history.toPath());
            Files.deleteIfExists(backup.toPath());

            AppSettings failing = new AppSettings(
                    false,
                    original.chatBaseUrl,
                    original.chatApiKey,
                    "https://127.0.0.1:1/v1",
                    "test-only",
                    original.chatModel,
                    "gpt-image-2",
                    original.systemPrompt,
                    original.autoSpeak,
                    original.ttsVoice,
                    original.ttsStyle,
                    original.ttsRate,
                    original.ttsVolume,
                    original.ttsPitch,
                    original.avatarEnabled,
                    original.avatarId);
            failing.save(context);

            ConversationStore store = new ConversationStore(context);
            ArrayList<ChatMessage> messages = new ArrayList<>();
            messages.add(new ChatMessage(ChatMessage.ROLE_USER, "后台重试测试"));
            ChatMessage retryResponse = imageResponse("测试图片");
            messages.add(retryResponse);
            store.save(messages);
            String conversationId = store.activeConversationId();
            ImageGenerationService.start(context, conversationId, retryResponse, null);
            waitForStopped(20_000L);
            ChatMessage failed = store.findMessage(conversationId, retryResponse.id);
            require(failed != null && failed.error && failed.retryable,
                    "Three-attempt failure was not persisted");
            require(failed.content.contains("已尝试 3 次"),
                    "Image service did not make exactly three attempts: " + failed.content);

            messages = store.load();
            messages.add(new ChatMessage(ChatMessage.ROLE_USER, "后台停止测试"));
            ChatMessage cancelResponse = imageResponse("停止测试图片");
            messages.add(cancelResponse);
            store.save(messages);
            ImageGenerationService.start(context, conversationId, cancelResponse, null);
            waitForRunning(5_000L);
            ImageGenerationService.cancel(context);
            waitForStopped(5_000L);
            ChatMessage cancelled = store.findMessage(conversationId, cancelResponse.id);
            require(cancelled != null && cancelled.content.contains("已停止生成图片"),
                    "Manual stop was not persisted");
        } finally {
            ImageGenerationService.cancel(context);
            waitForStopped(5_000L);
            original.save(context);
            Files.deleteIfExists(history.toPath());
            Files.deleteIfExists(backup.toPath());
            if (originalHistory != null) Files.write(history.toPath(), originalHistory);
            if (originalBackup != null) Files.write(backup.toPath(), originalBackup);
        }
    }

    private ChatMessage imageResponse(String prompt) {
        ChatMessage response = new ChatMessage(ChatMessage.ROLE_ASSISTANT, "");
        response.mode = ChatMessage.MODE_IMAGE;
        response.imagePrompt = prompt;
        response.imageSize = "1024x1024";
        response.imageQuality = "medium";
        return response;
    }

    private void waitForRunning(long timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout;
        while (!ImageGenerationService.snapshot(context).running
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(100L);
        }
        require(ImageGenerationService.snapshot(context).running, "Image service did not start");
    }

    private void waitForStopped(long timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout;
        while (ImageGenerationService.snapshot(context).running
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(100L);
        }
        require(!ImageGenerationService.snapshot(context).running, "Image service did not stop");
        instrumentation.waitForIdleSync();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
