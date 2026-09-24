package cloud.pcie.openaiq;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

final class OpenAiClient {
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_GENERATED_IMAGE_BYTES = 50 * 1024 * 1024;
    private static final int MAX_IMAGE_RESPONSE_BYTES = 72 * 1024 * 1024;
    private final Context context;

    OpenAiClient(Context context) {
        this.context = context.getApplicationContext();
    }

    interface Listener {
        void onDelta(String text);
        void onToolCall(ToolCall call);
        void onComplete();
        void onError(String message);
    }

    interface ModelsListener {
        void onSuccess(List<String> models);
        void onError(String message);
    }

    interface ImageListener {
        void onSuccess(ImageResult image);
        void onError(String message);
    }

    static final class ImageResult {
        final String uri;
        final String mime;
        final String name;

        ImageResult(String uri, String mime, String name) {
            this.uri = uri;
            this.mime = mime;
            this.name = name;
        }
    }

    static final class ToolCall {
        final String name;
        final String arguments;

        ToolCall(String name, String arguments) {
            this.name = name == null ? "" : name;
            this.arguments = arguments == null ? "" : arguments;
        }
    }

    static final class RequestHandle {
        private volatile boolean cancelled;
        private volatile HttpURLConnection connection;

        void cancel() {
            cancelled = true;
            HttpURLConnection active = connection;
            if (active != null) {
                active.disconnect();
            }
        }

        boolean isCancelled() {
            return cancelled;
        }
    }

    RequestHandle stream(
            ExecutorService executor,
            AppSettings settings,
            List<ChatMessage> conversation,
            Listener listener) {
        RequestHandle handle = new RequestHandle();
        executor.execute(() -> execute(handle, settings, conversation, listener));
        return handle;
    }

    RequestHandle listModels(
            ExecutorService executor,
            String baseUrl,
            String apiKey,
            ModelsListener listener) {
        RequestHandle handle = new RequestHandle();
        executor.execute(() -> {
            try {
                List<String> models = performListModels(handle, baseUrl, apiKey);
                if (!handle.isCancelled()) {
                    listener.onSuccess(models);
                }
            } catch (Exception exception) {
                if (!handle.isCancelled()) {
                    listener.onError(readableError(exception, apiKey));
                }
            }
        });
        return handle;
    }

    RequestHandle generateImage(
            ExecutorService executor,
            AppSettings settings,
            String prompt,
            ChatMessage referenceImage,
            ImageGenerationOptions options,
            ImageListener listener) {
        RequestHandle handle = new RequestHandle();
        executor.execute(() -> {
            try {
                ImageResult result = referenceImage != null && referenceImage.hasImage()
                        ? performEditImage(handle, settings, prompt, referenceImage, options)
                        : performGenerateImage(handle, settings, prompt, options);
                if (!handle.isCancelled()) {
                    listener.onSuccess(result);
                }
            } catch (Exception exception) {
                if (!handle.isCancelled()) {
                    listener.onError(readableError(exception, settings.imageApiKey));
                }
            }
        });
        return handle;
    }

    private void execute(
            RequestHandle handle,
            AppSettings settings,
            List<ChatMessage> conversation,
            Listener listener) {
        try {
            ToolAccumulator toolAccumulator = new ToolAccumulator();
            performRequest(
                    handle,
                    settings,
                    conversation,
                    listener,
                    toolAccumulator,
                    true);
            if (!handle.isCancelled()) {
                for (ToolCall call : toolAccumulator.toCalls()) {
                    listener.onToolCall(call);
                }
                listener.onComplete();
            }
        } catch (Exception exception) {
            if (!handle.isCancelled()) {
                listener.onError(readableError(exception, settings.chatApiKey));
            }
        }
    }

