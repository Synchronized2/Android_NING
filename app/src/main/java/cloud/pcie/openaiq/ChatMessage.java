package cloud.pcie.openaiq;

import org.json.JSONException;
import org.json.JSONObject;

final class ChatMessage {
    static final String ROLE_USER = "user";
    static final String ROLE_ASSISTANT = "assistant";
    static final String MODE_CHAT = "chat";
    static final String MODE_IMAGE = "image";

    final String role;
    String content;
    boolean error;
    String imageUri = "";
    String imageMime = "";
    String imageName = "";
    String meta = "";
    String mode = MODE_CHAT;
    String imagePrompt = "";
    String imageSize = "";
    String imageQuality = "";
    boolean generatedImage;
    boolean retryable;

    ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("role", role)
                .put("content", content)
                .put("error", error)
                .put("image_uri", imageUri)
                .put("image_mime", imageMime)
                .put("image_name", imageName)
                .put("meta", meta)
                .put("mode", mode)
                .put("image_prompt", imagePrompt)
                .put("image_size", imageSize)
                .put("image_quality", imageQuality)
                .put("generated_image", generatedImage)
                .put("retryable", retryable);
    }

    static ChatMessage fromJson(JSONObject json) {
        ChatMessage message = new ChatMessage(
                json.optString("role", ROLE_ASSISTANT),
                json.optString("content", ""));
        message.error = json.optBoolean("error", false);
        message.imageUri = json.optString("image_uri", "");
        message.imageMime = json.optString("image_mime", "");
        message.imageName = json.optString("image_name", "");
        message.meta = json.optString("meta", "");
        message.mode = json.optString("mode", MODE_CHAT);
        message.imagePrompt = json.optString("image_prompt", "");
        message.imageSize = json.optString("image_size", "");
        message.imageQuality = json.optString("image_quality", "");
        message.generatedImage = json.optBoolean("generated_image", false);
        message.retryable = json.optBoolean("retryable", false);
        return message;
    }

    boolean hasImage() {
        return imageUri != null && !imageUri.isEmpty();
    }

    boolean hasGeneratedImage() {
        return generatedImage && hasImage();
    }

    boolean isSpeakable() {
        return ROLE_ASSISTANT.equals(role)
                && !error
                && !generatedImage
                && content != null
                && !content.trim().isEmpty();
    }
}
