package cloud.pcie.openaiq;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LocalIntentParser {
    private static final Pattern SET_VOLUME = Pattern.compile(
            "^(?:请|请你|帮我|麻烦)?(?:把|将)?(?:媒体)?音量(?:调到|设置为|设为|调整到)\\s*(\\d{1,3})\\s*%?[。！!]?$");
    private static final Pattern OPEN_APP = Pattern.compile(
            "^(?:请|请你|帮我|麻烦)?(?:打开|启动|运行)(?:一下)?\\s*(.{1,30}?)[。！!]?$");
    private static final Pattern SET_VOLUME_EN = Pattern.compile(
            "^(?:set\\s+)?(?:media\\s+)?volume(?:\\s+to)?\\s+(\\d{1,3})\\s*%?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OPEN_APP_EN = Pattern.compile(
            "^(?:open|launch|start)\\s+(.{1,30})$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NAVIGATE_TO = Pattern.compile(
            "^(?:请|请你|麻烦)?(?:用|使用)?(?:百度地图)?(?:帮我)?"
                    + "(?:导航到|导航去|带我去|前往)\\s*(.{1,80}?)[。！!]?$" );

    private LocalIntentParser() { }

    static DeviceAction parse(String rawText) {
        if (rawText == null) {
            return null;
        }
        String text = rawText.trim().replaceAll("\\s+", " ");
        if (text.isEmpty() || containsMultipleIntentJoiner(text)) {
            return null;
        }

        Matcher volume = SET_VOLUME.matcher(text);
        if (volume.matches()) {
            int percent = Integer.parseInt(volume.group(1));
            return percent <= 100 ? DeviceAction.setVolume(percent) : null;
        }
        Matcher volumeEn = SET_VOLUME_EN.matcher(text);
        if (volumeEn.matches()) {
            int percent = Integer.parseInt(volumeEn.group(1));
            return percent <= 100 ? DeviceAction.setVolume(percent) : null;
        }
        if (matches(text, "静音", "关闭音量", "把音量关掉", "媒体静音")) {
            return DeviceAction.setVolume(0);
        }
        if (containsCommand(text, "调高音量", "增大音量", "加大音量", "提高音量", "音量调高", "音量加大",
                "volume up", "raise volume")) {
            return DeviceAction.adjustVolume("up", 2);
        }
        if (containsCommand(text, "调低音量", "减小音量", "降低音量", "音量调低", "音量减小",
                "volume down", "lower volume")) {
            return DeviceAction.adjustVolume("down", 2);
        }

        if (matches(text, "播放音乐", "继续播放", "继续播放音乐", "play music", "resume music")) {
            return DeviceAction.media("play");
        }
        if (matches(text, "暂停", "暂停播放", "暂停音乐", "停一下", "pause", "pause music")) {
            return DeviceAction.media("pause");
        }
        if (matches(text, "播放或暂停", "切换播放", "播放暂停", "play pause", "toggle playback")) {
            return DeviceAction.media("toggle");
        }
        if (matches(text,
                "下一首", "播放下一首", "切到下一首",
                "下一曲", "播放下一曲", "切到下一曲",
                "下一首歌", "播放下一首歌", "切歌", "换下一首",
                "next", "next track")) {
            return DeviceAction.media("next");
        }
        if (matches(text,
                "上一首", "播放上一首", "切到上一首",
                "上一曲", "播放上一曲", "切到上一曲",
                "上一首歌", "播放上一首歌", "换上一首",
                "previous", "previous track")) {
            return DeviceAction.media("previous");
        }
        if (matches(text, "停止播放", "停止音乐", "stop music", "stop playback")) {
            return DeviceAction.media("stop");
        }

        Matcher navigation = NAVIGATE_TO.matcher(text);
        if (navigation.matches()) {
            String destination = cleanDestination(navigation.group(1));
            return destination.isEmpty() ? null : DeviceAction.navigateTo(destination);
        }

        DeviceAction settings = parseSettings(text);
        if (settings != null) {
            return settings;
        }

        Matcher open = OPEN_APP.matcher(text);
        if (open.matches()) {
            String target = cleanAppName(open.group(1));
            return target.isEmpty() ? null : DeviceAction.openApp(target);
        }
        Matcher openEn = OPEN_APP_EN.matcher(text);
        if (openEn.matches()) {
            String target = cleanAppName(openEn.group(1));
            return target.isEmpty() ? null : DeviceAction.openApp(target);
        }
        return null;
    }

    private static DeviceAction parseSettings(String text) {
        String normalized = normalize(text);
        if (!normalized.matches("^(打开|进入|开启|open).*$")) {
            return null;
        }
        if (normalized.contains("wifi") || normalized.contains("wlan")
                || normalized.contains("无线网络")) {
            return DeviceAction.settings("wifi");
        }
        if (normalized.contains("蓝牙")) {
            return DeviceAction.settings("bluetooth");
        }
        if (normalized.contains("显示设置") || normalized.contains("亮度设置")) {
            return DeviceAction.settings("display");
        }
        if (normalized.contains("声音设置") || normalized.contains("音量设置")) {
            return DeviceAction.settings("sound");
        }
        if (normalized.contains("应用设置") || normalized.contains("应用管理")) {
            return DeviceAction.settings("apps");
        }
        if (normalized.contains("无障碍")) {
            return DeviceAction.settings("accessibility");
        }
        if (normalized.contains("电池设置") || normalized.contains("省电设置")) {
            return DeviceAction.settings("battery");
        }
        if (normalized.matches("^(打开|进入)(系统)?设置$") || normalized.equals("opensettings")) {
            return DeviceAction.settings("settings");
        }
        return null;
    }

    private static String cleanAppName(String value) {
        String cleaned = value.trim()
                .replaceFirst("^(手机上的|手机里?的|本机的)", "")
                .replaceFirst("(应用程序|客户端|应用|软件|app)$", "")
                .trim();
        return cleaned.length() <= 20 ? cleaned : "";
    }

    private static String cleanDestination(String value) {
        String cleaned = value.trim()
                .replaceFirst("^(?:一下|这个)?", "")
                .replaceFirst("(?:怎么走|的路线)$", "")
                .trim();
        return cleaned.length() <= 80 ? cleaned : "";
    }

    private static boolean containsMultipleIntentJoiner(String text) {
        return text.contains("，") || text.contains(",") || text.contains("；")
                || text.contains(";") || text.contains("然后") || text.contains("并且")
                || text.contains("并帮") || text.contains("再帮") || text.contains("顺便");
    }

    private static boolean containsCommand(String text, String... commands) {
        String normalized = normalize(text)
                .replace("请", "")
                .replace("帮我", "")
                .replace("一下", "")
                .replace("一点", "")
                .replace("一些", "");
        for (String command : commands) {
            if (normalized.equals(normalize(command))) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String text, String... values) {
        String normalized = normalize(text);
        for (String value : values) {
            if (normalized.equals(normalize(value))) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("。", "")
                .replace("！", "")
                .replace("!", "");
    }
}