    private List<String> performListModels(
            RequestHandle handle,
            String baseUrl,
            String apiKey) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(handle, buildModelsEndpoint(baseUrl), "GET", apiKey);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new ApiException(formatHttpError(
                        status,
                        readLimited(connection.getErrorStream(), 16_384)));
            }
            JSONObject response = new JSONObject(readLimited(connection.getInputStream(), 4 * 1024 * 1024));
            JSONArray data = response.optJSONArray("data");
            if (data == null) {
                throw new ApiException("模型列表响应中缺少 data 字段");
            }
            List<String> models = new ArrayList<>();
            for (int index = 0; index < data.length(); index++) {
                JSONObject item = data.optJSONObject(index);
                String id = item == null ? "" : item.optString("id").trim();
                if (!id.isEmpty() && !models.contains(id)) {
                    models.add(id);
                }
            }
            Collections.sort(models, String.CASE_INSENSITIVE_ORDER);
            if (models.isEmpty()) {
                throw new ApiException("节点没有返回可用模型");
            }
            return models;
        } finally {
            clearConnection(handle, connection);
        }
    }

    private ImageResult performGenerateImage(
            RequestHandle handle,
            AppSettings settings,
            String prompt,
            ImageGenerationOptions options) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(
                    handle,
                    buildImagesEndpoint(settings.imageBaseUrl),
                    "POST",
                    settings.imageApiKey);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            JSONObject payload = imagePayload(settings.imageModel, prompt, options);
            byte[] requestBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(requestBytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(requestBytes);
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new ApiException(formatHttpError(
                        status,
                        readLimited(connection.getErrorStream(), 16_384)));
            }
            return readImageResult(handle, connection);
        } finally {
            clearConnection(handle, connection);
        }
    }

    private ImageResult performEditImage(
            RequestHandle handle,
            AppSettings settings,
            String prompt,
            ChatMessage referenceImage,
            ImageGenerationOptions options) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(
                    handle,
                    buildImageEditsEndpoint(settings.imageBaseUrl),
                    "POST",
                    settings.imageApiKey);
            String boundary = "NING-" + UUID.randomUUID();
            connection.setDoOutput(true);
            connection.setChunkedStreamingMode(8192);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty(
                    "Content-Type",
                    "multipart/form-data; boundary=" + boundary);
            try (OutputStream output = connection.getOutputStream()) {
                writeMultipartText(output, boundary, "model", settings.imageModel);
                writeMultipartText(output, boundary, "prompt", prompt);
                writeImageOptions(output, boundary, options);
                writeMultipartImage(output, boundary, referenceImage);
                writeUtf8(output, "--" + boundary + "--\r\n");
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new ApiException(formatHttpError(
                        status,
                        readLimited(connection.getErrorStream(), 16_384)));
            }
            return readImageResult(handle, connection);
        } finally {
            clearConnection(handle, connection);
        }
    }

    static JSONObject imagePayload(String model, String prompt, ImageGenerationOptions options) throws Exception {
        JSONObject payload = new JSONObject().put("model", model).put("prompt", prompt);
        if (!options.size.isEmpty()) payload.put("size", options.size);
        if (!options.quality.isEmpty()) payload.put("quality", options.quality);
        return payload;
    }

    void writeImageOptions(OutputStream output, String boundary, ImageGenerationOptions options) throws Exception {
        if (!options.size.isEmpty()) writeMultipartText(output, boundary, "size", options.size);
        if (!options.quality.isEmpty()) writeMultipartText(output, boundary, "quality", options.quality);
    }

    private void writeMultipartText(
            OutputStream output,
            String boundary,
            String name,
            String value) throws Exception {
        writeUtf8(output, "--" + boundary + "\r\n");
        writeUtf8(output, "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        writeUtf8(output, value + "\r\n");
    }

    private void writeMultipartImage(
            OutputStream output,
            String boundary,
            ChatMessage referenceImage) throws Exception {
        String mime = referenceImage.imageMime;
        if (mime == null || !mime.startsWith("image/")) {
            mime = "image/jpeg";
        }
        String fileName = sanitizeFileName(referenceImage.imageName);
        writeUtf8(output, "--" + boundary + "\r\n");
        writeUtf8(
                output,
                "Content-Disposition: form-data; name=\"image[]\"; filename=\""
                        + fileName + "\"\r\n");
        writeUtf8(output, "Content-Type: " + mime + "\r\n\r\n");
        Uri uri = Uri.parse(referenceImage.imageUri);
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new ApiException("无法读取所选参考图片");
            }
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_IMAGE_BYTES) {
                    throw new ApiException("参考图片不能超过 10 MB");
                }
                output.write(buffer, 0, read);
            }
        }
        writeUtf8(output, "\r\n");
    }

    private void writeUtf8(OutputStream output, String value) throws Exception {
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private String sanitizeFileName(String value) {
        String name = value == null || value.trim().isEmpty() ? "reference-image" : value.trim();
        return name.replace('"', '_').replace('\\', '_').replace('\r', '_').replace('\n', '_');
    }

    private ImageResult readImageResult(
            RequestHandle handle,
            HttpURLConnection connection) throws Exception {
        byte[] imageBytes;
        String responseBody = readStrict(
                connection.getInputStream(),
                MAX_IMAGE_RESPONSE_BYTES,
                "生图响应超过 72 MB 限制");
        JSONObject response = new JSONObject(responseBody);
        JSONObject error = response.optJSONObject("error");
        if (error != null) {
            throw new ApiException(error.optString("message", "图片生成失败"));
        }
        JSONArray data = response.optJSONArray("data");
        JSONObject first = data == null ? null : data.optJSONObject(0);
        if (first == null) {
            throw new ApiException("节点未返回图片数据");
        }
        String encoded = first.optString("b64_json");
        if (!encoded.isEmpty()) {
            if (encoded.length() > (MAX_GENERATED_IMAGE_BYTES * 4L / 3L) + 16L) {
                throw new ApiException("生成图片超过 50 MB 限制");
            }
            try {
                imageBytes = Base64.decode(encoded, Base64.DEFAULT);
            } catch (IllegalArgumentException exception) {
                throw new ApiException("节点返回了无效的 Base64 图片数据");
            }
        } else {
            String imageUrl = first.optString("url");
            if (imageUrl.isEmpty()) {
                throw new ApiException("生图响应中没有 b64_json 或 url 字段");
            }
            imageBytes = downloadGeneratedImage(handle, imageUrl);
        }
        if (imageBytes.length == 0 || imageBytes.length > MAX_GENERATED_IMAGE_BYTES) {
            throw new ApiException("生成图片为空或超过 50 MB 限制");
        }
        return saveGeneratedImage(imageBytes);
    }

    private byte[] downloadGeneratedImage(
            RequestHandle handle,
            String imageUrl) throws Exception {
        URI uri = URI.create(imageUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new ApiException("节点返回了不安全的图片下载地址");
        }
        HttpURLConnection connection = null;
        try {
            connection = openConnection(handle, imageUrl, "GET", "");
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "image/*");
            int status = connection.getResponseCode();
            if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
                throw new ApiException("图片下载地址跳转到了非 HTTPS 地址");
            }
            if (status < 200 || status >= 300) {
                throw new ApiException("下载生成图片失败：HTTP " + status);
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_GENERATED_IMAGE_BYTES) {
                throw new ApiException("生成图片超过 50 MB 限制");
            }
            return readStrictBytes(
                    connection.getInputStream(),
                    MAX_GENERATED_IMAGE_BYTES,
                    "生成图片超过 50 MB 限制");
        } finally {
            clearConnection(handle, connection);
        }
    }

    private ImageResult saveGeneratedImage(byte[] bytes) throws Exception {
        String extension;
        String mime;
        if (startsWith(bytes, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) {
            extension = ".png";
            mime = "image/png";
        } else if (startsWith(bytes, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff})) {
            extension = ".jpg";
            mime = "image/jpeg";
        } else if (bytes.length >= 12
                && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) {
            extension = ".webp";
            mime = "image/webp";
        } else {
            throw new ApiException("节点返回的数据不是 PNG、JPEG 或 WebP 图片");
        }
        File directory = new File(context.getFilesDir(), "generated_images");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new ApiException("无法创建图片保存目录");
        }
        String name = "ning-" + System.currentTimeMillis()
                + "-" + UUID.randomUUID().toString().substring(0, 8) + extension;
        File file = new File(directory, name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
        return new ImageResult(Uri.fromFile(file).toString(), mime, name);
    }

    private boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (content[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private HttpURLConnection openConnection(
            RequestHandle handle,
            String endpoint,
            String method,
            String apiKey) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URI(endpoint).toURL().openConnection();
        handle.connection = connection;
        connection.setRequestMethod(method);
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(180_000);
        if (apiKey != null && !apiKey.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        return connection;
    }

    private void clearConnection(RequestHandle handle, HttpURLConnection connection) {
        if (connection == null) {
            return;
        }
        if (handle.connection == connection) {
            handle.connection = null;
        }
        connection.disconnect();
    }

    private void performRequest(
            RequestHandle handle,
            AppSettings settings,
            List<ChatMessage> conversation,
            Listener listener,
            ToolAccumulator toolAccumulator,
            boolean includeTools) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL endpoint = new URI(buildEndpoint(settings.chatBaseUrl)).toURL();
            connection = (HttpURLConnection) endpoint.openConnection();
            handle.connection = connection;
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(120_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "text/event-stream, application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Authorization", "Bearer " + settings.chatApiKey);

            byte[] payload = createPayload(settings, conversation, includeTools)
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                String errorBody = readLimited(connection.getErrorStream(), 16_384);
                if (status == 400 && includeTools && !handle.isCancelled()) {
                    connection.disconnect();
                    handle.connection = null;
                    connection = null;
                    performRequest(
                            handle,
                            settings,
                            conversation,
                            listener,
                            toolAccumulator,
                            false);
                    return;
                }
                throw new ApiException(formatHttpError(status, errorBody));
            }

            String contentType = connection.getContentType();
            if (contentType != null
                    && contentType.toLowerCase(Locale.ROOT).contains("text/event-stream")) {
                readEventStream(handle, connection.getInputStream(), listener, toolAccumulator);
            } else {
                String body = readLimited(connection.getInputStream(), 4 * 1024 * 1024);
                String text = extractText(new JSONObject(body), toolAccumulator);
                if (!text.isEmpty()) {
                    listener.onDelta(text);
                }
            }
        } finally {
            if (handle.connection == connection) {
                handle.connection = null;
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private JSONObject createPayload(
            AppSettings settings,
            List<ChatMessage> conversation,
            boolean includeTools)
            throws Exception {
        JSONArray messages = new JSONArray();
        if (!settings.systemPrompt.isEmpty()) {
            messages.put(new JSONObject()
                    .put("role", "system")
                    .put("content", settings.systemPrompt));
        }
        messages.put(new JSONObject()
                .put("role", "system")
                .put("content", includeTools
                        ? "你可以使用提供的设备工具、实时天气工具和图片生成工具。只有当前用户明确要求操作本机时才调用设备工具；"
                                + "应用名称必须使用用户说出的名称，不得猜包名。工具结果由客户端展示，不要声称已执行尚未调用的动作。"
                                + "用户询问当前或未来天气时必须调用 get_weather。若用户明确说出城市或地区，将其放入 location；"
                                + "若用户没有提供地点，设置 use_current_location=true，让客户端通过设备定位查询，不要先追问地点。"
                                + "当用户明确要求生成、绘制或创作图片时，必须调用 generate_image，并把完整、可直接生图的描述放进 prompt；"
                                + "普通图片分析、询问生图方法或非图片内容创作不要调用 generate_image。"
                        : "当前模型节点不支持工具。本轮只能正常回答，不能声称已经执行设备操作或生成图片。"));
        for (ChatMessage message : conversation) {
            if (message.error || (message.content.trim().isEmpty() && !message.hasImage())) {
                continue;
            }
            messages.put(new JSONObject()
                    .put("role", message.role)
                    .put("content", message.hasGeneratedImage()
                            ? message.content
                            : createContent(message)));
        }
        JSONObject payload = new JSONObject()
                .put("model", settings.chatModel)
                .put("messages", messages)
                .put("stream", true);
        if (includeTools) {
            payload.put("tools", createTools()).put("tool_choice", "auto");
        }
        return payload;
    }

    private JSONArray createTools() throws Exception {
        JSONArray tools = new JSONArray();
        tools.put(functionTool(
                "open_app",
                "Open an installed launcher application when the user explicitly asks to open it.",
                new JSONObject().put("app_name", stringProperty("The app display name spoken by the user.")),
                new JSONArray().put("app_name")));
        tools.put(functionTool(
                "set_media_volume",
                "Set Android media volume to an exact percentage.",
                new JSONObject().put("percent", new JSONObject()
                        .put("type", "integer")
                        .put("minimum", 0)
                        .put("maximum", 100)),
                new JSONArray().put("percent")));
        tools.put(functionTool(
                "adjust_media_volume",
                "Raise or lower Android media volume by a small number of steps.",
                new JSONObject()
                        .put("direction", enumProperty("up", "down"))
                        .put("steps", new JSONObject()
                                .put("type", "integer")
                                .put("minimum", 1)
                                .put("maximum", 5)),
                new JSONArray().put("direction").put("steps")));
        tools.put(functionTool(
                "media_control",
                "Control the active Android media session. Use play for an explicit request to play music.",
                new JSONObject().put("action", enumProperty(
                        "play", "pause", "toggle", "next", "previous", "stop")),
                new JSONArray().put("action")));
        tools.put(functionTool(
                "open_system_settings",
                "Open a supported Android settings page. Wi-Fi and Bluetooth switches must be changed by the user there.",
                new JSONObject().put("panel", enumProperty(
                        "settings", "wifi", "bluetooth", "display", "sound",
                        "apps", "accessibility", "battery")),
                new JSONArray().put("panel")));
        tools.put(functionTool(
                "navigate_to_place",
                "Open Baidu Maps with a route when the user explicitly asks to navigate to a destination.",
                new JSONObject().put(
                        "destination",
                        stringProperty("The destination explicitly named by the user.")),
                new JSONArray().put("destination")));
        tools.put(functionTool(
                "get_weather",
                "Get real-time weather and a forecast for an explicit place or the device's current location.",
                new JSONObject()
                        .put("location", stringProperty(
                                "City or region explicitly provided by the user. Omit for current location."))
                        .put("use_current_location", new JSONObject()
                                .put("type", "boolean")
                                .put("description", "True when the user did not name a location."))
                        .put("forecast_days", new JSONObject()
                                .put("type", "integer")
                                .put("minimum", 1)
                                .put("maximum", 7)),
                new JSONArray()));
        tools.put(functionTool(
                "generate_image",
                "Generate an image only when the user explicitly asks to create, draw, or generate one.",
                new JSONObject().put(
                        "prompt",
                        stringProperty("A complete standalone prompt for the image generation model.")),
                new JSONArray().put("prompt")));
        return tools;
    }

    private JSONObject functionTool(
            String name,
            String description,
            JSONObject properties,
            JSONArray required) throws Exception {
        JSONObject parameters = new JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", required)
                .put("additionalProperties", false);
        return new JSONObject()
                .put("type", "function")
                .put("function", new JSONObject()
                        .put("name", name)
                        .put("description", description)
                        .put("parameters", parameters));
    }

    private JSONObject stringProperty(String description) throws Exception {
        return new JSONObject().put("type", "string").put("description", description);
    }

    private JSONObject enumProperty(String... values) throws Exception {
        JSONArray choices = new JSONArray();
        for (String value : values) {
            choices.put(value);
        }
        return new JSONObject().put("type", "string").put("enum", choices);
    }

    private Object createContent(ChatMessage message) throws Exception {
        if (!message.hasImage()) {
            return message.content;
        }
        JSONArray parts = new JSONArray();
        if (!message.content.trim().isEmpty()) {
            parts.put(new JSONObject()
                    .put("type", "text")
                    .put("text", message.content));
        }
        parts.put(new JSONObject()
                .put("type", "image_url")
                .put("image_url", new JSONObject()
                        .put("url", readImageDataUrl(message))
                        .put("detail", "auto")));
        return parts;
    }

    private String readImageDataUrl(ChatMessage message) throws Exception {
        Uri uri = Uri.parse(message.imageUri);
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) {
                throw new ApiException("无法读取所选图片");
            }
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_IMAGE_BYTES) {
                    throw new ApiException("图片不能超过 10 MB");
                }
                output.write(buffer, 0, read);
            }
            String mime = message.imageMime;
            if (mime == null || !mime.startsWith("image/")) {
                mime = "image/jpeg";
            }
            return "data:" + mime + ";base64,"
                    + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        }
    }

    private void readEventStream(
            RequestHandle handle,
            InputStream input,
            Listener listener,
            ToolAccumulator toolAccumulator)
            throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while (!handle.isCancelled() && (line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    return;
                }
                String text = extractText(new JSONObject(data), toolAccumulator);
                if (!text.isEmpty()) {
                    listener.onDelta(text);
                }
            }
        }
    }

    private String extractText(JSONObject response, ToolAccumulator toolAccumulator) {
        JSONObject error = response.optJSONObject("error");
        if (error != null) {
            String message = error.optString("message", "模型节点返回错误");
            throw new ApiException(message);
        }

        JSONArray choices = response.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            return "";
        }
        JSONObject choice = choices.optJSONObject(0);
        if (choice == null) {
            return "";
        }
        JSONObject delta = choice.optJSONObject("delta");
        if (delta != null) {
            toolAccumulator.accept(delta.optJSONArray("tool_calls"));
            return contentToText(delta.opt("content"));
        }
        JSONObject message = choice.optJSONObject("message");
        if (message == null) {
            return "";
        }
        toolAccumulator.accept(message.optJSONArray("tool_calls"));
        return contentToText(message.opt("content"));
    }

    private String contentToText(Object content) {
        if (content instanceof String) {
            return (String) content;
        }
        if (content instanceof JSONArray) {
            StringBuilder combined = new StringBuilder();
            JSONArray parts = (JSONArray) content;
            for (int index = 0; index < parts.length(); index++) {
                JSONObject part = parts.optJSONObject(index);
                if (part != null && "text".equals(part.optString("type"))) {
                    combined.append(part.optString("text"));
                }
            }
            return combined.toString();
        }
        return "";
    }

    static String buildEndpoint(String baseUrl) {
        return buildApiEndpoint(baseUrl, "chat/completions");
    }

    static String buildModelsEndpoint(String baseUrl) {
        return buildApiEndpoint(baseUrl, "models");
    }

    static String buildImagesEndpoint(String baseUrl) {
        return buildApiEndpoint(baseUrl, "images/generations");
    }

    static String buildImageEditsEndpoint(String baseUrl) {
        return buildApiEndpoint(baseUrl, "images/edits");
    }

    private static String buildApiEndpoint(String baseUrl, String resource) {
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String[] knownResources = {
                "/chat/completions", "/images/generations", "/images/edits", "/models"};
        for (String known : knownResources) {
            if (normalized.endsWith(known)) {
                normalized = normalized.substring(0, normalized.length() - known.length());
                break;
            }
        }
        try {
            URI uri = URI.create(normalized);
            String path = uri.getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                return normalized + "/v1/" + resource;
            }
        } catch (IllegalArgumentException ignored) {
            // Settings validation presents a clearer URL error before this path is reached.
        }
        return normalized + "/" + resource;
    }

    private String formatHttpError(int status, String body) {
        try {
            JSONObject json = new JSONObject(body);
            JSONObject error = json.optJSONObject("error");
            if (error != null) {
                String message = error.optString("message");
                if (!message.isEmpty()) {
                    return "HTTP " + status + "：" + message;
                }
            }
        } catch (Exception ignored) {
            // Fall through to a bounded plain-text error.
        }
        String compact = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (compact.length() > 240) {
            compact = compact.substring(0, 240) + "...";
        }
        return compact.isEmpty() ? "HTTP " + status : "HTTP " + status + "：" + compact;
    }

    private String readableError(Exception exception, String apiKey) {
        String readable;
        if (exception instanceof ApiException) {
            readable = exception.getMessage();
        } else {
            String message = exception.getMessage();
            readable = message == null || message.trim().isEmpty()
                    ? "网络请求失败，请检查服务地址和网络连接。"
                    : "网络请求失败：" + message;
        }
        if (readable == null) {
            readable = "未知错误";
        }
        return apiKey == null || apiKey.isEmpty()
                ? readable
                : readable.replace(apiKey, "[REDACTED]");
    }

    private String readStrict(InputStream input, int limit, String tooLargeMessage) throws Exception {
        return new String(readStrictBytes(input, limit, tooLargeMessage), StandardCharsets.UTF_8);
    }

    private byte[] readStrictBytes(InputStream input, int limit, String tooLargeMessage) throws Exception {
        if (input == null) {
            return new byte[0];
        }
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                total += read;
                if (total > limit) {
                    throw new ApiException(tooLargeMessage);
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private String readLimited(InputStream input, int limit) throws Exception {
        if (input == null) {
            return "";
        }
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while (total < limit && (read = stream.read(buffer, 0, Math.min(buffer.length, limit - total))) >= 0) {
                output.write(buffer, 0, read);
                total += read;
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static final class ApiException extends RuntimeException {
        ApiException(String message) {
            super(message);
        }
    }

    private static final class ToolAccumulator {
        private final Map<Integer, ToolBuilder> builders = new TreeMap<>();

        void accept(JSONArray toolCalls) {
            if (toolCalls == null) {
                return;
            }
            for (int arrayIndex = 0; arrayIndex < toolCalls.length(); arrayIndex++) {
                JSONObject item = toolCalls.optJSONObject(arrayIndex);
                if (item == null) {
                    continue;
                }
                int index = item.optInt("index", arrayIndex);
                ToolBuilder builder = builders.computeIfAbsent(index, ignored -> new ToolBuilder());
                JSONObject function = item.optJSONObject("function");
                if (function == null) {
                    continue;
                }
                String name = function.optString("name");
                if (!name.isEmpty()) {
                    builder.name = name;
                }
                String arguments = function.optString("arguments");
                if (!arguments.isEmpty()) {
                    builder.arguments.append(arguments);
                }
            }
        }

        List<ToolCall> toCalls() {
            List<ToolCall> calls = new java.util.ArrayList<>();
            for (ToolBuilder builder : builders.values()) {
                if (!builder.name.isEmpty()) {
                    calls.add(new ToolCall(builder.name, builder.arguments.toString()));
                }
            }
            return calls;
        }
    }

    private static final class ToolBuilder {
        String name = "";
        final StringBuilder arguments = new StringBuilder();
    }
}
