package cloud.pcie.openaiq;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String STATE_MESSAGES = "messages";
    private static final String STATE_DRAFT = "draft";
    private static final String STATE_IMAGE_URI = "image_uri";
    private static final String STATE_IMAGE_MIME = "image_mime";
    private static final String STATE_IMAGE_NAME = "image_name";
    private static final String STATE_DOWNLOAD_URI = "download_uri";
    private static final String STATE_DOWNLOAD_MIME = "download_mime";
    private static final String STATE_DOWNLOAD_NAME = "download_name";
    private static final int REQUEST_PICK_IMAGE = 41;
    private static final int REQUEST_SAVE_IMAGE = 42;
    private static final int REQUEST_HISTORY = 43;
    private static final int REQUEST_RECORD_AUDIO = 44;
    private static final long MAX_IMAGE_BYTES = 10L * 1024L * 1024L;

    private final ArrayList<ChatMessage> messages = new ArrayList<>();
    private final ArrayList<OpenAiClient.ToolCall> pendingToolCalls = new ArrayList<>();
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private OpenAiClient client;
    private DeviceActionExecutor actionExecutor;
    private ConversationStore conversationStore;
    private SpeechController speechController;
    private VoiceConversationController voiceController;

    private MessageAdapter adapter;
    private ListView messageList;
    private EditText messageInput;
    private Button attachmentButton;
    private Button chatButton;
    private Button imageButton;
    private Button sendButton;
    private TextView statusText;
    private TextView activeModelText;
    private View voiceControls;
    private View composerRow;
    private View voiceModeButton;
    private ImageButton keyboardButton;
    private ImageButton audioActionButton;
    private ImageButton viewToggleButton;
    private View avatarDialog;
    private TextView avatarDialogRole;
    private TextView avatarDialogText;
    private Live2DAvatarView live2dAvatar;
    private View avatarStage;
    private TextView avatarStatusText;
    private TextView voiceStatusText;
    private ImageButton voiceButton;
    private View attachmentBar;
    private ImageView attachmentPreview;
    private TextView attachmentText;
    private OpenAiClient.RequestHandle activeHandle;
    private ChatMessage activeMessage;
    private boolean busy;
    private int requestGeneration;
    private String pendingImageUri = "";
    private String pendingImageMime = "";
    private String pendingImageName = "";
    private long requestStartedAtMs;
    private String activeMode = ChatMessage.MODE_CHAT;
    private String selectedMode = ChatMessage.MODE_CHAT;
    private boolean interactiveTextInput;
    private boolean avatarFullBody;
    private String pendingDownloadUri = "";
    private String pendingDownloadMime = "image/png";
    private String pendingDownloadName = "ning-image.png";
    private boolean forceSpeakNextReply;
    private boolean avatarReady;
    private boolean avatarEnabled;
    private String avatarDisplayName = "Hiyori";
    private String activeAvatarId = "";
    private SpeechController.State speechState = SpeechController.State.IDLE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        client = new OpenAiClient(getApplicationContext());
        actionExecutor = new DeviceActionExecutor(this);
        conversationStore = new ConversationStore(this);
        live2dAvatar = findViewById(R.id.live2dAvatar);
        avatarStage = findViewById(R.id.avatarStage);
        avatarStatusText = findViewById(R.id.avatarStatusText);
        voiceStatusText = findViewById(R.id.voiceStatusText);
        voiceButton = findViewById(R.id.voiceButton);
        live2dAvatar.setListener(new Live2DAvatarView.Listener() {
            @Override
            public void onReady(String modelName) {
                avatarReady = true;
                updateAvatarState();
            }

            @Override
            public void onError(String message) {
                avatarReady = false;
                avatarStatusText.setText(avatarDisplayName + " · 加载失败");
                Toast.makeText(MainActivity.this,
                        getString(R.string.avatar_failed) + "：" + message,
                        Toast.LENGTH_LONG).show();
            }
        });
        speechController = new SpeechController(this, new SpeechController.Listener() {
            @Override
            public void onStateChanged(ChatMessage message, SpeechController.State state, int attempt) {
                speechState = state;
                refreshMessages(false);
                updateModeViews();
                if (state == SpeechController.State.PREPARING) {
                    live2dAvatar.setSpeaking(false);
                    setAvatarState("answering", R.string.avatar_answering);
                    statusText.setText(attempt == 1
                            ? getString(R.string.speech_preparing)
                            : getString(R.string.speech_retrying, attempt));
                } else if (state == SpeechController.State.PLAYING) {
                    live2dAvatar.setSpeaking(true);
                    setAvatarState("answering", R.string.avatar_speaking);
                    statusText.setText(getString(R.string.tts_preview_playing));
                } else {
                    live2dAvatar.setSpeaking(false);
                    updateAvatarState();
                    updateNodeStatus();
                }
            }

            @Override
            public void onFailure(ChatMessage message, String error) {
                speechState = SpeechController.State.IDLE;
                refreshMessages(false);
                updateModeViews();
                live2dAvatar.setSpeaking(false);
                updateAvatarState();
                updateNodeStatus();
                Toast.makeText(
                        MainActivity.this,
                        getString(R.string.speech_failed, error),
                        Toast.LENGTH_LONG).show();
            }
        });

        messageList = findViewById(R.id.messageList);
        messageInput = findViewById(R.id.messageInput);
        attachmentButton = findViewById(R.id.attachmentButton);
        chatButton = findViewById(R.id.chatButton);
        imageButton = findViewById(R.id.imageButton);
        sendButton = findViewById(R.id.sendButton);
        statusText = findViewById(R.id.statusText);
        activeModelText = findViewById(R.id.activeModelText);
        voiceControls = findViewById(R.id.voiceControls);
        composerRow = findViewById(R.id.composerRow);
        voiceModeButton = findViewById(R.id.voiceModeButton);
        keyboardButton = findViewById(R.id.keyboardButton);
        audioActionButton = findViewById(R.id.audioActionButton);
        viewToggleButton = findViewById(R.id.viewToggleButton);
        avatarDialog = findViewById(R.id.avatarDialog);
        avatarDialogRole = findViewById(R.id.avatarDialogRole);
        avatarDialogText = findViewById(R.id.avatarDialogText);
        attachmentBar = findViewById(R.id.attachmentBar);
        attachmentPreview = findViewById(R.id.attachmentPreview);
        attachmentText = findViewById(R.id.attachmentText);
        voiceController = new VoiceConversationController(this,
                new VoiceConversationController.Listener() {
                    @Override
                    public void onStateChanged(VoiceConversationController.State state) {
                        updateVoiceState(state);
                    }

                    @Override
                    public void onPartialResult(String text) {
                        messageInput.setText(text);
                        messageInput.setSelection(messageInput.length());
                    }

                    @Override
                    public void onResult(String text) {
                        messageInput.setText(text);
                        messageInput.setSelection(messageInput.length());
                        submitVoiceMessage();
                    }

                    @Override
                    public void onError(String message) {
                        updateVoiceState(VoiceConversationController.State.IDLE);
                        Toast.makeText(MainActivity.this,
                                getString(R.string.voice_failed, message),
                                Toast.LENGTH_LONG).show();
                    }
                });
        adapter = new MessageAdapter(this, messages, new MessageAdapter.Actions() {
            @Override
            public void onRetry(int position) {
                retryMessage(position);
            }

            @Override
            public void onOpenImage(ChatMessage message) {
                openImage(message);
            }

            @Override
            public void onDownloadImage(ChatMessage message) {
                requestImageDownload(message);
            }

            @Override
            public void onDelete(ChatMessage message) {
                confirmDeleteMessage(message);
            }

            @Override
            public void onSpeak(ChatMessage message) {
                speechController.toggle(message, AppSettings.load(MainActivity.this));
            }

            @Override
            public boolean isSpeaking(ChatMessage message) {
                return speechController.isActive(message);
            }
        });
        messageList.setAdapter(adapter);

        restoreState(savedInstanceState);
        chatButton.setOnClickListener(view -> setSelectedMode(ChatMessage.MODE_CHAT));
        imageButton.setOnClickListener(view -> setSelectedMode(ChatMessage.MODE_IMAGE));
        sendButton.setOnClickListener(view -> {
            if (busy) {
                stopGeneration();
            } else if (ChatMessage.MODE_IMAGE.equals(selectedMode)) {
                sendImageMessage();
            } else {
                sendChatMessage();
            }
        });
        attachmentButton.setOnClickListener(view -> pickImage());
        voiceButton.setOnClickListener(view -> toggleVoiceConversation());
        keyboardButton.setOnClickListener(view -> showInteractiveKeyboard());
        voiceModeButton.setOnClickListener(view -> returnToVoiceMode());
        audioActionButton.setOnClickListener(view -> onInteractiveAudioAction());
        viewToggleButton.setOnClickListener(view -> toggleAvatarViewMode());
        findViewById(R.id.removeAttachmentButton).setOnClickListener(view -> clearPendingImage());
        messageInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                if (ChatMessage.MODE_IMAGE.equals(selectedMode)) {
                    sendImageMessage();
                } else {
                    sendChatMessage();
                }
                return true;
            }
            return false;
        });
        findViewById(R.id.historyButton).setOnClickListener(view -> {
            voiceController.cancel();
            stopGeneration();
            saveConversation();
            startActivityForResult(new Intent(this, HistoryActivity.class), REQUEST_HISTORY);
        });
        findViewById(R.id.newConversationButton).setOnClickListener(view -> startNewConversation());
        findViewById(R.id.titleSettingsButton).setOnClickListener(view -> {
            voiceController.cancel();
            stopGeneration();
            startActivity(new Intent(this, SettingsActivity.class));
        });

        AppSettings settings = AppSettings.load(this);
        setSelectedMode(ChatMessage.MODE_CHAT);
        if (!settings.isConfigured() && savedInstanceState == null) {
            startActivity(new Intent(this, SettingsActivity.class));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        live2dAvatar.onResume();
        applyAvatarSettings();
        updateNodeStatus();
    }

    @Override
    protected void onPause() {
        live2dAvatar.onPause();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        JSONArray serialized = new JSONArray();
        for (ChatMessage message : messages) {
            try {
                serialized.put(message.toJson());
            } catch (Exception ignored) {
                // Keep the rest of the conversation if a single item cannot be serialized.
            }
        }
        outState.putString(STATE_MESSAGES, serialized.toString());
        outState.putString(STATE_DRAFT, messageInput.getText().toString());
        outState.putString(STATE_IMAGE_URI, pendingImageUri);
        outState.putString(STATE_IMAGE_MIME, pendingImageMime);
        outState.putString(STATE_IMAGE_NAME, pendingImageName);
        outState.putString(STATE_DOWNLOAD_URI, pendingDownloadUri);
        outState.putString(STATE_DOWNLOAD_MIME, pendingDownloadMime);
        outState.putString(STATE_DOWNLOAD_NAME, pendingDownloadName);
    }

    @Override
    protected void onStop() {
        voiceController.cancel();
        speechController.stop();
        conversationStore.save(messages);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (activeHandle != null) {
            activeHandle.cancel();
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        networkExecutor.shutdownNow();
        fileExecutor.shutdownNow();
        voiceController.release();
        speechController.release();
        live2dAvatar.destroy();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RECORD_AUDIO) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startVoiceConversation();
        } else {
            Toast.makeText(this, R.string.voice_permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_HISTORY) {
            if (resultCode == RESULT_OK) {
                loadActiveConversation();
            }
            return;
        }
        if (requestCode == REQUEST_SAVE_IMAGE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                saveImageTo(data.getData());
            }
            return;
        }
        if (requestCode != REQUEST_PICK_IMAGE || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        try {
            if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                getContentResolver().takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        } catch (SecurityException ignored) {
            // The provider may grant access for the current app lifetime only.
        }

        ImageInfo info = readImageInfo(uri);
        if (!isSupportedReferenceImage(info.mime)) {
            Toast.makeText(this, "仅支持 JPEG、PNG 或 WebP 图片", Toast.LENGTH_LONG).show();
            return;
        }
        if (info.size > MAX_IMAGE_BYTES) {
            Toast.makeText(this, "图片不能超过 10 MB", Toast.LENGTH_LONG).show();
            return;
        }
        pendingImageUri = uri.toString();
        pendingImageMime = info.mime;
        pendingImageName = info.name;
        showPendingImage();
    }

    private void toggleVoiceConversation() {
        if (busy) {
            return;
        }
        if (voiceController.isActive()) {
            voiceController.finish();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO);
            return;
        }
        startVoiceConversation();
    }

    private void startVoiceConversation() {
        if (busy) {
            return;
        }
        speechController.stop();
        messageInput.setText("");
        hideKeyboard();
        voiceController.start();
    }

    private void showInteractiveKeyboard() {
        interactiveTextInput = true;
        updateModeViews();
        messageInput.requestFocus();
        InputMethodManager manager = getSystemService(InputMethodManager.class);
        if (manager != null) {
            manager.showSoftInput(messageInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void returnToVoiceMode() {
        interactiveTextInput = false;
        hideKeyboard();
        updateModeViews();
    }

    private void onInteractiveAudioAction() {
        if (busy) {
            stopGeneration();
            return;
        }
        if (speechController.isActive()) {
            speechController.stop();
            return;
        }
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage message = messages.get(index);
            if (ChatMessage.ROLE_ASSISTANT.equals(message.role) && message.isSpeakable()) {
                speechController.speak(message, AppSettings.load(this));
                return;
            }
        }
    }

    private void toggleAvatarViewMode() {
        avatarFullBody = !avatarFullBody;
        AppSettings.saveAvatarFullBody(this, avatarFullBody);
        live2dAvatar.setViewMode(avatarFullBody);
        updateAvatarViewIcon();
    }

    private void updateAvatarViewIcon() {
        viewToggleButton.setImageResource(avatarFullBody
                ? R.drawable.ic_avatar_portrait
                : R.drawable.ic_avatar_full);
        viewToggleButton.setContentDescription(getString(avatarFullBody
                ? R.string.switch_to_portrait
                : R.string.switch_to_full_body));
    }

    private void submitVoiceMessage() {
        String text = messageInput.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        if (!AppSettings.load(this).isChatConfigured()) {
            Toast.makeText(this, "请先填写节点配置和对话模型", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        forceSpeakNextReply = true;
        live2dAvatar.triggerGesture();
        sendChatMessage();
    }

    private void updateVoiceState(VoiceConversationController.State state) {
        boolean active = state != VoiceConversationController.State.IDLE;
        voiceButton.setContentDescription(active
                ? getString(R.string.stop_voice_chat)
                : getString(R.string.start_voice_chat));
        voiceButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getColor(active ? R.color.warning : R.color.accent)));
        if (state == VoiceConversationController.State.LISTENING) {
            voiceStatusText.setText(R.string.voice_listening);
            setAvatarState("listening", R.string.avatar_listening);
        } else if (state == VoiceConversationController.State.PROCESSING) {
            voiceStatusText.setText(R.string.voice_processing);
            setAvatarState("thinking", R.string.avatar_thinking);
        } else {
            voiceStatusText.setText(R.string.tap_to_talk);
            updateAvatarState();
        }
    }

    private void setAvatarState(String state, int statusTextResource) {
        live2dAvatar.setAvatarState(state);
        if (avatarReady) {
            avatarStatusText.setText(avatarDisplayName + " · " + avatarStateLabel(statusTextResource));
        }
    }

    private String avatarStateLabel(int statusTextResource) {
        if (statusTextResource == R.string.avatar_listening) return "正在聆听";
        if (statusTextResource == R.string.avatar_thinking) return "正在思考";
        if (statusTextResource == R.string.avatar_answering) return "正在回答";
        if (statusTextResource == R.string.avatar_speaking) return "正在朗读";
        if (statusTextResource == R.string.avatar_failed) return "加载失败";
        if (statusTextResource == R.string.avatar_loading) return "正在加载";
        return "在线";
    }

    private void applyAvatarSettings() {
        AppSettings settings = AppSettings.load(this);
        avatarFullBody = AppSettings.loadAvatarFullBody(this);
        avatarEnabled = settings.avatarEnabled;
        updateModeViews();
        AvatarCatalog.Avatar avatar = AvatarCatalog.find(this, settings.avatarId);
        avatarDisplayName = avatar == null ? settings.avatarId : avatar.name;
        updateAvatarDialog();
        if (!avatarEnabled) {
            voiceController.cancel();
            live2dAvatar.setSpeaking(false);
            return;
        }
        if (!settings.avatarId.equals(activeAvatarId)) {
            activeAvatarId = settings.avatarId;
            avatarReady = false;
            avatarStatusText.setText(avatarDisplayName + " · 正在加载");
            live2dAvatar.setModel(settings.avatarId);
        } else {
            updateAvatarState();
        }
        live2dAvatar.setViewMode(avatarFullBody);
        updateAvatarViewIcon();
    }

    private void updateAvatarState() {
        if (voiceController != null && voiceController.isActive()) {
            return;
        }
        if (speechState == SpeechController.State.PLAYING) {
            live2dAvatar.setSpeaking(true);
            setAvatarState("answering", R.string.avatar_speaking);
        } else if (speechState == SpeechController.State.PREPARING) {
            live2dAvatar.setSpeaking(false);
            setAvatarState("answering", R.string.avatar_answering);
        } else if (busy) {
            boolean answering = activeMessage != null && !activeMessage.content.trim().isEmpty();
            setAvatarState(answering ? "answering" : "thinking",
                    answering ? R.string.avatar_answering : R.string.avatar_thinking);
        } else {
            live2dAvatar.setSpeaking(false);
            setAvatarState("idle", R.string.avatar_ready);
        }
    }

    private void sendChatMessage() {
        String text = messageInput.getText().toString().trim();
        if (text.isEmpty() && pendingImageUri.isEmpty()) {
            return;
        }

        AppSettings settings = AppSettings.load(this);
        DeviceAction localAction = pendingImageUri.isEmpty()
                ? LocalIntentParser.parse(text)
                : null;
        if (localAction != null) {
            long localStartedAt = SystemClock.elapsedRealtime();
            ChatMessage userMessage = new ChatMessage(ChatMessage.ROLE_USER, text);
            userMessage.mode = ChatMessage.MODE_CHAT;
            messages.add(userMessage);
            messageInput.setText("");
            hideKeyboard();
            DeviceActionExecutor.Result result = actionExecutor.execute(localAction);
            ChatMessage reply = new ChatMessage(ChatMessage.ROLE_ASSISTANT, result.message);
            reply.mode = ChatMessage.MODE_CHAT;
            reply.error = !result.success;
            reply.meta = formatLocalDuration(SystemClock.elapsedRealtime() - localStartedAt);
            messages.add(reply);
            saveConversation();
            refreshMessages();
            updateNodeStatus();
            boolean shouldSpeak = forceSpeakNextReply || settings.autoSpeak;
            forceSpeakNextReply = false;
            if (shouldSpeak && reply.isSpeakable()) {
                speechController.speak(reply, settings);
            }
            return;
        }

        if (!settings.isChatConfigured()) {
            forceSpeakNextReply = false;
            Toast.makeText(this, "请先填写节点配置和对话模型", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }

        ChatMessage userMessage = new ChatMessage(ChatMessage.ROLE_USER, text);
        userMessage.mode = ChatMessage.MODE_CHAT;
        userMessage.imageUri = pendingImageUri;
        userMessage.imageMime = pendingImageMime;
        userMessage.imageName = pendingImageName;
        messages.add(userMessage);
        List<ChatMessage> requestMessages = new ArrayList<>(messages);
        ChatMessage responseMessage = new ChatMessage(ChatMessage.ROLE_ASSISTANT, "");
        responseMessage.mode = ChatMessage.MODE_CHAT;
        messages.add(responseMessage);
        saveConversation();
        messageInput.setText("");
        clearPendingImage();
        hideKeyboard();
        beginChatRequest(settings, requestMessages, responseMessage, text);
    }

    private void sendImageMessage() {
        String prompt = messageInput.getText().toString().trim();
        if (prompt.isEmpty()) {
            Toast.makeText(this, "请输入生图描述", Toast.LENGTH_SHORT).show();
            return;
        }

        AppSettings settings = AppSettings.load(this);
        if (!settings.isImageConfigured()) {
            Toast.makeText(this, "请先填写节点配置和生图模型", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }

        ChatMessage userMessage = new ChatMessage(ChatMessage.ROLE_USER, prompt);
        userMessage.mode = ChatMessage.MODE_IMAGE;
        userMessage.imageUri = pendingImageUri;
        userMessage.imageMime = pendingImageMime;
        userMessage.imageName = pendingImageName;
        messages.add(userMessage);
        ChatMessage responseMessage = new ChatMessage(ChatMessage.ROLE_ASSISTANT, "");
        responseMessage.mode = ChatMessage.MODE_IMAGE;
        responseMessage.imagePrompt = prompt;
        messages.add(responseMessage);
        saveConversation();
        messageInput.setText("");
        clearPendingImage();
        hideKeyboard();
        beginImageRequest(settings, prompt, responseMessage, userMessage);
    }

    private void beginChatRequest(
            AppSettings settings,
            List<ChatMessage> requestMessages,
            ChatMessage responseMessage,
            String userText) {
        speechController.stop();
        activeMessage = responseMessage;
        activeMode = ChatMessage.MODE_CHAT;
        activeMessage.content = "";
        activeMessage.error = false;
        activeMessage.retryable = false;
        activeMessage.meta = "";
        pendingToolCalls.clear();
        setBusy(true);
        refreshMessages();

        int generation = ++requestGeneration;
        requestStartedAtMs = SystemClock.elapsedRealtime();
        activeHandle = client.stream(networkExecutor, settings, requestMessages, new OpenAiClient.Listener() {
            @Override
            public void onDelta(String delta) {
                runOnUiThread(() -> {
                    if (!busy || generation != requestGeneration || activeMessage == null) {
                        return;
                    }
                    boolean firstDelta = activeMessage.content.isEmpty();
                    activeMessage.content += delta;
                    if (firstDelta) {
                        updateAvatarState();
                    }
                    refreshMessages();
                });
            }

            @Override
            public void onToolCall(OpenAiClient.ToolCall call) {
                runOnUiThread(() -> {
                    if (busy && generation == requestGeneration && pendingToolCalls.size() < 3) {
                        pendingToolCalls.add(call);
                    }
                });
            }

            @Override
            public void onComplete() {
                runOnUiThread(() -> {
                    if (!busy || generation != requestGeneration) {
                        return;
                    }
                    OpenAiClient.ToolCall imageCall = findImageToolCall();
                    if (activeMessage != null && imageCall != null) {
                        String imagePrompt = readImagePrompt(imageCall);
                        if (imagePrompt.isEmpty()) {
                            activeMessage.content = "生图工具没有提供有效的图片描述。";
                            activeMessage.error = true;
                            activeMessage.retryable = true;
                            pendingToolCalls.clear();
                            activeMessage.meta = formatModelDuration(elapsedRequestMs());
                            finishRequest();
                            return;
                        } else if (!settings.isImageConfigured()) {
                            activeMessage.content = "请先在设置中选择生图模型。";
                            activeMessage.error = true;
                            activeMessage.retryable = true;
                            pendingToolCalls.clear();
                            activeMessage.meta = formatModelDuration(elapsedRequestMs());
                            finishRequest();
                            return;
                        } else {
                            pendingToolCalls.clear();
                            activeMessage.mode = ChatMessage.MODE_IMAGE;
                            activeMessage.imagePrompt = imagePrompt;
                            beginImageRequest(
                                    settings,
                                    imagePrompt,
                                    activeMessage,
                                    latestReferenceImage(requestMessages));
                            return;
                        }
                    }
                    if (activeMessage != null && !pendingToolCalls.isEmpty()) {
                        appendToolResults(activeMessage, userText);
                    } else if (activeMessage != null && activeMessage.content.trim().isEmpty()) {
                        activeMessage.content = "模型未返回文本或工具调用。";
                        activeMessage.error = true;
                        activeMessage.retryable = true;
                    }
                    if (activeMessage != null) {
                        activeMessage.meta = formatModelDuration(elapsedRequestMs());
                    }
                    finishRequest();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    if (!busy || generation != requestGeneration) {
                        return;
                    }
                    if (activeMessage != null) {
                        activeMessage.content = "请求失败：" + error;
                        activeMessage.error = true;
                        activeMessage.retryable = true;
                        activeMessage.meta = formatModelDuration(elapsedRequestMs());
                    }
                    finishRequest();
                });
            }
        });
    }

    private void beginImageRequest(
            AppSettings settings,
            String prompt,
            ChatMessage responseMessage,
            ChatMessage referenceImage) {
        speechController.stop();
        activeMessage = responseMessage;
        activeMode = ChatMessage.MODE_IMAGE;
        activeMessage.mode = ChatMessage.MODE_IMAGE;
        activeMessage.imagePrompt = prompt;
        activeMessage.content = "";
        activeMessage.error = false;
        activeMessage.retryable = false;
        activeMessage.generatedImage = false;
        activeMessage.imageUri = "";
        activeMessage.imageMime = "";
        activeMessage.imageName = "";
        activeMessage.meta = "";
        setBusy(true);
        refreshMessages();

        int generation = ++requestGeneration;
        requestStartedAtMs = SystemClock.elapsedRealtime();
        activeHandle = client.generateImage(
                networkExecutor,
                settings,
                prompt,
                referenceImage,
                new OpenAiClient.ImageListener() {
                    @Override
                    public void onSuccess(OpenAiClient.ImageResult image) {
                        runOnUiThread(() -> {
                            if (!busy || generation != requestGeneration || activeMessage == null) {
                                return;
                            }
                            activeMessage.content = "图片已生成";
                            activeMessage.imageUri = image.uri;
                            activeMessage.imageMime = image.mime;
                            activeMessage.imageName = image.name;
                            activeMessage.generatedImage = true;
                            activeMessage.meta = formatModelDuration(elapsedRequestMs());
                            finishRequest();
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            if (!busy || generation != requestGeneration || activeMessage == null) {
                                return;
                            }
                            activeMessage.content = "生图失败：" + message;
                            activeMessage.error = true;
                            activeMessage.retryable = true;
                            activeMessage.meta = formatModelDuration(elapsedRequestMs());
                            finishRequest();
                        });
                    }
                });
    }

    private OpenAiClient.ToolCall findImageToolCall() {
        for (OpenAiClient.ToolCall call : pendingToolCalls) {
            if ("generate_image".equals(call.name)) {
                return call;
            }
        }
        return null;
    }

    private String readImagePrompt(OpenAiClient.ToolCall call) {
        try {
            String prompt = new JSONObject(call.arguments).optString("prompt").trim();
            return prompt.length() <= 20_000 ? prompt : prompt.substring(0, 20_000);
        } catch (Exception ignored) {
            return "";
        }
    }

    private void retryMessage(int position) {
        if (busy || position <= 0 || position >= messages.size()) {
            return;
        }
        ChatMessage response = messages.get(position);
        if (!response.error || !response.retryable
                || !ChatMessage.ROLE_ASSISTANT.equals(response.role)) {
            return;
        }
        int userPosition = position - 1;
        while (userPosition >= 0
                && !ChatMessage.ROLE_USER.equals(messages.get(userPosition).role)) {
            userPosition--;
        }
        if (userPosition < 0) {
            return;
        }
        ChatMessage userMessage = messages.get(userPosition);
        AppSettings settings = AppSettings.load(this);
        if (ChatMessage.MODE_IMAGE.equals(response.mode)) {
            if (!settings.isImageConfigured()) {
                Toast.makeText(this, "请先配置生图模型", Toast.LENGTH_SHORT).show();
                return;
            }
            String prompt = response.imagePrompt.isEmpty()
                    ? userMessage.content
                    : response.imagePrompt;
            beginImageRequest(
                    settings,
                    prompt,
                    response,
                    userMessage.hasImage() ? userMessage : null);
        } else {
            if (!settings.isChatConfigured()) {
                Toast.makeText(this, "请先配置对话模型", Toast.LENGTH_SHORT).show();
                return;
            }
            List<ChatMessage> requestMessages = new ArrayList<>(messages.subList(0, position));
            beginChatRequest(settings, requestMessages, response, userMessage.content);
        }
    }

    private void stopGeneration() {
        if (!busy) {
            return;
        }
        requestGeneration++;
        if (activeHandle != null) {
            activeHandle.cancel();
        }
        if (activeMessage != null && activeMessage.content.trim().isEmpty()) {
            activeMessage.content = "已停止生成。";
        }
        if (activeMessage != null) {
            activeMessage.error = true;
            activeMessage.retryable = true;
            activeMessage.meta = "已停止 · " + formatDuration(elapsedRequestMs());
        }
        pendingToolCalls.clear();
        finishRequest();
    }

    private void finishRequest() {
        ChatMessage completedMessage = activeMessage;
        boolean shouldAutoSpeak = completedMessage != null
                && completedMessage.isSpeakable()
                && (forceSpeakNextReply || AppSettings.load(this).autoSpeak);
        forceSpeakNextReply = false;
        activeHandle = null;
        activeMessage = null;
        activeMode = ChatMessage.MODE_CHAT;
        pendingToolCalls.clear();
        setBusy(false);
        saveConversation();
        refreshMessages();
        updateNodeStatus();
        if (shouldAutoSpeak) {
            speechController.speak(completedMessage, AppSettings.load(this));
        }
    }

    private void clearConversation() {
        if (messages.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear_chat)
                .setMessage(R.string.confirm_clear_chat)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.clear_chat, (dialog, which) -> {
                    stopGeneration();
                    speechController.stop();
                    messages.clear();
                    saveConversation();
                    adapter.notifyDataSetChanged();
                    statusText.setText(R.string.welcome);
                })
                .show();
    }

    private void startNewConversation() {
        voiceController.cancel();
        stopGeneration();
        speechController.stop();
        saveConversation();
        conversationStore.createConversation();
        messages.clear();
        messageInput.setText("");
        clearPendingImage();
        adapter.notifyDataSetChanged();
        updateNodeStatus();
    }

    private void loadActiveConversation() {
        voiceController.cancel();
        stopGeneration();
        speechController.stop();
        messages.clear();
        messages.addAll(conversationStore.load());
        if (markInterruptedMessages()) {
            saveConversation();
        }
        messageInput.setText("");
        clearPendingImage();
        refreshMessages(false);
        updateNodeStatus();
    }

    private void setBusy(boolean value) {
        busy = value;
        if (value) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        messageInput.setEnabled(!value);
        attachmentButton.setEnabled(!value);
        chatButton.setEnabled(!value);
        imageButton.setEnabled(!value);
        sendButton.setText(value ? R.string.stop : R.string.send);
        sendButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getColor(value ? R.color.warning : R.color.accent)));
        if (value) {
            statusText.setText(ChatMessage.MODE_IMAGE.equals(activeMode)
                    ? "正在生成图片..."
                    : "模型正在识别并生成...");
        } else {
            statusText.setText("");
        }
        updateModeViews();
    }

    private void setSelectedMode(String mode) {
        if (busy) {
            return;
        }
        selectedMode = ChatMessage.MODE_IMAGE.equals(mode)
                ? ChatMessage.MODE_IMAGE
                : ChatMessage.MODE_CHAT;
        interactiveTextInput = false;
        if (ChatMessage.MODE_IMAGE.equals(selectedMode)) {
            voiceController.cancel();
        }
        updateModeViews();
        updateNodeStatus();
    }

    private void updateModeViews() {
        boolean imageMode = ChatMessage.MODE_IMAGE.equals(selectedMode);
        boolean interactive = avatarEnabled && !imageMode;
        boolean immersiveVoice = interactive && !interactiveTextInput;
        chatButton.setTextColor(getColor(imageMode ? R.color.text_secondary : R.color.text_primary));
        imageButton.setTextColor(getColor(imageMode ? R.color.text_primary : R.color.text_secondary));
        chatButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getColor(imageMode ? R.color.surface : R.color.accent_dark)));
        imageButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getColor(imageMode ? R.color.accent_dark : R.color.surface)));
        avatarStage.setVisibility(interactive ? View.VISIBLE : View.GONE);
        messageList.setVisibility(interactive ? View.GONE : View.VISIBLE);
        statusText.setVisibility(immersiveVoice ? View.GONE : View.VISIBLE);
        composerRow.setVisibility(immersiveVoice ? View.GONE : View.VISIBLE);
        attachmentBar.setVisibility(!immersiveVoice && !pendingImageUri.isEmpty()
                ? View.VISIBLE : View.GONE);
        voiceControls.setVisibility(immersiveVoice ? View.VISIBLE : View.GONE);
        voiceModeButton.setVisibility(interactiveTextInput ? View.VISIBLE : View.GONE);
        attachmentButton.setVisibility(interactiveTextInput ? View.GONE : View.VISIBLE);
        audioActionButton.setImageResource(busy || speechController.isActive()
                ? R.drawable.ic_stop
                : R.drawable.ic_sound_on);
        AppSettings settings = AppSettings.load(this);
        messageInput.setHint(imageMode ? "描述你想生成的图片…" : "输入消息…");
        activeModelText.setText((imageMode ? "生图 · " : "对话 · ")
                + (imageMode ? settings.imageModel : settings.chatModel));
        updateAvatarDialog();
    }

    private void updateAvatarDialog() {
        if (!avatarEnabled || ChatMessage.MODE_IMAGE.equals(selectedMode)) {
            avatarDialog.setVisibility(View.GONE);
            return;
        }
        ChatMessage latest = null;
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage candidate = messages.get(index);
            if (!candidate.content.trim().isEmpty()) {
                latest = candidate;
                break;
            }
        }
        avatarDialog.setVisibility(View.VISIBLE);
        if (latest == null) {
            avatarDialogRole.setText(avatarDisplayName);
            avatarDialogText.setText("你好，今天想聊些什么？");
        } else {
            avatarDialogRole.setText(ChatMessage.ROLE_USER.equals(latest.role)
                    ? "你" : avatarDisplayName);
            avatarDialogText.setText(latest.content);
        }
    }

    private void appendToolResults(ChatMessage message, String userText) {
        StringBuilder results = new StringBuilder();
        boolean anySuccess = false;
        for (OpenAiClient.ToolCall call : pendingToolCalls) {
            if ("generate_image".equals(call.name)) {
                continue;
            }
            DeviceAction action = actionExecutor.fromToolCall(call);
            DeviceActionExecutor.Result result = DeviceActionPolicy.isExplicitlyAuthorized(action, userText)
                    ? actionExecutor.execute(action)
                    : new DeviceActionExecutor.Result(
                            false,
                            "未执行：没有检测到明确的本机操作请求。");
            if (results.length() > 0) {
                results.append('\n');
            }
            results.append(result.message);
            anySuccess |= result.success;
        }
        if (!message.content.trim().isEmpty()) {
            message.content += "\n\n";
        }
        message.content += results;
        message.error = !anySuccess;
    }

    private long elapsedRequestMs() {
        return requestStartedAtMs <= 0L
                ? 0L
                : Math.max(0L, SystemClock.elapsedRealtime() - requestStartedAtMs);
    }

    private String formatModelDuration(long milliseconds) {
        return "耗时 " + formatDuration(milliseconds);
    }

    private String formatLocalDuration(long milliseconds) {
        return "本地执行 · " + (milliseconds < 1000L
                ? milliseconds + " 毫秒"
                : formatDuration(milliseconds));
    }

    private String formatDuration(long milliseconds) {
        if (milliseconds < 1000L) {
            return milliseconds + " 毫秒";
        }
        return String.format(java.util.Locale.CHINA, "%.1f 秒", milliseconds / 1000f);
    }

    private void refreshMessages() {
        refreshMessages(true);
    }

    private void refreshMessages(boolean scrollToBottom) {
        adapter.notifyDataSetChanged();
        updateAvatarDialog();
        if (scrollToBottom && !messages.isEmpty()) {
            messageList.post(() -> messageList.setSelection(messages.size() - 1));
        }
    }

    private void updateNodeStatus() {
        if (busy) {
            return;
        }
        AppSettings settings = AppSettings.load(this);
        activeModelText.setText((ChatMessage.MODE_IMAGE.equals(selectedMode) ? "生图 · " : "对话 · ")
                + (ChatMessage.MODE_IMAGE.equals(selectedMode) ? settings.imageModel : settings.chatModel));
        if (!settings.hasChatCredentials() && !settings.hasImageCredentials()) {
            statusText.setText("尚未配置模型节点");
            return;
        }
        String chatHost = displayHost(settings.chatBaseUrl);
        if (settings.useSameService) {
            statusText.setText(getString(
                    R.string.node_status_models,
                    chatHost,
                    settings.chatModel,
                    settings.imageModel));
            return;
        }
        statusText.setText(getString(
                R.string.node_status_separate,
                chatHost,
                settings.chatModel,
                displayHost(settings.imageBaseUrl),
                settings.imageModel));
    }

    private String displayHost(String baseUrl) {
        String host = baseUrl;
        try {
            String parsedHost = URI.create(baseUrl).getHost();
            if (parsedHost != null) {
                host = parsedHost;
            }
        } catch (Exception ignored) {
            // The settings screen validates the URL before saving.
        }
        return host;
    }

    private void restoreState(Bundle savedInstanceState) {
        boolean hadPersistentHistory = conversationStore.exists();
        if (savedInstanceState != null) {
            try {
                JSONArray saved = new JSONArray(savedInstanceState.getString(STATE_MESSAGES, "[]"));
                for (int index = 0; index < saved.length(); index++) {
                    if (saved.optJSONObject(index) != null) {
                        messages.add(ChatMessage.fromJson(saved.optJSONObject(index)));
                    }
                }
            } catch (Exception ignored) {
                messages.clear();
            }
        } else {
            messages.addAll(conversationStore.load());
        }

        boolean changed = markInterruptedMessages();
        if (!hadPersistentHistory && messages.isEmpty()) {
            ArrayList<ChatMessage> recovered = conversationStore.recoverGeneratedImages(messages);
            messages.addAll(recovered);
            changed = !recovered.isEmpty();
        }
        if (changed || !hadPersistentHistory) {
            saveConversation();
        }
        adapter.notifyDataSetChanged();

        if (savedInstanceState != null) {
            messageInput.setText(savedInstanceState.getString(STATE_DRAFT, ""));
            pendingImageUri = savedInstanceState.getString(STATE_IMAGE_URI, "");
            pendingImageMime = savedInstanceState.getString(STATE_IMAGE_MIME, "");
            pendingImageName = savedInstanceState.getString(STATE_IMAGE_NAME, "");
            pendingDownloadUri = savedInstanceState.getString(STATE_DOWNLOAD_URI, "");
            pendingDownloadMime = savedInstanceState.getString(STATE_DOWNLOAD_MIME, "image/png");
            pendingDownloadName = savedInstanceState.getString(
                    STATE_DOWNLOAD_NAME,
                    "ning-image.png");
        }
        showPendingImage();
    }

    private boolean markInterruptedMessages() {
        boolean changed = false;
        for (ChatMessage message : messages) {
            if (ChatMessage.ROLE_ASSISTANT.equals(message.role)
                    && message.content.trim().isEmpty()
                    && !message.hasGeneratedImage()) {
                message.content = getString(R.string.previous_request_interrupted);
                message.error = true;
                message.retryable = true;
                changed = true;
            }
        }
        return changed;
    }

    private void confirmDeleteMessage(ChatMessage message) {
        if (busy) {
            Toast.makeText(this, R.string.stop_before_delete, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_message)
                .setMessage(message.hasGeneratedImage()
                        ? R.string.confirm_delete_generated_message
                        : R.string.confirm_delete_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete_message, (dialog, which) -> {
                    if (speechController.isActive(message)) {
                        speechController.stop();
                    }
                    if (messages.remove(message)) {
                        saveConversation();
                        refreshMessages();
                    }
                })
                .show();
    }

    private void saveConversation() {
        conversationStore.save(messages);
    }

    private void hideKeyboard() {
        InputMethodManager manager = getSystemService(InputMethodManager.class);
        if (manager != null) {
            manager.hideSoftInputFromWindow(messageInput.getWindowToken(), 0);
        }
    }

    private void pickImage() {
        if (busy) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_PICK_IMAGE);
    }

    private ImageInfo readImageInfo(Uri uri) {
        String name = "image";
        long size = -1;
        String mime = getContentResolver().getType(uri);
        try (Cursor cursor = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null,
                null,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                    name = cursor.getString(nameColumn);
                }
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    size = cursor.getLong(sizeColumn);
                }
            }
        } catch (Exception ignored) {
            // The request path enforces the same size limit while reading.
        }
        mime = mime == null ? "" : mime.toLowerCase(java.util.Locale.ROOT);
        return new ImageInfo(name, mime, size);
    }

    private boolean isSupportedReferenceImage(String mime) {
        return "image/jpeg".equals(mime)
                || "image/png".equals(mime)
                || "image/webp".equals(mime);
    }

    private void showPendingImage() {
        if (pendingImageUri.isEmpty()) {
            attachmentBar.setVisibility(View.GONE);
            attachmentPreview.setImageDrawable(null);
            attachmentText.setText("");
            return;
        }
        attachmentBar.setVisibility(View.VISIBLE);
        attachmentPreview.setImageURI(Uri.parse(pendingImageUri));
        attachmentText.setText(getString(R.string.image_attached, pendingImageName));
    }

    private void clearPendingImage() {
        pendingImageUri = "";
        pendingImageMime = "";
        pendingImageName = "";
        showPendingImage();
    }

    private ChatMessage latestReferenceImage(List<ChatMessage> requestMessages) {
        for (int index = requestMessages.size() - 1; index >= 0; index--) {
            ChatMessage message = requestMessages.get(index);
            if (ChatMessage.ROLE_USER.equals(message.role)) {
                return message.hasImage() ? message : null;
            }
        }
        return null;
    }

    private void openImage(ChatMessage message) {
        if (!message.hasGeneratedImage()) {
            return;
        }
        Intent intent = new Intent(this, ImagePreviewActivity.class);
        intent.putExtra(ImagePreviewActivity.EXTRA_URI, message.imageUri);
        intent.putExtra(ImagePreviewActivity.EXTRA_MIME, message.imageMime);
        intent.putExtra(ImagePreviewActivity.EXTRA_NAME, message.imageName);
        JSONArray gallery = new JSONArray();
        int selectedIndex = 0;
        for (ChatMessage candidate : messages) {
            if (!candidate.hasGeneratedImage()) {
                continue;
            }
            if (candidate == message) {
                selectedIndex = gallery.length();
            }
            try {
                gallery.put(new JSONObject()
                        .put("uri", candidate.imageUri)
                        .put("mime", candidate.imageMime)
                        .put("name", candidate.imageName));
            } catch (Exception ignored) {
                // Skip malformed gallery entries without blocking the selected image.
            }
        }
        intent.putExtra(ImagePreviewActivity.EXTRA_GALLERY, gallery.toString());
        intent.putExtra(ImagePreviewActivity.EXTRA_INDEX, selectedIndex);
        startActivity(intent);
    }

    private void requestImageDownload(ChatMessage message) {
        if (!message.hasGeneratedImage()) {
            return;
        }
        pendingDownloadUri = message.imageUri;
        pendingDownloadMime = message.imageMime;
        pendingDownloadName = message.imageName;
        startActivityForResult(
                ImageFileUtils.createSaveIntent(pendingDownloadName, pendingDownloadMime),
                REQUEST_SAVE_IMAGE);
    }

    private void saveImageTo(Uri destination) {
        String source = pendingDownloadUri;
        if (source.isEmpty()) {
            return;
        }
        fileExecutor.execute(() -> {
            try {
                ImageFileUtils.copy(this, source, destination);
                runOnUiThread(() -> {
                    pendingDownloadUri = "";
                    Toast.makeText(this, R.string.image_saved, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception exception) {
                runOnUiThread(() -> Toast.makeText(
                        this,
                        getString(R.string.image_save_failed, exception.getMessage()),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private static final class ImageInfo {
        final String name;
        final String mime;
        final long size;

        ImageInfo(String name, String mime, long size) {
            this.name = name;
            this.mime = mime;
            this.size = size;
        }
    }
}
