package cloud.pcie.openaiq;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import org.json.JSONObject;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Owns image requests independently from Activity lifecycle and persists their target message. */
public final class ImageGenerationService extends Service {
    static final String ACTION_EVENT = "cloud.pcie.openaiq.IMAGE_JOB_EVENT";
    static final String EXTRA_STATE = "state";
    static final String EXTRA_RESPONSE_ID = "response_id";
    static final String EXTRA_CONVERSATION_ID = "conversation_id";
    static final String EXTRA_ATTEMPT = "attempt";
    static final String STATE_RUNNING = "running";
    static final String STATE_RETRYING = "retrying";
    static final String STATE_SUCCESS = "success";
    static final String STATE_FAILED = "failed";
    static final String STATE_CANCELLED = "cancelled";

    private static final String ACTION_START = "cloud.pcie.openaiq.START_IMAGE_JOB";
    private static final String ACTION_CANCEL = "cloud.pcie.openaiq.CANCEL_IMAGE_JOB";
    private static final String PREFS = "image_generation_job";
    private static final String KEY_RUNNING = "running";
    private static final String KEY_JOB = "job";
    private static final String CHANNEL_PROGRESS = "ning_image_progress";
    private static final String CHANNEL_RESULTS = "ning_image_results";
    private static final int NOTIFICATION_PROGRESS = 3101;
    private static final int NOTIFICATION_RESULT = 3102;
    private static final int MAX_ATTEMPTS = 3;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService executor;
    private OpenAiClient client;
    private ConversationStore store;
    private NotificationManager notifications;
    private OpenAiClient.RequestHandle activeHandle;
    private PowerManager.WakeLock wakeLock;
    private Job activeJob;
    private boolean cancelled;

    static final class Snapshot {
        final boolean running;
        final String conversationId;
        final String responseId;
        final int attempt;

        Snapshot(boolean running, String conversationId, String responseId, int attempt) {
            this.running = running;
            this.conversationId = conversationId;
            this.responseId = responseId;
            this.attempt = attempt;
        }
    }

