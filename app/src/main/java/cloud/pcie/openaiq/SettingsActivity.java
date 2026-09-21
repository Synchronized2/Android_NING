package cloud.pcie.openaiq;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SettingsActivity extends Activity {
    private static final int REQUEST_AVATAR = 51;
    private Switch sameServiceSwitch;
    private LinearLayout sharedConnectionContainer;
    private LinearLayout separateConnectionContainer;
    private EditText sharedBaseUrlInput;
    private EditText sharedApiKeyInput;
    private EditText chatBaseUrlInput;
    private EditText chatApiKeyInput;
    private EditText imageBaseUrlInput;
    private EditText imageApiKeyInput;
    private Spinner sharedChatModelSpinner;
    private Spinner sharedImageModelSpinner;
    private Spinner chatModelSpinner;
    private Spinner imageModelSpinner;
    private EditText systemPromptInput;
    private Button sharedFetchModelsButton;
    private Button chatFetchModelsButton;
    private Button imageFetchModelsButton;
    private TextView sharedModelsStatusText;
    private TextView chatModelsStatusText;
    private TextView imageModelsStatusText;
    private CheckBox autoSpeakCheck;
    private Spinner ttsVoiceSpinner;
    private Spinner ttsStyleSpinner;
    private CheckBox sharedShowKeyCheck;
    private CheckBox chatShowKeyCheck;
    private CheckBox imageShowKeyCheck;
    private Button ttsPreviewButton;
    private Button refreshTtsVoicesButton;
    private TextView ttsStatusText;
    private TextView ttsRateLabel;
    private TextView ttsVolumeLabel;
    private TextView ttsPitchLabel;
    private SeekBar ttsRateSeek;
    private SeekBar ttsVolumeSeek;
    private SeekBar ttsPitchSeek;
    private CheckBox avatarEnabledCheck;
    private TextView avatarSelectionText;
    private Button manageAvatarsButton;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private OpenAiClient client;
    private OpenAiClient.RequestHandle activeHandle;
    private SpeechController speechController;
    private EdgeTtsClient voiceCatalogClient;
    private EdgeTtsClient.Handle voiceCatalogHandle;
    private ChatMessage previewMessage;
    private List<EdgeTtsClient.Voice> curatedVoices = new ArrayList<>();
    private List<EdgeTtsClient.Style> ttsStyles = new ArrayList<>();
    private String selectedAvatarId = "hiyori";
    private boolean updatingKeyVisibility;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        client = new OpenAiClient(getApplicationContext());
        voiceCatalogClient = new EdgeTtsClient(getApplicationContext());

        sameServiceSwitch = findViewById(R.id.sameServiceSwitch);
        sharedConnectionContainer = findViewById(R.id.sharedConnectionContainer);
        separateConnectionContainer = findViewById(R.id.separateConnectionContainer);
        sharedBaseUrlInput = findViewById(R.id.sharedBaseUrlInput);
        sharedApiKeyInput = findViewById(R.id.sharedApiKeyInput);
        chatBaseUrlInput = findViewById(R.id.chatBaseUrlInput);
        chatApiKeyInput = findViewById(R.id.chatApiKeyInput);
        imageBaseUrlInput = findViewById(R.id.imageBaseUrlInput);
        imageApiKeyInput = findViewById(R.id.imageApiKeyInput);
        sharedChatModelSpinner = findViewById(R.id.sharedChatModelSpinner);
        sharedImageModelSpinner = findViewById(R.id.sharedImageModelSpinner);
        chatModelSpinner = findViewById(R.id.chatModelSpinner);
        imageModelSpinner = findViewById(R.id.imageModelSpinner);
        systemPromptInput = findViewById(R.id.systemPromptInput);
        sharedFetchModelsButton = findViewById(R.id.sharedFetchModelsButton);
        chatFetchModelsButton = findViewById(R.id.chatFetchModelsButton);
        imageFetchModelsButton = findViewById(R.id.imageFetchModelsButton);
        sharedModelsStatusText = findViewById(R.id.sharedModelsStatusText);
        chatModelsStatusText = findViewById(R.id.chatModelsStatusText);
        imageModelsStatusText = findViewById(R.id.imageModelsStatusText);
        autoSpeakCheck = findViewById(R.id.autoSpeakCheck);
        ttsVoiceSpinner = findViewById(R.id.ttsVoiceSpinner);
        ttsStyleSpinner = findViewById(R.id.ttsStyleSpinner);
        ttsPreviewButton = findViewById(R.id.ttsPreviewButton);
        refreshTtsVoicesButton = findViewById(R.id.refreshTtsVoicesButton);
        ttsStatusText = findViewById(R.id.ttsStatusText);
        ttsRateLabel = findViewById(R.id.ttsRateLabel);
        ttsVolumeLabel = findViewById(R.id.ttsVolumeLabel);
        ttsPitchLabel = findViewById(R.id.ttsPitchLabel);
        ttsRateSeek = findViewById(R.id.ttsRateSeek);
        ttsVolumeSeek = findViewById(R.id.ttsVolumeSeek);
        ttsPitchSeek = findViewById(R.id.ttsPitchSeek);
        sharedShowKeyCheck = findViewById(R.id.sharedShowKeyCheck);
        chatShowKeyCheck = findViewById(R.id.chatShowKeyCheck);
        imageShowKeyCheck = findViewById(R.id.imageShowKeyCheck);
        avatarEnabledCheck = findViewById(R.id.avatarEnabledCheck);
        avatarSelectionText = findViewById(R.id.avatarSelectionText);
        manageAvatarsButton = findViewById(R.id.manageAvatarsButton);

        speechController = new SpeechController(this, new SpeechController.Listener() {
            @Override
            public void onStateChanged(ChatMessage message, SpeechController.State state, int attempt) {
                if (state == SpeechController.State.PREPARING) {
                    ttsPreviewButton.setText(R.string.tts_preview_stop);
                    ttsStatusText.setText(getString(R.string.tts_preview_preparing, attempt));
                } else if (state == SpeechController.State.PLAYING) {
                    ttsPreviewButton.setText(R.string.tts_preview_stop);
                    ttsStatusText.setText(R.string.tts_preview_playing);
                } else {
                    ttsPreviewButton.setText(R.string.tts_preview);
                    ttsStatusText.setText("");
                }
            }

            @Override
            public void onFailure(ChatMessage message, String error) {
                ttsPreviewButton.setText(R.string.tts_preview);
                ttsStatusText.setText(getString(R.string.tts_preview_failed, error));
                ttsStatusText.setTextColor(getColor(R.color.warning));
            }
        });

        AppSettings settings = AppSettings.load(this);
        sharedBaseUrlInput.setText(settings.chatBaseUrl);
        sharedApiKeyInput.setText(settings.chatApiKey);
        chatBaseUrlInput.setText(settings.chatBaseUrl);
        chatApiKeyInput.setText(settings.chatApiKey);
        imageBaseUrlInput.setText(settings.imageBaseUrl);
        imageApiKeyInput.setText(settings.imageApiKey);
        systemPromptInput.setText(settings.systemPrompt);
        autoSpeakCheck.setChecked(settings.autoSpeak);
        avatarEnabledCheck.setChecked(settings.avatarEnabled);
        selectedAvatarId = settings.avatarId;
        ttsRateSeek.setProgress(settings.ttsRate + 100);
        ttsVolumeSeek.setProgress(settings.ttsVolume + 100);
        ttsPitchSeek.setProgress(settings.ttsPitch + 100);
        List<String> chatCatalog = AppSettings.loadChatModelCatalog(this);
        List<String> imageCatalog = AppSettings.loadImageModelCatalog(this);
        setSharedModelAdapters(chatCatalog, imageCatalog, settings.chatModel, settings.imageModel);
        setDedicatedModelAdapters(chatCatalog, imageCatalog, settings.chatModel, settings.imageModel);
        setVoiceAdapter(AppSettings.loadTtsVoiceCatalog(this), settings.ttsVoice);
        setStyleAdapter(settings.ttsStyle);
        updateAvatarSelection();
        updateTtsLabels();

        configureKeyReveal(sharedApiKeyInput, sharedShowKeyCheck);
        configureKeyReveal(chatApiKeyInput, chatShowKeyCheck);
        configureKeyReveal(imageApiKeyInput, imageShowKeyCheck);
        sameServiceSwitch.setChecked(settings.useSameService);
        updateConnectionMode(settings.useSameService, false);
        sameServiceSwitch.setOnCheckedChangeListener((button, checked) ->
                updateConnectionMode(checked, true));
        sharedFetchModelsButton.setOnClickListener(view -> fetchSharedModels());
        chatFetchModelsButton.setOnClickListener(view -> fetchDedicatedModels(true));
        imageFetchModelsButton.setOnClickListener(view -> fetchDedicatedModels(false));
        ttsPreviewButton.setOnClickListener(view -> toggleTtsPreview());
        refreshTtsVoicesButton.setOnClickListener(view -> refreshTtsVoices());
        manageAvatarsButton.setOnClickListener(view -> startActivityForResult(
                new Intent(this, AvatarLibraryActivity.class), REQUEST_AVATAR));
        SeekBar.OnSeekBarChangeListener seekListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateTtsLabels();
                if (fromUser && previewMessage != null && speechController.isActive(previewMessage)) {
                    speechController.stop();
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        };
        ttsRateSeek.setOnSeekBarChangeListener(seekListener);
        ttsVolumeSeek.setOnSeekBarChangeListener(seekListener);
        ttsPitchSeek.setOnSeekBarChangeListener(seekListener);
        findViewById(R.id.cancelButton).setOnClickListener(view -> finish());
        findViewById(R.id.saveButton).setOnClickListener(view -> save());
    }

    @Override
    protected void onPause() {
        setKeyVisible(sharedApiKeyInput, false);
        setKeyVisible(chatApiKeyInput, false);
        setKeyVisible(imageApiKeyInput, false);
        setShowKeyChecked(sharedShowKeyCheck, false);
        setShowKeyChecked(chatShowKeyCheck, false);
        setShowKeyChecked(imageShowKeyCheck, false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (activeHandle != null) {
            activeHandle.cancel();
        }
        if (speechController != null) {
            speechController.release();
        }
        if (voiceCatalogHandle != null) {
            voiceCatalogHandle.cancel();
        }
        if (voiceCatalogClient != null) {
            voiceCatalogClient.shutdown();
        }
        networkExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_AVATAR && resultCode == RESULT_OK) {
            selectedAvatarId = AppSettings.load(this).avatarId;
            updateAvatarSelection();
        }
    }

    private void setVoiceAdapter(List<EdgeTtsClient.Voice> voices, String selectedVoice) {
        curatedVoices = voices == null || voices.isEmpty()
                ? EdgeTtsClient.fallbackVoices()
                : new ArrayList<>(voices);
        ArrayAdapter<EdgeTtsClient.Voice> adapter = new ArrayAdapter<>(
                this, R.layout.item_voice_selected, curatedVoices);
        adapter.setDropDownViewResource(R.layout.item_voice_dropdown);
        ttsVoiceSpinner.setAdapter(adapter);
        int selectedIndex = 0;
        for (int index = 0; index < curatedVoices.size(); index++) {
            if (curatedVoices.get(index).shortName.equals(selectedVoice)) {
                selectedIndex = index;
                break;
            }
        }
        ttsVoiceSpinner.setSelection(selectedIndex, false);
        ttsVoiceSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(
                    android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                if (previewMessage != null && speechController.isActive(previewMessage)) {
                    speechController.stop();
                }
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
    }

    private void setStyleAdapter(String selectedStyle) {
        ttsStyles = EdgeTtsClient.styles();
        ArrayAdapter<EdgeTtsClient.Style> adapter = new ArrayAdapter<>(
                this, R.layout.item_voice_selected, ttsStyles);
        adapter.setDropDownViewResource(R.layout.item_voice_dropdown);
        ttsStyleSpinner.setAdapter(adapter);
        int selectedIndex = 0;
        for (int index = 0; index < ttsStyles.size(); index++) {
            if (ttsStyles.get(index).id.equals(selectedStyle)) {
                selectedIndex = index;
                break;
            }
        }
        ttsStyleSpinner.setSelection(selectedIndex, false);
        ttsStyleSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(
                    android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (previewMessage != null && speechController.isActive(previewMessage)) {
                    speechController.stop();
                }
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
    }

    private void refreshTtsVoices() {
        if (voiceCatalogHandle != null) {
            voiceCatalogHandle.cancel();
        }
        refreshTtsVoicesButton.setEnabled(false);
        ttsStatusText.setText("正在获取 Edge TTS 人声…");
        ttsStatusText.setTextColor(getColor(R.color.text_secondary));
        String selected = selectedVoice();
        voiceCatalogHandle = voiceCatalogClient.listVoices(new EdgeTtsClient.VoicesListener() {
            @Override
            public void onSuccess(List<EdgeTtsClient.Voice> voices) {
                runOnUiThread(() -> {
                    voiceCatalogHandle = null;
                    AppSettings.saveTtsVoiceCatalog(SettingsActivity.this, voices);
                    setVoiceAdapter(voices, selected);
                    refreshTtsVoicesButton.setEnabled(true);
                    ttsStatusText.setText(getString(R.string.tts_voices_loaded, voices.size()));
                    ttsStatusText.setTextColor(getColor(R.color.text_secondary));
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    voiceCatalogHandle = null;
                    refreshTtsVoicesButton.setEnabled(true);
                    ttsStatusText.setText("获取人声失败：" + message);
                    ttsStatusText.setTextColor(getColor(R.color.warning));
                });
            }
        });
    }

    private void updateAvatarSelection() {
        AvatarCatalog.Avatar avatar = AvatarCatalog.find(this, selectedAvatarId);
        avatarSelectionText.setText(avatar == null
                ? "未找到本地人物资源"
                : avatar.name + " · 本地运行 · WebGL");
    }

    private void toggleTtsPreview() {
        if (previewMessage != null && speechController.isActive(previewMessage)) {
            speechController.stop();
            return;
        }
        previewMessage = new ChatMessage(
                ChatMessage.ROLE_ASSISTANT,
                "你好，这是 NING 的 Edge TTS 音色试听。Welcome to NING.");
        speechController.speak(previewMessage, currentSettingsForTts());
    }

    private AppSettings currentSettingsForTts() {
        boolean sameService = sameServiceSwitch.isChecked();
        String chatBaseUrl = sameService ? valueOf(sharedBaseUrlInput) : valueOf(chatBaseUrlInput);
        String chatApiKey = sameService ? valueOf(sharedApiKeyInput) : valueOf(chatApiKeyInput);
        String imageBaseUrl = sameService ? chatBaseUrl : valueOf(imageBaseUrlInput);
        String imageApiKey = sameService ? chatApiKey : valueOf(imageApiKeyInput);
        return new AppSettings(
                sameService,
                chatBaseUrl,
                chatApiKey,
                imageBaseUrl,
                imageApiKey,
                selectedChatModel(),
                selectedImageModel(),
                valueOf(systemPromptInput),
                autoSpeakCheck.isChecked(),
                selectedVoice(),
                selectedStyle(),
                ttsRateSeek.getProgress() - 100,
                ttsVolumeSeek.getProgress() - 100,
                ttsPitchSeek.getProgress() - 100,
                avatarEnabledCheck.isChecked(),
                selectedAvatarId);
    }

    private void updateTtsLabels() {
        ttsRateLabel.setText(getString(R.string.tts_rate, ttsRateSeek.getProgress() - 100));
        ttsVolumeLabel.setText(getString(R.string.tts_volume, ttsVolumeSeek.getProgress() - 100));
        ttsPitchLabel.setText(getString(R.string.tts_pitch, ttsPitchSeek.getProgress() - 100));
    }

    private void fetchSharedModels() {
        String baseUrl = valueOf(sharedBaseUrlInput);
        String apiKey = valueOf(sharedApiKeyInput);
        if (!validateConnection(sharedBaseUrlInput, sharedApiKeyInput)) {
            return;
        }
        startModelFetch(baseUrl, apiKey, sharedModelsStatusText,
                models -> {
                    List<String> chatModels = new ArrayList<>();
                    List<String> imageModels = new ArrayList<>();
                    for (String model : models) {
                        (isImageModel(model) ? imageModels : chatModels).add(model);
                    }
                    String selectedChat = selectedModel(sharedChatModelSpinner);
                    String selectedImage = selectedModel(sharedImageModelSpinner);
                    AppSettings.saveModelCatalog(this, models);
                    AppSettings.saveChatModelCatalog(this, chatModels);
                    AppSettings.saveImageModelCatalog(this, imageModels);
                    setSharedModelAdapters(chatModels, imageModels, selectedChat, selectedImage);
                    setDedicatedModelAdapters(chatModels, imageModels, selectedChat, selectedImage);
                    sharedChatModelSpinner.performClick();
                });
    }

    private void fetchDedicatedModels(boolean chatService) {
        EditText urlInput = chatService ? chatBaseUrlInput : imageBaseUrlInput;
        EditText keyInput = chatService ? chatApiKeyInput : imageApiKeyInput;
        TextView status = chatService ? chatModelsStatusText : imageModelsStatusText;
        if (!validateConnection(urlInput, keyInput)) {
            return;
        }
        startModelFetch(valueOf(urlInput), valueOf(keyInput), status, models -> {
            if (chatService) {
                String selected = selectedModel(chatModelSpinner);
                AppSettings.saveChatModelCatalog(this, models);
                setStringSpinner(chatModelSpinner, models, selected);
                setStringSpinner(sharedChatModelSpinner, models, selected);
                chatModelSpinner.performClick();
            } else {
                String selected = selectedModel(imageModelSpinner);
                AppSettings.saveImageModelCatalog(this, models);
                setStringSpinner(imageModelSpinner, models, selected);
                setStringSpinner(sharedImageModelSpinner, models, selected);
                imageModelSpinner.performClick();
            }
        });
    }

    private void startModelFetch(
            String baseUrl,
            String apiKey,
            TextView status,
            java.util.function.Consumer<List<String>> onSuccess) {
        if (activeHandle != null) {
            activeHandle.cancel();
        }
        setFetchButtonsEnabled(false);
        status.setText(R.string.loading_models);
        activeHandle = client.listModels(networkExecutor, baseUrl, apiKey,
                new OpenAiClient.ModelsListener() {
                    @Override
                    public void onSuccess(List<String> models) {
                        runOnUiThread(() -> {
                            activeHandle = null;
                            onSuccess.accept(models);
                            setFetchButtonsEnabled(true);
                            status.setText(getString(R.string.models_loaded, models.size()));
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            activeHandle = null;
                            setFetchButtonsEnabled(true);
                            status.setText(getString(R.string.models_load_failed, message));
                        });
                    }
                });
    }

    private void setFetchButtonsEnabled(boolean enabled) {
        sharedFetchModelsButton.setEnabled(enabled);
        chatFetchModelsButton.setEnabled(enabled);
        imageFetchModelsButton.setEnabled(enabled);
    }

    private boolean validateConnection(EditText urlInput, EditText keyInput) {
        if (!isValidSecureUrl(valueOf(urlInput))) {
            urlInput.setError("请输入有效的 HTTPS 服务地址");
            urlInput.requestFocus();
            return false;
        }
        if (valueOf(keyInput).isEmpty()) {
            keyInput.setError("请输入 API Key");
            keyInput.requestFocus();
            return false;
        }
        return true;
    }

    private void save() {
        boolean sameService = sameServiceSwitch.isChecked();
        if (sameService && !validateConnection(sharedBaseUrlInput, sharedApiKeyInput)) {
            return;
        }
        if (!sameService && (!validateConnection(chatBaseUrlInput, chatApiKeyInput)
                || !validateConnection(imageBaseUrlInput, imageApiKeyInput))) {
            return;
        }

        String chatModel = selectedChatModel();
        String imageModel = selectedImageModel();
        if (chatModel.isEmpty()) {
            Toast.makeText(this, "请先获取并选择对话模型", Toast.LENGTH_SHORT).show();
            (sameService ? sharedChatModelSpinner : chatModelSpinner).requestFocus();
            return;
        }
        if (imageModel.isEmpty()) {
            Toast.makeText(this, "请先获取并选择生图模型", Toast.LENGTH_SHORT).show();
            (sameService ? sharedImageModelSpinner : imageModelSpinner).requestFocus();
            return;
        }

        String chatBaseUrl = sameService ? valueOf(sharedBaseUrlInput) : valueOf(chatBaseUrlInput);
        String chatApiKey = sameService ? valueOf(sharedApiKeyInput) : valueOf(chatApiKeyInput);
        String imageBaseUrl = sameService ? chatBaseUrl : valueOf(imageBaseUrlInput);
        String imageApiKey = sameService ? chatApiKey : valueOf(imageApiKeyInput);
        Runnable persist = () -> persistSettings(sameService, chatBaseUrl, chatApiKey,
                imageBaseUrl, imageApiKey, chatModel, imageModel, valueOf(systemPromptInput));
        if (!AppSettings.hasKeyViewPassword(this)) {
            showCreateKeyPasswordDialog(persist);
        } else {
            persist.run();
        }
    }

    private void persistSettings(
            boolean sameService,
            String chatBaseUrl,
            String chatApiKey,
            String imageBaseUrl,
            String imageApiKey,
            String chatModel,
            String imageModel,
            String systemPrompt) {
        try {
            new AppSettings(
                    sameService,
                    chatBaseUrl,
                    chatApiKey,
                    imageBaseUrl,
                    imageApiKey,
                    chatModel,
                    imageModel,
                    systemPrompt,
                    autoSpeakCheck.isChecked(),
                    selectedVoice(),
                    selectedStyle(),
                    ttsRateSeek.getProgress() - 100,
                    ttsVolumeSeek.getProgress() - 100,
                    ttsPitchSeek.getProgress() - 100,
                    avatarEnabledCheck.isChecked(),
                    selectedAvatarId).save(this);
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        } catch (Exception exception) {
            Toast.makeText(this, "保存失败：" + exception.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String selectedVoice() {
        Object selected = ttsVoiceSpinner.getSelectedItem();
        return selected instanceof EdgeTtsClient.Voice
                ? ((EdgeTtsClient.Voice) selected).shortName
                : AppSettings.DEFAULT_TTS_VOICE;
    }

    private String selectedStyle() {
        Object selected = ttsStyleSpinner.getSelectedItem();
        return selected instanceof EdgeTtsClient.Style
                ? ((EdgeTtsClient.Style) selected).id
                : "general";
    }

    private void setSharedModelAdapters(
            List<String> chatCatalog,
            List<String> imageCatalog,
            String selectedChat,
            String selectedImage) {
        setStringSpinner(sharedChatModelSpinner, withSelected(chatCatalog, selectedChat), selectedChat);
        setStringSpinner(sharedImageModelSpinner, withSelected(imageCatalog, selectedImage), selectedImage);
    }

    private void setDedicatedModelAdapters(
            List<String> chatCatalog,
            List<String> imageCatalog,
            String selectedChat,
            String selectedImage) {
        setStringSpinner(chatModelSpinner, withSelected(chatCatalog, selectedChat), selectedChat);
        setStringSpinner(imageModelSpinner, withSelected(imageCatalog, selectedImage), selectedImage);
    }

    private List<String> withSelected(List<String> values, String selectedValue) {
        LinkedHashSet<String> result = new LinkedHashSet<>(values);
        if (selectedValue != null && !selectedValue.trim().isEmpty()) {
            result.add(selectedValue.trim());
        }
        return new ArrayList<>(result);
    }

    private void setStringSpinner(Spinner spinner, List<String> values, String selectedValue) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, R.layout.item_voice_selected, values);
        adapter.setDropDownViewResource(R.layout.item_voice_dropdown);
        spinner.setAdapter(adapter);
        int selectedIndex = Math.max(0, values.indexOf(selectedValue));
        if (!values.isEmpty()) {
            spinner.setSelection(selectedIndex, false);
        }
    }

    private String selectedModel(Spinner spinner) {
        Object selected = spinner.getSelectedItem();
        return selected == null ? "" : selected.toString().trim();
    }

    private String selectedChatModel() {
        return selectedModel(sameServiceSwitch.isChecked()
                ? sharedChatModelSpinner
                : chatModelSpinner);
    }

    private String selectedImageModel() {
        return selectedModel(sameServiceSwitch.isChecked()
                ? sharedImageModelSpinner
                : imageModelSpinner);
    }

    private void updateConnectionMode(boolean sameService, boolean syncSelection) {
        if (syncSelection) {
            if (sameService) {
                selectSpinnerValue(sharedChatModelSpinner, selectedModel(chatModelSpinner));
                selectSpinnerValue(sharedImageModelSpinner, selectedModel(imageModelSpinner));
                sharedBaseUrlInput.setText(valueOf(chatBaseUrlInput));
                sharedApiKeyInput.setText(valueOf(chatApiKeyInput));
            } else {
                selectSpinnerValue(chatModelSpinner, selectedModel(sharedChatModelSpinner));
                selectSpinnerValue(imageModelSpinner, selectedModel(sharedImageModelSpinner));
                if (valueOf(chatBaseUrlInput).isEmpty() && valueOf(chatApiKeyInput).isEmpty()) {
                    chatBaseUrlInput.setText(valueOf(sharedBaseUrlInput));
                    chatApiKeyInput.setText(valueOf(sharedApiKeyInput));
                }
                if (valueOf(imageBaseUrlInput).isEmpty() && valueOf(imageApiKeyInput).isEmpty()) {
                    imageBaseUrlInput.setText(valueOf(sharedBaseUrlInput));
                    imageApiKeyInput.setText(valueOf(sharedApiKeyInput));
                }
            }
        }
        sharedConnectionContainer.setVisibility(sameService ? View.VISIBLE : View.GONE);
        separateConnectionContainer.setVisibility(sameService ? View.GONE : View.VISIBLE);
    }

    private void selectSpinnerValue(Spinner spinner, String value) {
        if (value == null || value.isEmpty() || spinner.getAdapter() == null) {
            return;
        }
        for (int index = 0; index < spinner.getAdapter().getCount(); index++) {
            if (value.equals(String.valueOf(spinner.getAdapter().getItem(index)))) {
                spinner.setSelection(index, false);
                return;
            }
        }
    }

    private boolean isImageModel(String model) {
        String lower = model == null ? "" : model.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("image")
                || lower.contains("dall-e")
                || lower.contains("flux")
                || lower.contains("midjourney")
                || lower.contains("stable-diffusion");
    }

    private void configureKeyReveal(EditText keyInput, CheckBox checkBox) {
        checkBox.setOnCheckedChangeListener((button, checked) -> {
            if (updatingKeyVisibility) {
                return;
            }
            if (checked) {
                setShowKeyChecked(checkBox, false);
                requestKeyReveal(keyInput, checkBox);
            } else {
                setKeyVisible(keyInput, false);
            }
        });
    }

    private void requestKeyReveal(EditText keyInput, CheckBox checkBox) {
        if (valueOf(keyInput).isEmpty()) {
            keyInput.setError("请先输入 API Key");
            keyInput.requestFocus();
            return;
        }
        if (!AppSettings.hasKeyViewPassword(this)) {
            showCreateKeyPasswordDialog(() -> {
                setKeyVisible(keyInput, true);
                setShowKeyChecked(checkBox, true);
            });
            return;
        }
        EditText passwordInput = passwordInput(R.string.key_password_hint);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.key_password_title)
                .setView(wrapDialogInput(passwordInput))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    if (!AppSettings.verifyKeyViewPassword(this, rawValueOf(passwordInput))) {
                        passwordInput.setError(getString(R.string.key_password_incorrect));
                        return;
                    }
                    setKeyVisible(keyInput, true);
                    setShowKeyChecked(checkBox, true);
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void showCreateKeyPasswordDialog(Runnable onSuccess) {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        fields.setPadding(padding, 0, padding, 0);
        TextView message = new TextView(this);
        message.setText(R.string.set_key_password_message);
        message.setTextColor(getColor(R.color.text_secondary));
        message.setPadding(0, 0, 0, padding / 2);
        EditText password = passwordInput(R.string.new_key_password_hint);
        EditText confirmation = passwordInput(R.string.confirm_key_password_hint);
        fields.addView(message);
        fields.addView(password);
        fields.addView(confirmation);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.set_key_password_title)
                .setView(fields)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String first = rawValueOf(password);
                    String second = rawValueOf(confirmation);
                    if (first.isEmpty()) {
                        password.setError(getString(R.string.key_password_empty));
                        return;
                    }
                    if (!first.equals(second)) {
                        confirmation.setError(getString(R.string.key_password_mismatch));
                        return;
                    }
                    try {
                        AppSettings.setKeyViewPassword(this, first);
                        dialog.dismiss();
                        onSuccess.run();
                    } catch (Exception exception) {
                        Toast.makeText(this, "密码保存失败：" + exception.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }));
        dialog.show();
    }

    private EditText passwordInput(int hintRes) {
        EditText input = new EditText(this);
        input.setHint(hintRes);
        input.setSingleLine(true);
        input.setTextColor(getColor(R.color.text_primary));
        input.setHintTextColor(getColor(R.color.text_secondary));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setTransformationMethod(PasswordTransformationMethod.getInstance());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            input.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        }
        return input;
    }

    private LinearLayout wrapDialogInput(EditText input) {
        LinearLayout wrapper = new LinearLayout(this);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        wrapper.setPadding(padding, 0, padding, 0);
        wrapper.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrapper;
    }

    private void setKeyVisible(EditText keyInput, boolean visible) {
        int cursor = Math.max(0, keyInput.getSelectionStart());
        keyInput.setTransformationMethod(visible
                ? HideReturnsTransformationMethod.getInstance()
                : PasswordTransformationMethod.getInstance());
        keyInput.setSelection(Math.min(cursor, keyInput.length()));
    }

    private void setShowKeyChecked(CheckBox checkBox, boolean checked) {
        updatingKeyVisibility = true;
        checkBox.setChecked(checked);
        updatingKeyVisibility = false;
    }

    private boolean isValidSecureUrl(String value) {
        try {
            Uri uri = Uri.parse(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && !uri.getHost().isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private String valueOf(TextView input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    private String rawValueOf(TextView input) {
        return input.getText() == null ? "" : input.getText().toString();
    }
}
