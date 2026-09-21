package cloud.pcie.openaiq;

final class DeviceAction {
    enum Type {
        OPEN_APP,
        SET_MEDIA_VOLUME,
        ADJUST_MEDIA_VOLUME,
        MEDIA_CONTROL,
        OPEN_SETTINGS
    }

    final Type type;
    final String target;
    final int value;

    private DeviceAction(Type type, String target, int value) {
        this.type = type;
        this.target = target == null ? "" : target;
        this.value = value;
    }

    static DeviceAction openApp(String name) {
        return new DeviceAction(Type.OPEN_APP, name, 0);
    }

    static DeviceAction setVolume(int percent) {
        return new DeviceAction(Type.SET_MEDIA_VOLUME, "", percent);
    }

    static DeviceAction adjustVolume(String direction, int steps) {
        return new DeviceAction(Type.ADJUST_MEDIA_VOLUME, direction, steps);
    }

    static DeviceAction media(String command) {
        return new DeviceAction(Type.MEDIA_CONTROL, command, 0);
    }

    static DeviceAction settings(String panel) {
        return new DeviceAction(Type.OPEN_SETTINGS, panel, 0);
    }
}