    static void start(
            Context context,
            String conversationId,
            ChatMessage response,
            ChatMessage reference) {
        Job job = new Job();
        job.conversationId = conversationId;
        job.responseId = response.id;
        job.prompt = response.imagePrompt;
        job.size = response.imageSize;
        job.quality = response.imageQuality;
        job.startedAt = SystemClock.elapsedRealtime();
        if (reference != null) {
            try {
                job.referenceJson = reference.toJson().toString();
            } catch (Exception ignored) {
                job.referenceJson = "";
            }
        }
        context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_RUNNING, true)
                .putString(KEY_JOB, job.toJson().toString())
                .commit();
        Intent intent = new Intent(context, ImageGenerationService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONVERSATION_ID, conversationId)
                .putExtra(EXTRA_RESPONSE_ID, response.id)
                .putExtra("prompt", response.imagePrompt)
                .putExtra("size", response.imageSize)
                .putExtra("quality", response.imageQuality);
        intent.putExtra("reference", job.referenceJson);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                context.startForegroundService(intent);
            } catch (RuntimeException exception) {
                context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().commit();
                throw exception;
            }
        } else {
            try {
                context.startService(intent);
            } catch (RuntimeException exception) {
                context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().commit();
                throw exception;
            }
        }
    }

    static void cancel(Context context) {
        Intent intent = new Intent(context, ImageGenerationService.class).setAction(ACTION_CANCEL);
        context.startService(intent);
    }

    static Snapshot snapshot(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        Job job = Job.fromJson(preferences.getString(KEY_JOB, ""));
        boolean running = preferences.getBoolean(KEY_RUNNING, false) && job != null;
        return new Snapshot(
                running,
                job == null ? "" : job.conversationId,
                job == null ? "" : job.responseId,
                job == null ? 0 : job.attempt);
    }

    static boolean isPendingResponse(Context context, String responseId) {
        Snapshot snapshot = snapshot(context);
        return snapshot.running && snapshot.responseId.equals(responseId);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadScheduledExecutor();
        client = new OpenAiClient(getApplicationContext());
        store = new ConversationStore(getApplicationContext());
        notifications = getSystemService(NotificationManager.class);
        createNotificationChannels();
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null) {
            wakeLock = power.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "NING:ImageGeneration");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            cancelActiveJob();
            return START_NOT_STICKY;
        }
        // start() commits the complete job before launching the service. Prefer that copy so
        // elapsed time and retry state survive both normal starts and process recreation.
        Job job = readPersistedJob();
        if (job == null && ACTION_START.equals(action)) {
            job = Job.fromIntent(intent);
        }
        if (job == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (activeJob != null) {
            return START_STICKY;
        }
        begin(job);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void begin(Job job) {
        activeJob = job;
        cancelled = false;
        if (job.startedAt <= 0L) job.startedAt = SystemClock.elapsedRealtime();
        persist(job, true);
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(30L * 60L * 1000L);
        }
        int attempt = Math.max(1, Math.min(MAX_ATTEMPTS, job.attempt));
        startForeground(NOTIFICATION_PROGRESS, progressNotification(attempt, false));
        startAttempt(attempt);
    }

    private void startAttempt(int attempt) {
        Job job = activeJob;
        if (job == null || cancelled) return;
        job.attempt = attempt;
        persist(job, true);
        notifications.notify(NOTIFICATION_PROGRESS, progressNotification(attempt, false));
        updateProgressMessage(job, attempt);
        broadcast(STATE_RUNNING, job);

        AppSettings settings = AppSettings.load(this);
        if (!settings.isImageConfigured()) {
            failPermanently(job, "生图服务配置不完整");
            return;
        }
        activeHandle = client.generateImage(
                executor,
                settings,
                job.prompt,
                job.reference(),
                new ImageGenerationOptions(job.size, job.quality),
                new OpenAiClient.ImageListener() {
                    @Override
                    public void onSuccess(OpenAiClient.ImageResult image) {
                        mainHandler.post(() -> complete(job, image));
                    }

                    @Override
                    public void onError(String message) {
                        mainHandler.post(() -> failedAttempt(job, message));
                    }
                });
    }

    private void updateProgressMessage(Job job, int attempt) {
        ChatMessage message = store.findMessage(job.conversationId, job.responseId);
        if (message == null) return;
        message.content = attempt == 1
                ? "正在后台生成图片…"
                : "生图失败，正在自动重试（" + attempt + "/" + MAX_ATTEMPTS + "）…";
        message.error = false;
        message.retryable = false;
        message.meta = "第 " + attempt + "/" + MAX_ATTEMPTS + " 次尝试";
        store.updateMessage(job.conversationId, message);
    }

    private void failedAttempt(Job job, String error) {
        if (!isCurrent(job) || cancelled) return;
        activeHandle = null;
        if (job.attempt < MAX_ATTEMPTS) {
            int nextAttempt = job.attempt + 1;
            ChatMessage message = store.findMessage(job.conversationId, job.responseId);
            if (message != null) {
                message.content = "生图失败，准备自动重试（" + nextAttempt + "/"
                        + MAX_ATTEMPTS + "）：" + error;
                message.error = false;
                message.retryable = false;
                message.meta = "等待重试";
                store.updateMessage(job.conversationId, message);
            }
            notifications.notify(
                    NOTIFICATION_PROGRESS,
                    progressNotification(nextAttempt, true));
            broadcast(STATE_RETRYING, job);
            long delaySeconds = job.attempt == 1 ? 2L : 5L;
            executor.schedule(
                    () -> mainHandler.post(() -> startAttempt(nextAttempt)),
                    delaySeconds,
                    TimeUnit.SECONDS);
            return;
        }
        failPermanently(job, error);
    }

    private void complete(Job job, OpenAiClient.ImageResult image) {
        if (!isCurrent(job) || cancelled) return;
        activeHandle = null;
        ChatMessage message = store.findMessage(job.conversationId, job.responseId);
        if (message != null) {
            message.content = "图片已生成";
            message.imageUri = image.uri;
            message.imageMime = image.mime;
            message.imageName = image.name;
            message.generatedImage = true;
            message.error = false;
            message.retryable = false;
            message.meta = duration(job.startedAt) + " · " + job.attempt + " 次尝试";
            store.updateMessage(job.conversationId, message);
        }
        broadcast(STATE_SUCCESS, job);
        finishForegroundJob(job, STATE_SUCCESS, getString(R.string.image_job_complete));
    }

    private void failPermanently(Job job, String error) {
        if (!isCurrent(job) || cancelled) return;
        activeHandle = null;
        ChatMessage message = store.findMessage(job.conversationId, job.responseId);
        if (message != null) {
            message.content = "生图失败（已尝试 " + MAX_ATTEMPTS + " 次）：" + error;
            message.error = true;
            message.retryable = true;
            message.meta = duration(job.startedAt);
            store.updateMessage(job.conversationId, message);
        }
        broadcast(STATE_FAILED, job);
        finishForegroundJob(job, STATE_FAILED, getString(R.string.image_job_failed));
    }

    private void cancelActiveJob() {
        Job job = activeJob != null ? activeJob : readPersistedJob();
        cancelled = true;
        if (activeHandle != null) activeHandle.cancel();
        activeHandle = null;
        if (job != null) {
            ChatMessage message = store.findMessage(job.conversationId, job.responseId);
            if (message != null) {
                message.content = "已停止生成图片。";
                message.error = true;
                message.retryable = true;
                message.meta = "已手动停止 · " + duration(job.startedAt);
                store.updateMessage(job.conversationId, message);
            }
            broadcast(STATE_CANCELLED, job);
        }
        clearPersistedJob();
        stopForeground(true);
        releaseWakeLock();
        activeJob = null;
        stopSelf();
    }

    private void finishForegroundJob(Job job, String state, String title) {
        clearPersistedJob();
        stopForeground(true);
        notifications.notify(
                NOTIFICATION_RESULT,
                resultNotification(job, title, state.equals(STATE_SUCCESS)));
        vibrate(state.equals(STATE_SUCCESS));
        releaseWakeLock();
        activeJob = null;
        stopSelf();
    }

    private boolean isCurrent(Job job) {
        return activeJob == job;
    }

    private Notification progressNotification(int attempt, boolean retrying) {
        Intent cancel = new Intent(this, ImageGenerationService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelIntent = PendingIntent.getService(
                this,
                1,
                cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = getString(
                retrying ? R.string.image_job_retrying : R.string.image_job_running,
                attempt);
        return builder(CHANNEL_PROGRESS, activeJob)
                .setContentTitle("NING · 生图任务")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .addAction(android.R.drawable.ic_media_pause, getString(R.string.stop), cancelIntent)
                .build();
    }

    private Notification resultNotification(Job job, String title, boolean success) {
        return builder(CHANNEL_RESULTS, job)
                .setContentTitle(title)
                .setContentText(success ? "点击进入 NING 查看图片" : "请进入 NING 查看错误并重试")
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(openAppIntent(job))
                .build();
    }

    private Notification.Builder builder(String channel, Job job) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, channel)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setColor(getColor(R.color.accent))
                .setContentIntent(openAppIntent(job))
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PRIVATE);
    }

    private PendingIntent openAppIntent(Job job) {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("open_image_workspace", true);
        if (job != null) {
            open.putExtra(EXTRA_CONVERSATION_ID, job.conversationId);
        }
        return PendingIntent.getActivity(
                this,
                2,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || notifications == null) return;
        NotificationChannel progress = new NotificationChannel(
                CHANNEL_PROGRESS,
                getString(R.string.image_job_channel),
                NotificationManager.IMPORTANCE_LOW);
        progress.setDescription("显示正在后台执行的生图任务");
        progress.setSound(null, null);
        notifications.createNotificationChannel(progress);

        NotificationChannel results = new NotificationChannel(
                CHANNEL_RESULTS,
                "生图完成提醒",
                NotificationManager.IMPORTANCE_DEFAULT);
        results.enableVibration(true);
        notifications.createNotificationChannel(results);
    }

    private void vibrate(boolean success) {
        Vibrator vibrator;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = getSystemService(VibratorManager.class);
            vibrator = manager == null ? null : manager.getDefaultVibrator();
        } else {
            vibrator = getSystemService(Vibrator.class);
        }
        if (vibrator == null || !vibrator.hasVibrator()) return;
        long[] pattern = success ? new long[]{0, 120, 80, 180} : new long[]{0, 350};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            vibrator.vibrate(pattern, -1);
        }
    }

    private void broadcast(String state, Job job) {
        sendBroadcast(new Intent(ACTION_EVENT)
                .setPackage(getPackageName())
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_CONVERSATION_ID, job.conversationId)
                .putExtra(EXTRA_RESPONSE_ID, job.responseId)
                .putExtra(EXTRA_ATTEMPT, job.attempt));
    }

    private void persist(Job job, boolean running) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_RUNNING, running)
                .putString(KEY_JOB, job.toJson().toString())
                .apply();
    }

    private Job readPersistedJob() {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!preferences.getBoolean(KEY_RUNNING, false)) return null;
        return Job.fromJson(preferences.getString(KEY_JOB, ""));
    }

    private void clearPersistedJob() {
        // Keep the last target IDs after completion. MainActivity uses them to merge the
        // service-owned response before saving a stale in-memory conversation on background.
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_RUNNING, false)
                .apply();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private static String duration(long startedAt) {
        long seconds = Math.max(0L, (SystemClock.elapsedRealtime() - startedAt) / 1000L);
        return seconds + " 秒";
    }

    @Override
    public void onDestroy() {
        if (activeHandle != null) activeHandle.cancel();
        releaseWakeLock();
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    private static final class Job {
        String conversationId;
        String responseId;
        String prompt;
        String size;
        String quality;
        String referenceJson;
        int attempt = 1;
        long startedAt;

        static Job fromIntent(Intent intent) {
            if (intent == null) return null;
            Job job = new Job();
            job.conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID);
            job.responseId = intent.getStringExtra(EXTRA_RESPONSE_ID);
            job.prompt = intent.getStringExtra("prompt");
            job.size = intent.getStringExtra("size");
            job.quality = intent.getStringExtra("quality");
            job.referenceJson = intent.getStringExtra("reference");
            Job persisted = fromJson(intent.getStringExtra("persisted_job"));
            if (persisted != null) return persisted;
            return job.valid() ? job : null;
        }

        static Job fromJson(String value) {
            if (value == null || value.isEmpty()) return null;
            try {
                JSONObject json = new JSONObject(value);
                Job job = new Job();
                job.conversationId = json.optString("conversation_id");
                job.responseId = json.optString("response_id");
                job.prompt = json.optString("prompt");
                job.size = json.optString("size");
                job.quality = json.optString("quality");
                job.referenceJson = json.optString("reference");
                job.attempt = Math.max(1, json.optInt("attempt", 1));
                job.startedAt = json.optLong("started_at", 0L);
                return job.valid() ? job : null;
            } catch (Exception ignored) {
                return null;
            }
        }

        JSONObject toJson() {
            try {
                return new JSONObject()
                        .put("conversation_id", conversationId)
                        .put("response_id", responseId)
                        .put("prompt", prompt)
                        .put("size", size)
                        .put("quality", quality)
                        .put("reference", referenceJson == null ? "" : referenceJson)
                        .put("attempt", attempt)
                        .put("started_at", startedAt);
            } catch (Exception ignored) {
                return new JSONObject();
            }
        }

        ChatMessage reference() {
            if (referenceJson == null || referenceJson.isEmpty()) return null;
            try {
                return ChatMessage.fromJson(new JSONObject(referenceJson));
            } catch (Exception ignored) {
                return null;
            }
        }

        boolean valid() {
            return conversationId != null && !conversationId.isEmpty()
                    && responseId != null && !responseId.isEmpty()
                    && prompt != null && !prompt.trim().isEmpty();
        }
    }
}
