package cloud.pcie.openaiq;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class DeviceActionExecutor {
    static final class Result {
        final boolean success;
        final String message;

        Result(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    private static final Map<String, String> APP_ALIASES = createAppAliases();

    private final Activity activity;
    private final PackageManager packageManager;
    private final AudioManager audioManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    DeviceActionExecutor(Activity activity) {
        this.activity = activity;
        packageManager = activity.getPackageManager();
        audioManager = activity.getSystemService(AudioManager.class);
    }

    DeviceAction fromToolCall(OpenAiClient.ToolCall call) {
        if (call == null || call.name.isEmpty()) {
            return null;
        }
        try {
            JSONObject arguments = new JSONObject(call.arguments.isEmpty() ? "{}" : call.arguments);
            switch (call.name) {
                case "open_app":
                    String appName = arguments.optString("app_name").trim();
                    return isSafeLabel(appName) ? DeviceAction.openApp(appName) : null;
                case "set_media_volume":
                    int percent = arguments.optInt("percent", -1);
                    return percent >= 0 && percent <= 100 ? DeviceAction.setVolume(percent) : null;
                case "adjust_media_volume":
                    String direction = arguments.optString("direction");
                    int steps = arguments.optInt("steps", 2);
                    return ("up".equals(direction) || "down".equals(direction))
                            && steps >= 1 && steps <= 5
                            ? DeviceAction.adjustVolume(direction, steps)
                            : null;
                case "media_control":
                    String command = arguments.optString("action");
                    return isOneOf(command, "play", "pause", "toggle", "next", "previous", "stop")
                            ? DeviceAction.media(command)
                            : null;
                case "open_system_settings":
                    String panel = arguments.optString("panel");
                    return isOneOf(panel, "settings", "wifi", "bluetooth", "display", "sound",
                            "apps", "accessibility", "battery")
                            ? DeviceAction.settings(panel)
                            : null;
                default:
                    return null;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    Result execute(DeviceAction action) {
        if (action == null) {
            return new Result(false, "未执行：模型返回了不受支持的设备操作。");
        }
        switch (action.type) {
            case OPEN_APP:
                return openApp(action.target);
            case SET_MEDIA_VOLUME:
                return setMediaVolume(action.value);
            case ADJUST_MEDIA_VOLUME:
                return adjustMediaVolume(action.target, action.value);
            case MEDIA_CONTROL:
                return controlMedia(action.target);
            case OPEN_SETTINGS:
                return openSettings(action.target);
            default:
                return new Result(false, "未执行：未知设备操作。");
        }
    }

    private Result openApp(String requestedName) {
        if (!isSafeLabel(requestedName)) {
            return new Result(false, "未打开应用：应用名称无效。");
        }
        String normalizedTarget = normalizeAppName(requestedName);
        String aliasPackage = APP_ALIASES.get(normalizedTarget);
        if (aliasPackage != null) {
            Intent launch = packageManager.getLaunchIntentForPackage(aliasPackage);
            if (launch != null) {
                return launchApp(launch, requestedName);
            }
        }

        Intent launcherQuery = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> activities = packageManager.queryIntentActivities(
                launcherQuery,
                PackageManager.MATCH_ALL);
        List<ResolveInfo> exact = new ArrayList<>();
        List<ResolveInfo> partial = new ArrayList<>();
        for (ResolveInfo info : activities) {
            if (info.activityInfo == null
                    || activity.getPackageName().equals(info.activityInfo.packageName)) {
                continue;
            }
            String label = String.valueOf(info.loadLabel(packageManager));
            String normalizedLabel = normalizeAppName(label);
            if (normalizedLabel.equals(normalizedTarget)) {
                exact.add(info);
            } else if (normalizedTarget.length() >= 2
                    && normalizedLabel.contains(normalizedTarget)) {
                partial.add(info);
            }
        }
        List<ResolveInfo> matches = exact.isEmpty() ? partial : exact;
        if (matches.size() == 1) {
            ResolveInfo match = matches.get(0);
            Intent launch = packageManager.getLaunchIntentForPackage(match.activityInfo.packageName);
            return launch == null
                    ? new Result(false, "未找到“" + requestedName + "”的可启动页面。")
                    : launchApp(launch, String.valueOf(match.loadLabel(packageManager)));
        }
        if (matches.size() > 1) {
            return new Result(false, "找到多个相似应用，请使用桌面显示的完整应用名称。");
        }
        return new Result(false, "未找到已安装应用“" + requestedName + "”。");
    }

    private Result launchApp(Intent launch, String displayName) {
        try {
            activity.startActivity(launch);
            return new Result(true, "已打开“" + displayName + "”。");
        } catch (Exception exception) {
            return new Result(false, "无法打开“" + displayName + "”。");
        }
    }

    private Result setMediaVolume(int percent) {
        if (audioManager == null || percent < 0 || percent > 100) {
            return new Result(false, "未调节音量：参数无效。");
        }
        int maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int value = Math.round(maximum * percent / 100f);
        audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                value,
                AudioManager.FLAG_SHOW_UI);
        int actual = maximum == 0 ? 0 : Math.round(
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / maximum);
        return new Result(true, "媒体音量已调到 " + actual + "% 。");
    }

    private Result adjustMediaVolume(String direction, int steps) {
        if (audioManager == null || steps < 1 || steps > 5) {
            return new Result(false, "未调节音量：参数无效。");
        }
        int adjustment = "up".equals(direction)
                ? AudioManager.ADJUST_RAISE
                : AudioManager.ADJUST_LOWER;
        for (int index = 0; index < steps; index++) {
            audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    adjustment,
                    index == steps - 1 ? AudioManager.FLAG_SHOW_UI : 0);
        }
        int maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int actual = maximum == 0 ? 0 : Math.round(
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / maximum);
        return new Result(true, "媒体音量已" + ("up".equals(direction) ? "调高" : "调低")
                + "到约 " + actual + "% 。");
    }

    private Result controlMedia(String command) {
        int keyCode;
        switch (command) {
            case "play":
                keyCode = KeyEvent.KEYCODE_MEDIA_PLAY;
                break;
            case "pause":
                keyCode = KeyEvent.KEYCODE_MEDIA_PAUSE;
                break;
            case "toggle":
                keyCode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
                break;
            case "next":
                keyCode = KeyEvent.KEYCODE_MEDIA_NEXT;
                break;
            case "previous":
                keyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
                break;
            case "stop":
                keyCode = KeyEvent.KEYCODE_MEDIA_STOP;
                break;
            default:
                return new Result(false, "未执行：不支持的媒体控制。");
        }

        boolean openedMusicApp = false;
        if ("play".equals(command) && audioManager != null && !audioManager.isMusicActive()) {
            openedMusicApp = openDefaultMusicApp();
        }
        if (openedMusicApp) {
            handler.postDelayed(() -> dispatchMediaKey(keyCode), 700L);
        } else {
            dispatchMediaKey(keyCode);
        }
        return new Result(true, mediaResultText(command, openedMusicApp));
    }

    private boolean openDefaultMusicApp() {
        Intent selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC);
        try {
            if (selector.resolveActivity(packageManager) == null) {
                return false;
            }
            activity.startActivity(selector);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void dispatchMediaKey(int keyCode) {
        if (audioManager == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    private String mediaResultText(String command, boolean openedMusicApp) {
        switch (command) {
            case "play":
                return openedMusicApp ? "已打开音乐应用并发送播放命令。" : "已发送播放命令。";
            case "pause":
                return "已发送暂停命令。";
            case "toggle":
                return "已切换播放/暂停状态。";
            case "next":
                return "已发送下一首命令。";
            case "previous":
                return "已发送上一首命令。";
            case "stop":
                return "已发送停止播放命令。";
            default:
                return "媒体控制命令已发送。";
        }
    }

    private Result openSettings(String panel) {
        String action;
        String label;
        switch (panel) {
            case "wifi":
                action = Settings.ACTION_WIFI_SETTINGS;
                label = "WLAN 设置";
                break;
            case "bluetooth":
                action = Settings.ACTION_BLUETOOTH_SETTINGS;
                label = "蓝牙设置";
                break;
            case "display":
                action = Settings.ACTION_DISPLAY_SETTINGS;
                label = "显示设置";
                break;
            case "sound":
                action = Settings.ACTION_SOUND_SETTINGS;
                label = "声音设置";
                break;
            case "apps":
                action = Settings.ACTION_APPLICATION_SETTINGS;
                label = "应用设置";
                break;
            case "accessibility":
                action = Settings.ACTION_ACCESSIBILITY_SETTINGS;
                label = "无障碍设置";
                break;
            case "battery":
                action = Settings.ACTION_BATTERY_SAVER_SETTINGS;
                label = "电池设置";
                break;
            case "settings":
                action = Settings.ACTION_SETTINGS;
                label = "系统设置";
                break;
            default:
                return new Result(false, "未执行：不支持的设置页面。");
        }
        try {
            activity.startActivity(new Intent(action));
            String suffix = ("wifi".equals(panel) || "bluetooth".equals(panel))
                    ? "，请在系统页面中切换开关。"
                    : "。";
            return new Result(true, "已打开" + label + suffix);
        } catch (Exception exception) {
            return new Result(false, "无法打开" + label + "。");
        }
    }

    private static boolean isSafeLabel(String value) {
        return value != null && !value.isBlank() && value.length() <= 30
                && !value.contains(":") && !value.contains("/") && !value.contains("\\")
                && !value.contains(".");
    }

    private static String normalizeAppName(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("应用程序", "")
                .replace("客户端", "")
                .replace("应用", "")
                .replace("软件", "")
                .replace("app", "")
                .trim();
    }

    private static boolean isOneOf(String value, String... allowed) {
        for (String item : allowed) {
            if (item.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> createAppAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("微信", "com.tencent.mm");
        aliases.put("wechat", "com.tencent.mm");
        aliases.put("qq", "com.tencent.mobileqq");
        aliases.put("支付宝", "com.eg.android.AlipayGphone");
        aliases.put("淘宝", "com.taobao.taobao");
        aliases.put("京东", "com.jingdong.app.mall");
        aliases.put("抖音", "com.ss.android.ugc.aweme");
        aliases.put("快手", "com.smile.gifmaker");
        aliases.put("小红书", "com.xingin.xhs");
        aliases.put("微博", "com.sina.weibo");
        aliases.put("哔哩哔哩", "tv.danmaku.bili");
        aliases.put("b站", "tv.danmaku.bili");
        aliases.put("高德地图", "com.autonavi.minimap");
        aliases.put("百度地图", "com.baidu.BaiduMap");
        aliases.put("网易云音乐", "com.netease.cloudmusic");
        aliases.put("neteasecloudmusic", "com.netease.cloudmusic");
        aliases.put("qq音乐", "com.tencent.qqmusic");
        aliases.put("qqmusic", "com.tencent.qqmusic");
        aliases.put("酷狗音乐", "com.kugou.android");
        aliases.put("kugoumusic", "com.kugou.android");
        aliases.put("bilibili", "tv.danmaku.bili");
        aliases.put("chrome", "com.android.chrome");
        return aliases;
    }
}
