package cloud.pcie.openaiq;

public final class LocalIntentParserSelfTest {
    public static void main(String[] args) {
        expect(DeviceAction.Type.OPEN_APP, "微信", LocalIntentParser.parse("帮我打开微信"));
        expect(DeviceAction.Type.OPEN_APP, "哔哩哔哩", LocalIntentParser.parse("打开手机上的哔哩哔哩应用"));
        expect(DeviceAction.Type.SET_MEDIA_VOLUME, "", LocalIntentParser.parse("把音量调到 40%"));
        expectValue(40, LocalIntentParser.parse("把音量调到 40%"));
        expect(DeviceAction.Type.ADJUST_MEDIA_VOLUME, "down", LocalIntentParser.parse("音量调低一点"));
        expect(DeviceAction.Type.MEDIA_CONTROL, "next", LocalIntentParser.parse("下一首"));
        expect(DeviceAction.Type.OPEN_SETTINGS, "bluetooth", LocalIntentParser.parse("打开蓝牙"));
        expectValue(40, LocalIntentParser.parse("set volume to 40%"));
        expect(DeviceAction.Type.OPEN_SETTINGS, "settings", LocalIntentParser.parse("open settings"));
        expect(DeviceAction.Type.MEDIA_CONTROL, "next", LocalIntentParser.parse("next track"));
        reject("微信是什么");
        reject("打开微信，然后告诉我天气");
        reject("把音量调到 120% ");
        require(!DeviceActionPolicy.isExplicitlyAuthorized(
                DeviceAction.openApp("照片"),
                "我要看照片"));
        require(DeviceActionPolicy.isExplicitlyAuthorized(
                DeviceAction.openApp("QQ音乐"),
                "open qq music"));
        require(DeviceActionPolicy.isExplicitlyAuthorized(
                DeviceAction.setVolume(33),
                "set volume to 33"));
        System.out.println("Local intent parser self-test: PASS");
    }

    private static void expect(DeviceAction.Type type, String target, DeviceAction action) {
        require(action != null && action.type == type && target.equals(action.target));
    }

    private static void expectValue(int value, DeviceAction action) {
        require(action != null && action.value == value);
    }

    private static void reject(String text) {
        require(LocalIntentParser.parse(text) == null);
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new AssertionError("Local intent parser assertion failed");
        }
    }
}
