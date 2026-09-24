package cloud.pcie.openaiq;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ListView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

/** Real widget screenshots with temporary data; never calls an API or writes chat history. */
public final class LayoutPreviewInstrumentation extends Instrumentation {
    private boolean avatarOnly;
    private boolean haiOnly;
    private Bundle auditArguments;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        avatarOnly = arguments != null && "true".equals(arguments.getString("avatarOnly"));
        haiOnly = arguments != null && "true".equals(arguments.getString("haiOnly"));
        auditArguments = arguments;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Activity activity = null;
        try {
            if (auditArguments != null && "true".equals(auditArguments.getString("allAvatars"))) {
                new AvatarAudit(this).run(auditArguments);
                result.putString("result", "Completed model audit; inspect avatar-audit report and screenshots");
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (haiOnly) {
                verifyHaiAvatars();
                result.putString("result", "PASS: requested avatars preview, full/portrait and main stage");
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (avatarOnly) {
                verifyAvatarLibrary();
                result.putString("result", "PASS: avatar preview, half/full modes, repeated selection, filtering, bounds");
                finish(Activity.RESULT_OK, result);
                return;
            }
            verifyImageRequestOptions();
            Intent intent = new Intent(getTargetContext(), MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(intent);
            Activity screen = activity;
            runOnMainSync(() -> {
                screen.findViewById(R.id.conversationViewButton).performClick();
                ArrayList<ChatMessage> fixture = new ArrayList<>();
                fixture.add(new ChatMessage(ChatMessage.ROLE_USER, "你好，今天想去户外走走。"));
                ChatMessage first = new ChatMessage(ChatMessage.ROLE_ASSISTANT,
                        "可以呀。你更想沿着河边散步，还是去公园找一片安静的树荫？\n\n告诉我所在的城市，我可以帮你查询天气，再一起安排路线。");
                first.meta = "耗时 1.2 秒";
                fixture.add(first);
                fixture.add(new ChatMessage(ChatMessage.ROLE_USER, "先帮我列一份出门前的准备清单吧。"));
                ChatMessage second = new ChatMessage(ChatMessage.ROLE_ASSISTANT,
                        "带上水、纸巾和充好电的手机，穿一双舒适的鞋。\n\n如果准备待到傍晚，可以多带一件薄外套。路线不用排得太满，留些时间休息、拍照。");
                second.meta = "耗时 0.8 秒";
                fixture.add(second);
                ListView list = screen.findViewById(R.id.messageList);
                list.setAdapter(new MessageAdapter(screen, fixture, new MessageAdapter.Actions() {
                    public void onRetry(int position) { }
                    public void onOpenImage(ChatMessage message) { }
                    public void onDownloadImage(ChatMessage message) { }
                    public void onDelete(ChatMessage message) { }
                    public void onSpeak(ChatMessage message) { }
                    public boolean isSpeaking(ChatMessage message) { return false; }
                }));
                list.setSelection(0);
            });
            waitForIdleSync();
            SystemClock.sleep(700);
            runOnMainSync(() -> {
                EditText input = screen.findViewById(R.id.messageInput);
                screen.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);
                input.clearFocus();
            });
            waitForIdleSync();
            SystemClock.sleep(700);
            runOnMainSync(() -> {
                require(screen.findViewById(R.id.messageList).isShown(), "Chat list is hidden");
                require(!screen.findViewById(R.id.avatarStage).isShown(), "Avatar overlaps chat");
                checkChildren(screen.findViewById(R.id.headerRow));
                checkChildren(screen.findViewById(R.id.composerRow));
                checkChildren(screen.findViewById(R.id.messageList));
                checkReferenceHeight(screen, R.id.headerRow, 72);
                checkReferenceHeight(screen, R.id.modeTabsRow, 52);
                checkReferenceHeight(screen, R.id.chatButton, 38);
                checkReferenceHeight(screen, R.id.messageInput, 52);
                checkReferenceHeight(screen, R.id.attachmentButton, 52);
                checkReferenceHeight(screen, R.id.sendButton, 52);
                require(screen.findViewById(R.id.chatButton).isSelected(), "Chat tab not selected");
            });
            capture(screen, "chat");
            runOnMainSync(() -> {
                try {
                    java.lang.reflect.Field enabled = MainActivity.class.getDeclaredField("avatarEnabled");
                    enabled.setAccessible(true);
                    java.lang.reflect.Method update = MainActivity.class.getDeclaredMethod("updateModeViews");
                    update.setAccessible(true);
                    boolean original = enabled.getBoolean(screen);
                    try {
                        enabled.setBoolean(screen, false);
                        update.invoke(screen);
                        require(screen.findViewById(R.id.chatViewSwitcher).getVisibility() == View.GONE,
                                "Disabled interaction leaves the view switcher visible");
                        require(screen.findViewById(R.id.voiceModeButton).getVisibility() == View.GONE,
                                "Disabled interaction leaves the return-to-character button visible");
                        require(screen.findViewById(R.id.messageList).isShown(), "Disabled interaction hides chat");
                    } finally {
                        enabled.setBoolean(screen, original);
                        update.invoke(screen);
                    }
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            runOnMainSync(() -> {
                // A streaming speech session with no text performs no network request.
                // It must remain the same session while changing both display modes.
                try {
                    java.lang.reflect.Field field = MainActivity.class.getDeclaredField("speechController");
                    field.setAccessible(true);
                    SpeechController speech = (SpeechController) field.get(screen);
                    ChatMessage reply = new ChatMessage(ChatMessage.ROLE_ASSISTANT, "同步朗读测试");
                    speech.startStreaming(reply, AppSettings.load(screen));
                    java.lang.reflect.Method busy = MainActivity.class.getDeclaredMethod("setBusy", boolean.class);
                    busy.setAccessible(true);
                    busy.invoke(screen, true);
                    screen.findViewById(R.id.characterViewButton).performClick();
                    require(screen.findViewById(R.id.avatarStage).isShown(), "Character view failed");
                    require(speech.isStreaming(reply), "Character switch stopped speech");
                    screen.findViewById(R.id.conversationViewButton).performClick();
                    require(speech.isStreaming(reply), "Conversation switch stopped speech");
                    screen.findViewById(R.id.imageButton).performClick();
                    require(speech.isStreaming(reply), "Image tab stopped speech");
                    screen.findViewById(R.id.chatButton).performClick();
                    require(screen.findViewById(R.id.messageList).isShown(), "Chat view choice was lost");
                    require(speech.isStreaming(reply), "Returning to chat stopped speech");
                    busy.invoke(screen, false);
                    speech.stop();
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            int initialHeight = screen.findViewById(R.id.composerRow).getHeight();
            runOnMainSync(() -> {
                EditText input = screen.findViewById(R.id.messageInput);
                input.setText("请画一张傍晚的城市街道，\n路边亮着暖黄色的灯，\n有一间安静的咖啡馆，\n门口摆着花，\n远处有人慢慢走过。\n画面自然，保留生活气息。");
                input.requestFocus();
            });
            waitForIdleSync();
            SystemClock.sleep(400);
            runOnMainSync(() -> {
                View dock = screen.findViewById(R.id.composerRow);
                require(dock.getHeight() > initialHeight, "Multiline input did not grow");
                checkChildren(dock);
                require(Math.abs(screen.findViewById(R.id.attachmentButton).getBottom()
                        - screen.findViewById(R.id.sendButton).getBottom()) <= 1,
                        "Composer controls do not share a bottom edge");
                EditText input = screen.findViewById(R.id.messageInput);
                screen.getSystemService(InputMethodManager.class).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            });
            waitForIdleSync();
            SystemClock.sleep(700);
            capture(screen, "multiline");
            runOnMainSync(() -> {
                ((EditText) screen.findViewById(R.id.messageInput)).setText("");
                screen.findViewById(R.id.imageButton).performClick();
            });
            waitForIdleSync();
            SystemClock.sleep(500);
            runOnMainSync(() -> {
                require(screen.findViewById(R.id.imageButton).isSelected(), "Image tab not selected");
                require(!screen.findViewById(R.id.chatButton).isSelected(), "Chat tab still selected");
                require(screen.findViewById(R.id.imageWorkbench).isShown(), "Image workspace hidden");
                require(!screen.findViewById(R.id.composerRow).isShown(), "Chat composer overlaps image workspace");
                require(!screen.findViewById(R.id.chatViewSwitcher).isShown(), "Chat switcher in image workspace");
                checkChildren(screen.findViewById(R.id.imageWorkbench));
                EditText imagePrompt = screen.findViewById(R.id.imagePromptInput);
                imagePrompt.setText("一只坐在窗边的小猫，柔和的阳光，背景是蓝色城市。\n保留自然细节。");
                ImageWorkbenchView studio = screen.findViewById(R.id.imageWorkbench);
                require(studio.options().size.equals("1024x1024"), "Default image size incorrect");
                require(studio.options().quality.equals("medium"), "Default image quality incorrect");
                screen.findViewById(R.id.generateImageButton).setContentDescription("生成图片");
                screen.findViewById(R.id.chatButton).performClick();
                ((EditText) screen.findViewById(R.id.messageInput)).setText("聊天草稿");
                screen.findViewById(R.id.imageButton).performClick();
                require(imagePrompt.getText().toString().startsWith("一只"), "Image draft overwritten by chat");
                screen.findViewById(R.id.chatButton).performClick();
                require(((EditText) screen.findViewById(R.id.messageInput)).getText().toString().equals("聊天草稿"), "Chat draft overwritten");
                ((EditText) screen.findViewById(R.id.messageInput)).setText("");
                screen.findViewById(R.id.imageButton).performClick();
                EditText input = screen.findViewById(R.id.messageInput);
                screen.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);
                input.clearFocus();
            });
            waitForIdleSync();
            SystemClock.sleep(500);
            capture(screen, "image");
            runOnMainSync(() -> {
                View workspace = screen.findViewById(R.id.imageWorkbench);
                View generate = screen.findViewById(R.id.generateImageButton);
                int[] workspacePosition = new int[2], buttonPosition = new int[2];
                workspace.getLocationOnScreen(workspacePosition);
                generate.getLocationOnScreen(buttonPosition);
                require(buttonPosition[1] + generate.getHeight() <= workspacePosition[1] + workspace.getHeight(),
                        "Image actions are not visible in the initial viewport");
            });
            runOnMainSync(() -> {
                screen.findViewById(R.id.chatButton).performClick();
                require(screen.findViewById(R.id.messageList).isShown(), "Tab return did not retain chat view");
                screen.findViewById(R.id.characterViewButton).performClick();
            });
            waitForIdleSync();
            SystemClock.sleep(500);
            runOnMainSync(() -> require(screen.findViewById(R.id.avatarStage).isShown(), "Voice return failed"));
            capture(screen, "character");
            result.putString("result", "PASS: view switching, retained speech session, separate drafts, image workspace, multiline, bounds");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("error", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (activity != null) {
                Activity screen = activity;
                runOnMainSync(screen::finish);
            }
        }
    }

    private void verifyHaiAvatars() throws Exception {
        String originalId = auditArguments.getString("restoreAvatarId", AppSettings.load(getTargetContext()).avatarId);
        boolean originalFull = AppSettings.loadAvatarFullBody(getTargetContext());
        try {
            for (String name : auditArguments.getString("modelNames", "Ning Hai|Ping Hai").split("\\|")) {
                Activity library = startActivitySync(new Intent(getTargetContext(), AvatarLibraryActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                try {
                    runOnMainSync(() -> {
                        ((EditText) library.findViewById(R.id.avatarSearchInput)).setText(name);
                        ListView list = library.findViewById(R.id.avatarList);
                        for (int i = 0; i < list.getCount(); i++) {
                            AvatarCatalog.Avatar candidate = (AvatarCatalog.Avatar) list.getItemAtPosition(i);
                            if (candidate.name.equals(name)) {
                                list.performItemClick(null, i, list.getItemIdAtPosition(i));
                                break;
                            }
                        }
                    });
                    awaitAvatar(library, R.id.libraryViewToggleButton);
                    for (boolean full : new boolean[]{true, false}) {
                        runOnMainSync(() -> library.findViewById(full
                                ? R.id.libraryViewToggleButton : R.id.libraryPortraitButton).performClick());
                        SystemClock.sleep(1400);
                        capture(library, name.replace(' ', '-') + (full ? "-full" : "-portrait"));
                    }
                    runOnMainSync(() -> {
                        View use = library.findViewById(R.id.useAvatarButton);
                        if (use.getVisibility() == View.VISIBLE) {
                            require(use.isEnabled(), "Use action disabled");
                            use.performClick();
                        }
                    });
                    require(AvatarCatalog.find(getTargetContext(), AppSettings.load(getTargetContext()).avatarId)
                            .name.equals(name), "Selected model not saved");
                } finally { runOnMainSync(library::finish); }
                Activity main = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                try {
                    runOnMainSync(() -> main.findViewById(R.id.characterViewButton).performClick());
                    SystemClock.sleep(4000);
                    capture(main, name.replace(' ', '-') + "-main");
                } finally { runOnMainSync(main::finish); }
            }
        } finally {
            runOnMainSync(() -> {
                AppSettings.saveAvatarSelection(getTargetContext(), originalId);
                AppSettings.saveAvatarFullBody(getTargetContext(), originalFull);
            });
        }
    }

    private void awaitAvatar(Activity screen, int id) {
        long deadline = SystemClock.uptimeMillis() + 30000;
        boolean[] ready = {false};
        do {
            runOnMainSync(() -> ready[0] = screen.findViewById(id).isEnabled());
            if (!ready[0]) SystemClock.sleep(250);
        } while (!ready[0] && SystemClock.uptimeMillis() < deadline);
        require(ready[0], "Avatar did not load within 30 seconds");
    }

    private void verifyAvatarLibrary() throws Exception {
        boolean originalFull = AppSettings.loadAvatarFullBody(getTargetContext());
        Intent intent = new Intent(getTargetContext(), AvatarLibraryActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity screen = startActivitySync(intent);
        try {
            long deadline = SystemClock.uptimeMillis() + 30000;
            boolean[] ready = {false};
            do {
                runOnMainSync(() -> ready[0] = screen.findViewById(R.id.libraryViewToggleButton).isEnabled());
                if (!ready[0]) SystemClock.sleep(250);
            } while (!ready[0] && SystemClock.uptimeMillis() < deadline);
            require(ready[0], "Avatar did not load within 30 seconds");
            runOnMainSync(() -> {
                screen.findViewById(R.id.libraryViewToggleButton).performClick();
                require(AppSettings.loadAvatarFullBody(screen), "Full-body mode not persisted");
                require(screen.findViewById(R.id.libraryViewToggleButton).isSelected(), "Full-body not highlighted");
                require(!screen.findViewById(R.id.libraryPortraitButton).isSelected(), "Both modes highlighted");
                checkChildren(screen.findViewById(android.R.id.content));
            });
            SystemClock.sleep(1200);
            capture(screen, "avatar-full");
            runOnMainSync(() -> {
                screen.findViewById(R.id.libraryPortraitButton).performClick();
                require(!AppSettings.loadAvatarFullBody(screen), "Portrait mode not persisted");
                require(screen.findViewById(R.id.libraryPortraitButton).isSelected(), "Portrait not highlighted");
                require(!screen.findViewById(R.id.libraryViewToggleButton).isSelected(), "Full-body remains highlighted");
                ListView list = screen.findViewById(R.id.avatarList);
                String selected = AppSettings.load(screen).avatarId;
                for (int i = 0; i < list.getCount(); i++) {
                    AvatarCatalog.Avatar avatar = (AvatarCatalog.Avatar) list.getItemAtPosition(i);
                    if (avatar.id.equals(selected)) {
                        list.performItemClick(null, i, list.getItemIdAtPosition(i));
                        require(screen.findViewById(R.id.libraryViewToggleButton).isEnabled(),
                                "Reselecting the ready avatar disables the view modes");
                        break;
                    }
                }
                EditText search = screen.findViewById(R.id.avatarSearchInput);
                search.setText("no-such-avatar-123");
                require(list.getCount() == 0, "Search filter failed");
                search.setText("");
                require(list.getCount() > 0, "Clearing search failed");
            });
            SystemClock.sleep(1200);
            capture(screen, "avatar-portrait");
        } finally {
            runOnMainSync(() -> {
                AppSettings.saveAvatarFullBody(screen, originalFull);
                screen.finish();
            });
        }
    }

    private void capture(Activity screen, String label) throws Exception {
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        require(bitmap != null, "Screenshot unavailable");
        File target = new File(screen.getExternalFilesDir(null), "layout-" + label + "-" + bitmap.getWidth() + ".png");
        try (FileOutputStream output = new FileOutputStream(target)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        bitmap.recycle();
    }

    private void verifyImageRequestOptions() throws Exception {
        for (int ratio = 0; ratio < ImageGenerationOptions.SIZES.length; ratio++) {
            String[] size = ImageGenerationOptions.SIZES[ratio].split("x");
            int width = Integer.parseInt(size[0]), height = Integer.parseInt(size[1]);
            require(width % 16 == 0 && height % 16 == 0, "Image size violates 16-pixel alignment");
            String[] aspect = ImageGenerationOptions.RATIOS[ratio].split(":");
            require(width * Integer.parseInt(aspect[1]) == height * Integer.parseInt(aspect[0]), "Wrong aspect ratio");
            for (String quality : ImageGenerationOptions.QUALITIES) {
                ChatMessage response = new ChatMessage(ChatMessage.ROLE_ASSISTANT, "");
                response.imageSize = ImageGenerationOptions.SIZES[ratio];
                response.imageQuality = quality;
                response.imagePrompt = ImageGenerationOptions.prompt("窗边的小猫", ratio, 2);
                ChatMessage restored = ChatMessage.fromJson(response.toJson());
                ImageGenerationOptions options = new ImageGenerationOptions(restored.imageSize, restored.imageQuality);
                org.json.JSONObject payload = OpenAiClient.imagePayload("gpt-image-2", restored.imagePrompt, options);
                require(payload.getString("size").equals(response.imageSize), "Size lost after persistence");
                require(payload.getString("quality").equals(quality), "Quality lost after persistence");
                require(payload.getString("prompt").contains("写实"), "Style omitted from generation");
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                new OpenAiClient(getTargetContext()).writeImageOptions(output, "TEST", options);
                String multipart = output.toString("UTF-8");
                require(multipart.contains("name=\"size\"\r\n\r\n" + response.imageSize), "Edit size missing");
                require(multipart.contains("name=\"quality\"\r\n\r\n" + quality), "Edit quality missing");
            }
        }
        org.json.JSONObject legacy = OpenAiClient.imagePayload("legacy", "cat", new ImageGenerationOptions("", ""));
        require(!legacy.has("size") && !legacy.has("quality"), "Legacy request defaults changed");
    }

    private static void checkChildren(View view) {
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            require(group instanceof android.widget.HorizontalScrollView
                            || (child.getLeft() >= 0 && child.getRight() <= group.getWidth() + 1),
                    "Horizontal clipping in " + group.getClass().getSimpleName());
            checkChildren(child);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void checkReferenceHeight(Activity screen, int id, int referenceDp) {
        float expected = screen.getResources().getDisplayMetrics().widthPixels * referenceDp * 3f / 1264f;
        require(Math.abs(screen.findViewById(id).getHeight() - expected) <= 1,
                "Design scale mismatch for " + id);
    }
}
