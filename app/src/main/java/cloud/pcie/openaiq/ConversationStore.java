package cloud.pcie.openaiq;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class ConversationStore {
    private static final String FILE_NAME = "conversation_history.json";
    private static final int FORMAT_VERSION = 2;
    private static final int MAX_CONVERSATIONS = 50;

    static final class Summary {
        final String id;
        final String title;
        final String preview;
        final int messageCount;
        final long updatedAt;
        final boolean active;

        Summary(String id, String title, String preview, int messageCount, long updatedAt, boolean active) {
            this.id = id;
            this.title = title;
            this.preview = preview;
            this.messageCount = messageCount;
            this.updatedAt = updatedAt;
            this.active = active;
        }
    }

    private final Context context;
    private final AtomicFile historyFile;

    ConversationStore(Context context) {
        this.context = context.getApplicationContext();
        historyFile = new AtomicFile(new File(this.context.getFilesDir(), FILE_NAME));
    }

    synchronized boolean exists() {
        return historyFile.getBaseFile().isFile();
    }

    synchronized ArrayList<ChatMessage> load() {
        Snapshot snapshot = readSnapshot();
        Conversation active = snapshot.activeConversation();
        return active == null ? new ArrayList<>() : copyMessages(active.messages);
    }

    synchronized void save(List<ChatMessage> messages) {
        Snapshot snapshot = readSnapshot();
        Conversation active = snapshot.activeConversation();
        if (messages == null || messages.isEmpty()) {
            if (active != null) {
                snapshot.conversations.remove(active);
                snapshot.activeId = "";
                writeSnapshot(snapshot);
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (active == null) {
            active = new Conversation(newId(), "新对话", now, now, new ArrayList<>());
            snapshot.conversations.add(active);
            snapshot.activeId = active.id;
        }
        active.messages = copyMessages(messages);
        active.updatedAt = now;
        if ("新对话".equals(active.title)) {
            active.title = autoTitle(active.messages);
        }
        trimOldest(snapshot);
        writeSnapshot(snapshot);
    }

    synchronized String createConversation() {
        Snapshot snapshot = readSnapshot();
        Conversation active = snapshot.activeConversation();
        if (active == null || active.messages.isEmpty()) {
            return active == null ? "" : active.id;
        }
        // Keep the new chat transient until its first message is saved.
        snapshot.activeId = "";
        writeSnapshot(snapshot);
        return "";
    }

    synchronized boolean selectConversation(String id) {
        Snapshot snapshot = readSnapshot();
        if (snapshot.find(id) == null) {
            return false;
        }
        snapshot.activeId = id;
        writeSnapshot(snapshot);
        return true;
    }

    synchronized boolean renameConversation(String id, String title) {
        String clean = cleanLine(title, 40);
        if (clean.isEmpty()) {
            return false;
        }
        Snapshot snapshot = readSnapshot();
        Conversation conversation = snapshot.find(id);
        if (conversation == null) {
            return false;
        }
        conversation.title = clean;
        conversation.updatedAt = System.currentTimeMillis();
        writeSnapshot(snapshot);
        return true;
    }

    synchronized boolean deleteConversation(String id) {
        Snapshot snapshot = readSnapshot();
        boolean removed = snapshot.conversations.removeIf(item -> item.id.equals(id));
        if (!removed) {
            return false;
        }
        if (id.equals(snapshot.activeId)) {
            snapshot.activeId = snapshot.conversations.isEmpty() ? "" : newest(snapshot.conversations).id;
        }
        writeSnapshot(snapshot);
        return true;
    }

    synchronized List<Summary> list() {
        Snapshot snapshot = readSnapshot();
        ArrayList<Conversation> sorted = new ArrayList<>(snapshot.conversations);
        sorted.sort((left, right) -> Long.compare(right.updatedAt, left.updatedAt));
        ArrayList<Summary> result = new ArrayList<>();
        for (Conversation conversation : sorted) {
            result.add(new Summary(
                    conversation.id,
                    conversation.title,
                    preview(conversation.messages),
                    conversation.messages.size(),
                    conversation.updatedAt,
                    conversation.id.equals(snapshot.activeId)));
        }
        return result;
    }

    synchronized ArrayList<ChatMessage> generatedImages() {
        Snapshot snapshot = readSnapshot();
        snapshot.conversations.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        ArrayList<ChatMessage> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Conversation conversation : snapshot.conversations) {
            for (int i = conversation.messages.size() - 1; i >= 0; i--) {
                ChatMessage message = conversation.messages.get(i);
                if (message.hasGeneratedImage() && seen.add(message.imageUri)) result.add(message);
            }
        }
        return result;
    }

    synchronized ArrayList<ChatMessage> recoverGeneratedImages(List<ChatMessage> messages) {
        ArrayList<ChatMessage> recovered = new ArrayList<>();
        File directory = new File(context.getFilesDir(), "generated_images");
        File[] files = directory.listFiles(File::isFile);
        if (files == null || files.length == 0) {
            return recovered;
        }
        Set<String> knownPaths = new HashSet<>();
        Snapshot snapshot = readSnapshot();
        for (Conversation conversation : snapshot.conversations) {
            collectKnownPaths(conversation.messages, knownPaths);
        }
        collectKnownPaths(messages, knownPaths);
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File file : files) {
            try {
                if (knownPaths.contains(file.getCanonicalPath())) {
                    continue;
                }
            } catch (Exception ignored) {
                continue;
            }
            ChatMessage message = new ChatMessage(
                    ChatMessage.ROLE_ASSISTANT,
                    context.getString(R.string.recovered_generated_image));
            message.mode = ChatMessage.MODE_IMAGE;
            message.imageUri = Uri.fromFile(file).toString();
            message.imageMime = mimeFor(file.getName());
            message.imageName = file.getName();
            message.generatedImage = true;
            recovered.add(message);
        }
        return recovered;
    }

    private Snapshot readSnapshot() {
        if (!exists()) {
            return new Snapshot();
        }
        try (FileInputStream input = historyFile.openRead()) {
            byte[] bytes = new byte[(int) Math.min(input.getChannel().size(), Integer.MAX_VALUE)];
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            JSONObject root = new JSONObject(new String(bytes, 0, offset, StandardCharsets.UTF_8));
            Snapshot snapshot = new Snapshot();
            JSONArray conversations = root.optJSONArray("conversations");
            if (conversations != null) {
                snapshot.activeId = root.optString("active_id", "");
                for (int index = 0; index < conversations.length(); index++) {
                    Conversation parsed = parseConversation(conversations.optJSONObject(index));
                    if (parsed != null) {
                        snapshot.conversations.add(parsed);
                    }
                }
            } else {
                JSONArray legacyMessages = root.optJSONArray("messages");
                if (legacyMessages != null) {
                    ArrayList<ChatMessage> migrated = parseMessages(legacyMessages);
                    long now = historyFile.getBaseFile().lastModified();
                    if (now <= 0L) {
                        now = System.currentTimeMillis();
                    }
                    Conversation legacy = new Conversation(newId(), autoTitle(migrated), now, now, migrated);
                    snapshot.conversations.add(legacy);
                    snapshot.activeId = legacy.id;
                    writeSnapshot(snapshot);
                }
            }
            boolean removedEmpty = snapshot.conversations.removeIf(
                    conversation -> conversation.messages.isEmpty());
            if (removedEmpty) {
                if (snapshot.find(snapshot.activeId) == null) {
                    snapshot.activeId = "";
                }
                writeSnapshot(snapshot);
            } else if (!snapshot.activeId.isEmpty()
                    && snapshot.find(snapshot.activeId) == null
                    && !snapshot.conversations.isEmpty()) {
                snapshot.activeId = newest(snapshot.conversations).id;
            }
            return snapshot;
        } catch (Exception ignored) {
            return new Snapshot();
        }
    }

    private void writeSnapshot(Snapshot snapshot) {
        FileOutputStream output = null;
        try {
            JSONArray conversations = new JSONArray();
            for (Conversation conversation : snapshot.conversations) {
                JSONArray serializedMessages = new JSONArray();
                for (ChatMessage message : conversation.messages) {
                    serializedMessages.put(message.toJson());
                }
                conversations.put(new JSONObject()
                        .put("id", conversation.id)
                        .put("title", conversation.title)
                        .put("created_at", conversation.createdAt)
                        .put("updated_at", conversation.updatedAt)
                        .put("messages", serializedMessages));
            }
            JSONObject root = new JSONObject()
                    .put("version", FORMAT_VERSION)
                    .put("active_id", snapshot.activeId)
                    .put("conversations", conversations);
            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            output = historyFile.startWrite();
            output.write(bytes);
            output.flush();
            historyFile.finishWrite(output);
        } catch (Exception ignored) {
            if (output != null) {
                historyFile.failWrite(output);
            }
        }
    }

    private static Conversation parseConversation(JSONObject item) {
        if (item == null || item.optString("id").isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        return new Conversation(
                item.optString("id"),
                cleanLine(item.optString("title", "新对话"), 40),
                item.optLong("created_at", now),
                item.optLong("updated_at", now),
                parseMessages(item.optJSONArray("messages")));
    }

    private static ArrayList<ChatMessage> parseMessages(JSONArray array) {
        ArrayList<ChatMessage> messages = new ArrayList<>();
        if (array == null) {
            return messages;
        }
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.optJSONObject(index);
            if (item != null) {
                messages.add(ChatMessage.fromJson(item));
            }
        }
        return messages;
    }

    private static ArrayList<ChatMessage> copyMessages(List<ChatMessage> source) {
        ArrayList<ChatMessage> copy = new ArrayList<>();
        for (ChatMessage message : source) {
            try {
                copy.add(ChatMessage.fromJson(message.toJson()));
            } catch (Exception ignored) {
                // Keep valid messages if one item is malformed.
            }
        }
        return copy;
    }

    private static void collectKnownPaths(List<ChatMessage> messages, Set<String> knownPaths) {
        for (ChatMessage message : messages) {
            if (!message.hasGeneratedImage()) {
                continue;
            }
            try {
                Uri uri = Uri.parse(message.imageUri);
                if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
                    knownPaths.add(new File(uri.getPath()).getCanonicalPath());
                }
            } catch (Exception ignored) {
                // Ignore inaccessible historic image URIs.
            }
        }
    }

    private static String autoTitle(List<ChatMessage> messages) {
        for (ChatMessage message : messages) {
            if (ChatMessage.ROLE_USER.equals(message.role) && !message.content.trim().isEmpty()) {
                return cleanLine(message.content, 24);
            }
        }
        return "新对话";
    }

    private static String preview(List<ChatMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage message = messages.get(index);
            String value = message.content == null ? "" : message.content;
            if (value.trim().isEmpty()) {
                value = message.imagePrompt == null ? "" : message.imagePrompt;
            }
            if (!value.trim().isEmpty()) {
                return cleanLine(value, 60);
            }
        }
        return "暂无消息";
    }

    private static String cleanLine(String value, int maximumLength) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return clean.length() > maximumLength ? clean.substring(0, maximumLength) : clean;
    }

    private static String newId() {
        return "chat-" + UUID.randomUUID();
    }

    private static Conversation newest(List<Conversation> conversations) {
        return conversations.stream()
                .max(Comparator.comparingLong(item -> item.updatedAt))
                .orElse(conversations.get(0));
    }

    private static void trimOldest(Snapshot snapshot) {
        while (snapshot.conversations.size() > MAX_CONVERSATIONS) {
            Conversation oldest = snapshot.conversations.stream()
                    .filter(item -> !item.id.equals(snapshot.activeId))
                    .min(Comparator.comparingLong(item -> item.updatedAt))
                    .orElse(null);
            if (oldest == null) {
                break;
            }
            snapshot.conversations.remove(oldest);
        }
    }

    private static final class Snapshot {
        String activeId = "";
        final ArrayList<Conversation> conversations = new ArrayList<>();

        Conversation find(String id) {
            if (id != null) {
                for (Conversation conversation : conversations) {
                    if (conversation.id.equals(id)) {
                        return conversation;
                    }
                }
            }
            return null;
        }

        Conversation activeConversation() {
            return find(activeId);
        }
    }

    private static final class Conversation {
        final String id;
        String title;
        final long createdAt;
        long updatedAt;
        ArrayList<ChatMessage> messages;

        Conversation(String id, String title, long createdAt, long updatedAt, ArrayList<ChatMessage> messages) {
            this.id = id;
            this.title = title.isEmpty() ? "新对话" : title;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.messages = messages;
        }
    }

    private static String mimeFor(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/png";
    }
}
