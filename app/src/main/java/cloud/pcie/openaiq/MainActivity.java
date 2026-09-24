package cloud.pcie.openaiq;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.LinearLayout;
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
    private static final int REQUEST_LOCATION = 45;
    private static final long MAX_IMAGE_BYTES = 10L * 1024L * 1024L;
    private static final long LOCATION_CACHE_MAX_AGE_MS = 30L * 60L * 1000L;
    private static final long LOCATION_TIMEOUT_MS = 12_000L;
    private static final String MODE_WEATHER = "weather";

    private final ArrayList<ChatMessage> messages = new ArrayList<>();
    private final ArrayList<OpenAiClient.ToolCall> pendingToolCalls = new ArrayList<>();
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private OpenAiClient client;
    private WeatherClient weatherClient;
    private DeviceActionExecutor actionExecutor;
    private ConversationStore conversationStore;
    private SpeechController speechController;
    private VoiceConversationController voiceController;

    private MessageAdapter adapter;
    private ListView messageList;
    private EditText messageInput;
    private ImageButton attachmentButton;
    private Button chatButton;
    private Button imageButton;
    private ImageButton sendButton;
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
    private CyberEffectsView micEffects;
    private CyberEffectsView assistantPanelEffects;
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
    private View chatViewSwitcher;
    private Button characterViewButton;
    private Button conversationViewButton;
    private ImageWorkbenchView imageWorkbench;
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
    private ChatMessage speechDisplayMessage;
    private String speechDisplayText = "";
    private boolean showAvatarWelcomeOnLaunch;
    private LocationManager locationManager;
    private LocationListener activeLocationListener;
    private Runnable locationTimeout;
    private Location fallbackLocation;
    private int pendingLocationWeatherDays = 3;
    private int pendingLocationWeatherGeneration = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        client = new OpenAiClient(getApplicationContext());
        weatherClient = new WeatherClient(getApplicationContext());
        showAvatarWelcomeOnLaunch = savedInstanceState == null;
        actionExecutor = new DeviceActionExecutor(this);
        conversationStore = new ConversationStore(this);
        live2dAvatar = findViewById(R.id.live2dAvatar);
        avatarStage = findViewById(R.id.avatarStage);
        avatarStatusText = findViewById(R.id.avatarStatusText);
        voiceStatusText = findViewById(R.id.voiceStatusText);
        voiceButton = findViewById(R.id.voiceButton);
        micEffects = findViewById(R.id.micEffects);
        assistantPanelEffects = findViewById(R.id.assistantPanelEffects);
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
                if (state == SpeechController.State.IDLE) {
                    speechDisplayMessage = null;
                    speechDisplayText = "";
                }
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
            public void onPlaybackText(ChatMessage message, String text) {
                speechDisplayMessage = text == null || text.isEmpty() ? null : message;
                speechDisplayText = text == null ? "" : text;
                updateAvatarDialog();
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
        createWorkspaceViews();
        applyReferenceLayoutMetrics();
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
        imageWorkbench.restoreState(savedInstanceState);
        interactiveTextInput = savedInstanceState != null && savedInstanceState.getBoolean("chat_list_view");
        imageWorkbench.updateGallery(conversationStore.generatedImages());
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
        setSelectedMode(savedInstanceState == null ? ChatMessage.MODE_CHAT
                : savedInstanceState.getString("selected_workspace", ChatMessage.MODE_CHAT));
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
        outState.putBoolean("chat_list_view", interactiveTextInput);
        outState.putString("selected_workspace", selectedMode);
        imageWorkbench.saveState(outState);
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
        cancelLocationLookup();
        if (activeHandle != null) {
            activeHandle.cancel();
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        networkExecutor.shutdownNow();
        fileExecutor.shutdownNow();
        voiceController.release();
        speechController.release();
        live2dAvatar.destroy();
        imageWorkbench.release();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION) {
            int generation = pendingLocationWeatherGeneration;
            int days = pendingLocationWeatherDays;
            pendingLocationWeatherGeneration = -1;
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && busy
                    && generation == requestGeneration) {
                startLocationLookup(days, generation);
            } else if (busy && generation == requestGeneration) {
                failWeatherRequest(
                        "未获得定位权限。请允许近似位置权限，或直接告诉我要查询的城市。");
            }
            return;
        }
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
        setChatView(true);
        messageInput.requestFocus();
        InputMethodManager manager = getSystemService(InputMethodManager.class);
        if (manager != null) {
            manager.showSoftInput(messageInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void returnToVoiceMode() {
        setChatView(false);
    }

    private void setChatView(boolean conversation) {
        interactiveTextInput = conversation;
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
        micEffects.setVoiceState(state == VoiceConversationController.State.LISTENING,
                state == VoiceConversationController.State.PROCESSING);
        assistantPanelEffects.setVoiceState(state == VoiceConversationController.State.LISTENING,
                state == VoiceConversationController.State.PROCESSING);
        voiceButton.setContentDescription(active
                ? getString(R.string.stop_voice_chat)
                : getString(R.string.start_voice_chat));
        voiceButton.setImageTintList(android.content.res.ColorStateList.valueOf(
                getColor(R.color.text_primary)));
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
            showAvatarWelcomeOnLaunch = false;
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
        showAvatarWelcomeOnLaunch = false;
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
        if (busy) return;
        String description = imageWorkbench.description();
        if (description.isEmpty()) {
            Toast.makeText(this, "请输入生图描述", Toast.LENGTH_SHORT).show();
            return;
        }

        AppSettings settings = AppSettings.load(this);
        if (!settings.isImageConfigured()) {
            Toast.makeText(this, "请先填写节点配置和生图模型", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }

        String prompt = imageWorkbench.generationPrompt();
        ImageGenerationOptions options = imageWorkbench.options();
        ChatMessage userMessage = new ChatMessage(ChatMessage.ROLE_USER, description);
        userMessage.mode = ChatMessage.MODE_IMAGE;
        userMessage.imageUri = pendingImageUri;
        userMessage.imageMime = pendingImageMime;
        userMessage.imageName = pendingImageName;
        messages.add(userMessage);
        ChatMessage responseMessage = new ChatMessage(ChatMessage.ROLE_ASSISTANT, "");
        responseMessage.mode = ChatMessage.MODE_IMAGE;
        responseMessage.imagePrompt = prompt;
        responseMessage.imageSize = options.size;
        responseMessage.imageQuality = options.quality;
        messages.add(responseMessage);
        saveConversation();
        clearPendingImage();
        hideKeyboard();
        beginImageRequest(settings, prompt, responseMessage, userMessage);
    }

    private void beginChatRequest(
            AppSettings settings,
            List<ChatMessage> requestMessages,
            ChatMessage responseMessage,
            String userText) {
        showAvatarWelcomeOnLaunch = false;
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

        boolean streamSpeech = forceSpeakNextReply || settings.autoSpeak;
        forceSpeakNextReply = false;
        if (streamSpeech) {
            speechController.startStreaming(responseMessage, settings);
        }

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
                    speechController.appendStreamingText(activeMessage, delta);
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
                    OpenAiClient.ToolCall weatherCall = findWeatherToolCall();
                    if (activeMessage != null && weatherCall != null) {
                        pendingToolCalls.clear();
                        beginWeatherRequest(weatherCall, generation);
                        return;
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
        boolean announceInAvatar = avatarEnabled
                && ChatMessage.MODE_CHAT.equals(selectedMode);
        speechController.stop();
        activeMessage = responseMessage;
        activeMode = ChatMessage.MODE_IMAGE;
        activeMessage.mode = ChatMessage.MODE_IMAGE;
        activeMessage.imagePrompt = prompt;
        activeMessage.content = announceInAvatar
                ? getString(R.string.avatar_image_generation_started)
                : "";
        activeMessage.error = false;
        activeMessage.retryable = false;
        activeMessage.generatedImage = false;
        activeMessage.imageUri = "";
        activeMessage.imageMime = "";
        activeMessage.imageName = "";
        activeMessage.meta = "";
        setBusy(true);
        refreshMessages();
        if (announceInAvatar) {
            speechController.speak(activeMessage, settings);
        }

        int generation = ++requestGeneration;
        requestStartedAtMs = SystemClock.elapsedRealtime();
        activeHandle = client.generateImage(
                networkExecutor,
                settings,
                prompt,
                referenceImage,
                new ImageGenerationOptions(responseMessage.imageSize, responseMessage.imageQuality),
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

    private OpenAiClient.ToolCall findWeatherToolCall() {
        for (OpenAiClient.ToolCall call : pendingToolCalls) {
            if ("get_weather".equals(call.name)) {
                return call;
            }
        }
        return null;
    }

    private void beginWeatherRequest(OpenAiClient.ToolCall call, int generation) {
        String location;
        int forecastDays;
        boolean useCurrentLocation;
        try {
            JSONObject arguments = new JSONObject(call.arguments);
            location = arguments.optString("location").trim();
            forecastDays = Math.max(1, Math.min(7, arguments.optInt("forecast_days", 3)));
            useCurrentLocation = arguments.optBoolean("use_current_location", false);
        } catch (Exception exception) {
            location = "";
            forecastDays = 3;
            useCurrentLocation = true;
        }
        activeHandle = null;
        activeMode = MODE_WEATHER;
        if (useCurrentLocation || location.isEmpty() || isCurrentLocationName(location)) {
            beginCurrentLocationWeather(forecastDays, generation);
            return;
        }
        String queryLocation = location;
        int queryDays = forecastDays;
        activeMessage.content = "正在查询" + queryLocation + "的天气…";
        refreshMessages();
        executeWeatherRequest(generation, () -> weatherClient.query(queryLocation, queryDays));
    }

    private boolean isCurrentLocationName(String location) {
        String normalized = location.replace(" ", "");
        return "当前位置".equals(normalized)
                || "当前城市".equals(normalized)
                || "这里".equals(normalized)
                || "本地".equals(normalized)
                || "我这里".equals(normalized);
    }

    private void beginCurrentLocationWeather(int days, int generation) {
        activeMessage.content = "正在获取当前位置…";
        refreshMessages();
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            pendingLocationWeatherDays = days;
            pendingLocationWeatherGeneration = generation;
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQUEST_LOCATION);
            return;
        }
        startLocationLookup(days, generation);
    }

    @SuppressLint("MissingPermission")
    private void startLocationLookup(int days, int generation) {
        cancelLocationLookup();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) {
            failWeatherRequest("设备没有可用的定位服务，请直接告诉我要查询的城市。");
            return;
        }
        List<String> providers = locationManager.getProviders(true);
        Location best = null;
        for (String provider : providers) {
            try {
                Location candidate = locationManager.getLastKnownLocation(provider);
                if (candidate != null && (best == null || candidate.getTime() > best.getTime())) {
                    best = candidate;
                }
            } catch (SecurityException ignored) {
                // Some providers require precise location; city weather only requests coarse access.
            }
        }
        if (best != null
                && Math.abs(System.currentTimeMillis() - best.getTime()) <= LOCATION_CACHE_MAX_AGE_MS) {
            queryWeatherAtLocation(best, days, generation);
            return;
        }
        fallbackLocation = best;
        activeMessage.content = "正在定位并查询天气…";
        refreshMessages();
        activeLocationListener = location -> {
            cancelLocationLookup();
            queryWeatherAtLocation(location, days, generation);
        };
        boolean requested = false;
        for (String provider : providers) {
            if (LocationManager.GPS_PROVIDER.equals(provider)
                    && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                continue;
            }
            try {
                locationManager.requestLocationUpdates(
                        provider,
                        0L,
                        0f,
                        activeLocationListener,
                        Looper.getMainLooper());
                requested = true;
            } catch (Exception ignored) {
                // Try the next enabled provider.
            }
        }
        if (!requested) {
            if (fallbackLocation != null) {
                Location fallback = fallbackLocation;
                cancelLocationLookup();
                queryWeatherAtLocation(fallback, days, generation);
            } else {
                failWeatherRequest("定位服务不可用，请开启系统定位，或直接告诉我要查询的城市。");
            }
            return;
        }
        locationTimeout = () -> {
            Location fallback = fallbackLocation;
            cancelLocationLookup();
            if (!busy || generation != requestGeneration) {
                return;
            }
            if (fallback != null) {
                queryWeatherAtLocation(fallback, days, generation);
            } else {
                failWeatherRequest("暂时无法获取当前位置，请稍后重试或直接告诉我要查询的城市。");
            }
        };
        mainHandler.postDelayed(locationTimeout, LOCATION_TIMEOUT_MS);
    }

    private void queryWeatherAtLocation(Location location, int days, int generation) {
        if (!busy || generation != requestGeneration || activeMessage == null) {
            return;
        }
        double latitude = location.getLatitude();
        double longitude = location.getLongitude();
        activeMessage.content = "已获取当前位置，正在查询天气…";
        refreshMessages();
        executeWeatherRequest(
                generation,
                () -> weatherClient.query(latitude, longitude, days));
    }

    private void executeWeatherRequest(int generation, WeatherQuery query) {
        networkExecutor.execute(() -> {
            try {
                String result = query.execute();
                runOnUiThread(() -> {
                    if (!busy || generation != requestGeneration || activeMessage == null) {
                        return;
                    }
                    activeMessage.content = result;
                    activeMessage.error = false;
                    activeMessage.retryable = false;
                    activeMessage.meta = formatModelDuration(elapsedRequestMs());
                    finishRequest();
                });
            } catch (Exception exception) {
                String message = exception.getMessage() == null
                        ? "无法连接天气服务"
                        : exception.getMessage();
                runOnUiThread(() -> {
                    if (!busy || generation != requestGeneration || activeMessage == null) {
                        return;
                    }
                    activeMessage.content = "天气查询失败：" + message;
                    activeMessage.error = true;
                    activeMessage.retryable = true;
                    activeMessage.meta = formatModelDuration(elapsedRequestMs());
                    finishRequest();
                });
            }
        });
    }

    private void failWeatherRequest(String message) {
        cancelLocationLookup();
        if (activeMessage == null) {
            return;
        }
        activeMessage.content = "天气查询失败：" + message;
        activeMessage.error = true;
        activeMessage.retryable = true;
        activeMessage.meta = formatModelDuration(elapsedRequestMs());
        finishRequest();
    }

    private void cancelLocationLookup() {
        if (locationTimeout != null) {
            mainHandler.removeCallbacks(locationTimeout);
            locationTimeout = null;
        }
        if (locationManager != null && activeLocationListener != null) {
            try {
                locationManager.removeUpdates(activeLocationListener);
            } catch (SecurityException ignored) {
                // Permission may have been revoked while the request was active.
            }
        }
        activeLocationListener = null;
        fallbackLocation = null;
    }

    private interface WeatherQuery {
        String execute() throws Exception;
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
        cancelLocationLookup();
        pendingLocationWeatherGeneration = -1;
        if (activeHandle != null) {
            activeHandle.cancel();
        }
        if (activeMessage != null && activeMessage.content.trim().isEmpty()) {
            activeMessage.content = "已停止生成。";
        }
        if (activeMessage != null && ChatMessage.MODE_IMAGE.equals(activeMode)) {
            activeMessage.content = "已停止生成图片。";
            speechController.stop();
        } else if (activeMessage != null && MODE_WEATHER.equals(activeMode)) {
            activeMessage.content = "已停止天气查询。";
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
        cancelLocationLookup();
        ChatMessage completedMessage = activeMessage;
        boolean streamingSpeech = speechController.isStreaming(completedMessage);
        if (streamingSpeech) {
            if (completedMessage.isSpeakable()) {
                speechController.finishStreaming(completedMessage, completedMessage.content);
            } else {
                speechController.stop();
            }
        }
        forceSpeakNextReply = false;
        activeHandle = null;
        activeMessage = null;
        activeMode = ChatMessage.MODE_CHAT;
        pendingToolCalls.clear();
        setBusy(false);
        saveConversation();
        refreshMessages();
        updateNodeStatus();
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
                    showAvatarWelcomeOnLaunch = true;
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
        showAvatarWelcomeOnLaunch = true;
        imageWorkbench.clearDraft();
        if (messages.isEmpty()) {
            messageInput.setText("");
            clearPendingImage();
            updateNodeStatus();
            return;
        }
        saveConversation();
        conversationStore.createConversation();
        messages.clear();
        messageInput.setText("");
        clearPendingImage();
        adapter.notifyDataSetChanged();
        updateModeViews();
        updateNodeStatus();
    }

    private void loadActiveConversation() {
        voiceController.cancel();
        stopGeneration();
        speechController.stop();
        showAvatarWelcomeOnLaunch = false;
        messages.clear();
        messages.addAll(conversationStore.load());
        if (markInterruptedMessages()) {
            saveConversation();
        }
        messageInput.setText("");
        clearPendingImage();
        imageWorkbench.updateGallery(conversationStore.generatedImages());
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
        chatButton.setEnabled(true);
        imageButton.setEnabled(true);
        sendButton.setContentDescription(getString(value ? R.string.stop : R.string.send));
        sendButton.setImageResource(value ? R.drawable.ic_stop : R.drawable.ic_send);
        sendButton.setImageTintList(android.content.res.ColorStateList.valueOf(
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
        hideKeyboard();
        selectedMode = ChatMessage.MODE_IMAGE.equals(mode)
                ? ChatMessage.MODE_IMAGE
                : ChatMessage.MODE_CHAT;
        if (ChatMessage.MODE_IMAGE.equals(selectedMode)) {
            voiceController.cancel();
        }
        updateModeViews();
        updateNodeStatus();
    }

    private void createWorkspaceViews() {
        LinearLayout root = (LinearLayout) avatarStage.getParent();
        LinearLayout switcher = new LinearLayout(this);
        switcher.setId(R.id.chatViewSwitcher);
        switcher.setGravity(android.view.Gravity.CENTER_VERTICAL);
        switcher.setPadding(sx(14), sx(4), sx(14), sx(4));
        TextView title = new TextView(this);
        title.setText("当前对话");
        title.setTextColor(0xFF85BEDB);
        setTextPx(title, 12);
        switcher.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
        title.setGravity(android.view.Gravity.CENTER_VERTICAL);
        characterViewButton = workspaceViewButton("人物", R.id.characterViewButton);
        conversationViewButton = workspaceViewButton("聊天", R.id.conversationViewButton);
        switcher.addView(characterViewButton, new LinearLayout.LayoutParams(sx(66), sx(30)));
        switcher.addView(conversationViewButton, new LinearLayout.LayoutParams(sx(66), sx(30)));
        characterViewButton.setOnClickListener(v -> setChatView(false));
        conversationViewButton.setOnClickListener(v -> setChatView(true));
        chatViewSwitcher = switcher;
        root.addView(switcher, root.indexOfChild(avatarStage), new LinearLayout.LayoutParams(-1, sx(38)));

        imageWorkbench = new ImageWorkbenchView(this, new ImageWorkbenchView.Actions() {
            public void generate() { if (busy) stopGeneration(); else sendImageMessage(); }
            public void pickReference() { pickImage(); }
            public void clearReference() { clearPendingImage(); }
            public void retry() {
                for (int i = messages.size() - 1; i >= 0; i--) {
                    ChatMessage message = messages.get(i);
                    if (ChatMessage.ROLE_ASSISTANT.equals(message.role) && ChatMessage.MODE_IMAGE.equals(message.mode)) {
                        retryMessage(i);
                        return;
                    }
                }
            }
            public void openImage(ChatMessage image, List<ChatMessage> gallery) { MainActivity.this.openImage(image, gallery); }
        });
        imageWorkbench.setVisibility(View.GONE);
        root.addView(imageWorkbench, root.indexOfChild(messageList), new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private Button workspaceViewButton(String title, int id) {
        Button button = new Button(this);
        button.setId(id);
        button.setText(title);
        button.setTextColor(0xFFB8E9FF);
        setTextPx(button, 13);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(0, 0, 0, 0);
        button.setBackground(new CyberPanelDrawable(this, CyberPanelDrawable.Kind.ACTION));
        return button;
    }

    private void updateWorkbenchStatus() {
        ChatMessage latest = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage candidate = messages.get(i);
            if (ChatMessage.ROLE_ASSISTANT.equals(candidate.role) && ChatMessage.MODE_IMAGE.equals(candidate.mode)) {
                latest = candidate;
                break;
            }
        }
        imageWorkbench.updateJob(busy, ChatMessage.MODE_IMAGE.equals(activeMode), latest);
    }

    private void applyReferenceLayoutMetrics() {
        View header = findViewById(R.id.headerRow);
        View tabs = findViewById(R.id.modeTabsRow);
        ImageView brandIcon = findViewById(R.id.brandIcon);
        ImageView wordmark = findViewById(R.id.brandWordmark);
        Button history = findViewById(R.id.historyButton);
        Button newConversation = findViewById(R.id.newConversationButton);

        // Dimensions recovered from NING 2.3.23; sx uses that phone's dp baseline.
        setDesignSize(header, -1, 72);
        header.setPadding(sx(14), 0, sx(10), 0);
        setDesignSize(brandIcon, 48, 48);
        setDesignSize(wordmark, 60, 20);
        setStartMargin(findViewById(R.id.titleSettingsButton), 8);

        // The old 68dp text-only controls need extra width for the added icons.
        setDesignSize(history, 88, 32);
        setDesignSize(newConversation, 80, 32);
        setStartMargin(newConversation, 5);
        styleHeaderButton(history);
        styleHeaderButton(newConversation);
        setTextPx(activeModelText, 11);

        setDesignSize(tabs, -1, 52);
        tabs.setPadding(sx(14), sx(7), sx(14), sx(7));
        setDesignSize(chatButton, -2, 38);
        setDesignSize(imageButton, -2, 38);
        setStartMargin(imageButton, -4);
        chatButton.setPadding(0, 0, 0, 0);
        imageButton.setPadding(0, 0, 0, 0);
        chatButton.setBackgroundResource(R.drawable.bg_tab_chat);
        imageButton.setBackgroundResource(R.drawable.bg_tab_image);
        setTextPx(chatButton, 14);
        setTextPx(imageButton, 14);

        messageList.setPadding(0, sx(8), 0, sx(8));
        setDesignSize(attachmentBar, -1, 58);
        setDesignSize(attachmentPreview, 42, 42);
        setDesignSize(findViewById(R.id.removeAttachmentButton), 58, 42);
        setTextPx(attachmentText, 13);

        composerRow.setMinimumHeight(sx(70));
        composerRow.setPadding(sx(12), sx(7), sx(12), sx(11));
        composerRow.setBackground(new CyberPanelDrawable(this, CyberPanelDrawable.Kind.DOCK));
        setDesignSize(attachmentButton, 48, 52);
        setDesignSize(voiceModeButton, 48, 52);
        setDesignSize(sendButton, 64, 52);
        setStartMargin(voiceModeButton, 4);
        setStartMargin(messageInput, 7);
        setStartMargin(sendButton, 7);
        attachmentButton.setBackground(new CyberPanelDrawable(this, CyberPanelDrawable.Kind.ACTION));
        voiceModeButton.setBackground(new CyberPanelDrawable(this, CyberPanelDrawable.Kind.ACTION));
        sendButton.setBackground(new CyberPanelDrawable(this, CyberPanelDrawable.Kind.SEND));
        attachmentButton.setPadding(sx(12), sx(14), sx(12), sx(14));
        attachmentButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        sendButton.setPadding(sx(18), sx(12), sx(18), sx(12));
        voiceModeButton.setPadding(sx(13), sx(13), sx(13), sx(13));
        ((ImageView) voiceModeButton).setScaleType(ImageView.ScaleType.FIT_CENTER);
        messageInput.setBackground(new CyberPanelDrawable(this, CyberPanelDrawable.Kind.INPUT));
        messageInput.setMinHeight(sx(52));
        messageInput.setMinimumHeight(sx(52));
        messageInput.setMaxLines(5);
        messageInput.setPadding(sx(12), sx(12), sx(12), sx(12));
        messageInput.setTextColor(0xFFE0F4FF);
        messageInput.setHintTextColor(0xFF689CBB);
        setTextPx(messageInput, 18);
        setTextPx(statusText, 11);
        statusText.setMinimumHeight(sx(22));
    }

    private void styleHeaderButton(Button button) {
        setTextPx(button, 11);
        button.setPadding(sx(6), 0, sx(6), 0);
        button.setCompoundDrawablePadding(sx(5));
        Drawable icon = button.getCompoundDrawablesRelative()[0];
        if (icon != null) {
            icon.setBounds(0, 0, sx(16), sx(16));
            button.setCompoundDrawablesRelative(icon, null, null, null);
        }
    }

    private void setTextPx(TextView view, int referenceSp) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, DesignScale.referenceSp(this, referenceSp));
    }

    private void setDesignSize(View view, int width, int height) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (width >= 0) {
            params.width = sx(width);
        } else if (width == -1) {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
        }
        if (height >= 0) {
            params.height = sx(height);
        }
        view.setLayoutParams(params);
    }

    private void setStartMargin(View view, int designPixels) {
        ViewGroup.LayoutParams rawParams = view.getLayoutParams();
        if (rawParams instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) rawParams;
            params.setMarginStart(designPixels < 0 ? -sx(-designPixels) : sx(designPixels));
            view.setLayoutParams(params);
        }
    }

    private int sx(float designPixels) {
        return DesignScale.referenceDp(this, designPixels);
    }

    private void updateModeViews() {
        boolean imageMode = ChatMessage.MODE_IMAGE.equals(selectedMode);
        boolean showAvatarStage = avatarEnabled && !imageMode && !interactiveTextInput;
        boolean immersiveVoice = showAvatarStage;
        chatButton.setTextColor(getColor(imageMode ? R.color.text_secondary : R.color.text_primary));
        imageButton.setTextColor(getColor(imageMode ? R.color.text_primary : R.color.text_secondary));
        chatButton.setSelected(!imageMode);
        imageButton.setSelected(imageMode);
        avatarStage.setVisibility(showAvatarStage ? View.VISIBLE : View.GONE);
        chatViewSwitcher.setVisibility(!imageMode && avatarEnabled ? View.VISIBLE : View.GONE);
        characterViewButton.setEnabled(avatarEnabled);
        characterViewButton.setSelected(showAvatarStage);
        conversationViewButton.setSelected(!showAvatarStage);
        imageWorkbench.setVisibility(imageMode ? View.VISIBLE : View.GONE);
        messageList.setVisibility(!imageMode && !showAvatarStage ? View.VISIBLE : View.GONE);
        boolean showStatus = !imageMode && !immersiveVoice
                && (busy || speechState != SpeechController.State.IDLE);
        statusText.setVisibility(showStatus ? View.VISIBLE : View.GONE);
        composerRow.setVisibility(imageMode || immersiveVoice ? View.GONE : View.VISIBLE);
        attachmentBar.setVisibility(!imageMode && !immersiveVoice && !pendingImageUri.isEmpty()
                ? View.VISIBLE : View.GONE);
        voiceControls.setVisibility(immersiveVoice ? View.VISIBLE : View.GONE);
        voiceModeButton.setVisibility(avatarEnabled && interactiveTextInput ? View.VISIBLE : View.GONE);
        attachmentButton.setVisibility(View.VISIBLE);
        audioActionButton.setImageResource(busy || speechController.isActive()
                ? R.drawable.ic_stop
                : R.drawable.ic_sound_on);
        AppSettings settings = AppSettings.load(this);
        messageInput.setHint(imageMode ? "描述你想生成的图片…" : "输入消息…");
        activeModelText.setText((imageMode ? "生图 · " : "对话 · ")
                + (imageMode ? settings.imageModel : settings.chatModel));
        updateAvatarDialog();
        updateWorkbenchStatus();
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
        if (speechDisplayMessage != null && !speechDisplayText.isEmpty()) {
            avatarDialogRole.setText(avatarDisplayName);
            avatarDialogText.setText(speechDisplayText);
        } else if (speechController.isStreaming(activeMessage)
                || (latest != null && speechController.isStreaming(latest))) {
            avatarDialogRole.setText(avatarDisplayName);
            avatarDialogText.setText("正在思考…");
        } else if (showAvatarWelcomeOnLaunch || latest == null) {
            avatarDialogRole.setText(avatarDisplayName);
            avatarDialogText.setText(R.string.avatar_welcome_message);
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
            if ("generate_image".equals(call.name) || "get_weather".equals(call.name)) {
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
        updateWorkbenchStatus();
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
        ArrayList<ChatMessage> turn = conversationTurnFor(message);
        if (turn.isEmpty()) {
            return;
        }
        boolean includesGeneratedImage = false;
        for (ChatMessage item : turn) {
            includesGeneratedImage |= item.hasGeneratedImage();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_message)
                .setMessage(includesGeneratedImage
                        ? R.string.confirm_delete_generated_message
                        : R.string.confirm_delete_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete_message, (dialog, which) -> {
                    for (ChatMessage item : turn) {
                        if (speechController.isActive(item)) {
                            speechController.stop();
                            break;
                        }
                    }
                    if (messages.removeAll(turn)) {
                        saveConversation();
                        refreshMessages();
                    }
                })
                .show();
    }

    private ArrayList<ChatMessage> conversationTurnFor(ChatMessage selected) {
        ArrayList<ChatMessage> turn = new ArrayList<>();
        int selectedIndex = messages.indexOf(selected);
        if (selectedIndex < 0) {
            return turn;
        }

        int start = selectedIndex;
        while (start > 0 && !ChatMessage.ROLE_USER.equals(messages.get(start).role)) {
            start--;
        }
        if (!ChatMessage.ROLE_USER.equals(messages.get(start).role)) {
            start = selectedIndex;
        }

        int end = start + 1;
        while (end < messages.size()
                && !ChatMessage.ROLE_USER.equals(messages.get(end).role)) {
            end++;
        }
        turn.addAll(messages.subList(start, end));
        return turn;
    }

    private void saveConversation() {
        conversationStore.save(messages);
        if (imageWorkbench != null) imageWorkbench.updateGallery(conversationStore.generatedImages());
    }

    private void hideKeyboard() {
        InputMethodManager manager = getSystemService(InputMethodManager.class);
        if (manager != null) {
            manager.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
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
        imageWorkbench.setReference(pendingImageUri, pendingImageName);
        if (pendingImageUri.isEmpty()) {
            attachmentBar.setVisibility(View.GONE);
            attachmentPreview.setImageDrawable(null);
            attachmentText.setText("");
            return;
        }
        attachmentBar.setVisibility(!ChatMessage.MODE_IMAGE.equals(selectedMode)
                && (!avatarEnabled || interactiveTextInput) ? View.VISIBLE : View.GONE);
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
        openImage(message, messages);
    }

    private void openImage(ChatMessage message, List<ChatMessage> images) {
        if (!message.hasGeneratedImage()) {
            return;
        }
        Intent intent = new Intent(this, ImagePreviewActivity.class);
        intent.putExtra(ImagePreviewActivity.EXTRA_URI, message.imageUri);
        intent.putExtra(ImagePreviewActivity.EXTRA_MIME, message.imageMime);
        intent.putExtra(ImagePreviewActivity.EXTRA_NAME, message.imageName);
        JSONArray gallery = new JSONArray();
        int selectedIndex = 0;
        for (ChatMessage candidate : images) {
            if (!candidate.hasGeneratedImage()) {
                continue;
            }
            if (candidate.imageUri.equals(message.imageUri)) {
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
