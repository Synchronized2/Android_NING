package cloud.pcie.openaiq;

import java.util.Locale;

final class DeviceActionPolicy {
    private DeviceActionPolicy() { }

    static boolean isExplicitlyAuthorized(DeviceAction action, String rawText) {
        if (action == null || rawText == null) {
            return false;
        }
        String text = normalize(rawText);
        switch (action.type) {
            case OPEN_APP:
                return containsAny(text, "打开", "启动", "运行", "open", "launch", "start");
            case SET_MEDIA_VOLUME:
            case ADJUST_MEDIA_VOLUME:
                return containsAny(text, "音量", "volume")
                        && containsAny(text, "调", "设置", "设为", "静音", "增", "加", "减", "降",
                        "set", "mute", "raise", "lower", "up", "down");
            case MEDIA_CONTROL:
                return isMediaCommandAuthorized(action.target, text);
            case OPEN_SETTINGS:
                return containsAny(text, "打开", "进入", "开启", "open")
                        && isSettingsPanelAuthorized(action.target, text);
            case NAVIGATE_TO_PLACE:
                return !action.target.isBlank()
                        && containsAny(text, "导航到", "导航去", "带我去", "前往", "navigate")
                        && text.contains(normalize(action.target));
            default:
                return false;
        }
    }

    private static boolean isMediaCommandAuthorized(String command, String text) {
        switch (command) {
            case "play":
                return containsAny(text, "播放", "继续", "play", "resume");
            case "pause":
                return containsAny(text, "暂停", "pause");
            case "toggle":
                return containsAny(text, "播放暂停", "切换播放", "playpause", "toggleplayback");
            case "next":
                return containsAny(text, "下一首", "下一曲", "下首", "切歌", "换歌", "next", "skip");
            case "previous":
                return containsAny(text, "上一首", "上一曲", "上首", "previous", "backtrack");
            case "stop":
                return containsAny(text, "停止播放", "停止音乐", "stopmusic", "stopplayback");
            default:
                return false;
        }
    }

    private static boolean isSettingsPanelAuthorized(String panel, String text) {
        switch (panel) {
            case "wifi":
                return containsAny(text, "wifi", "wlan", "无线网络", "网络设置");
            case "bluetooth":
                return containsAny(text, "蓝牙", "bluetooth");
            case "display":
                return containsAny(text, "显示", "亮度", "display", "brightness");
            case "sound":
                return containsAny(text, "声音", "音量", "sound", "volume");
            case "apps":
                return containsAny(text, "应用", "软件", "apps", "applications");
            case "accessibility":
                return containsAny(text, "无障碍", "accessibility");
            case "battery":
                return containsAny(text, "电池", "省电", "battery", "power");
            case "settings":
                return containsAny(text, "设置", "settings");
            default:
                return false;
        }
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(normalize(value))) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，,。.!！?？;；:：]", "");
    }
}
