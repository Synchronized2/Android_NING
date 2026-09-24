package cloud.pcie.openaiq;

/** Immutable request choices: retained on the response so retry repeats the same job. */
final class ImageGenerationOptions {
    static final String[] RATIOS = {"1:1", "3:4", "9:16", "16:9"};
    static final String[] SIZES = {"1024x1024", "960x1280", "864x1536", "1536x864"};
    static final String[] STYLES = {"默认", "二次元", "写实", "科幻"};
    static final String[] QUALITIES = {"low", "medium", "high"};
    final String size;
    final String quality;

    ImageGenerationOptions(String size, String quality) {
        this.size = size;
        this.quality = quality;
    }

    static String prompt(String description, int ratio, int style) {
        String result = description.trim() + "\n画面宽高比：" + RATIOS[ratio] + "。";
        if (style > 0) result += "\n视觉风格：" + STYLES[style] + "。";
        return result;
    }
}
