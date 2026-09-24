package cloud.pcie.openaiq;

import android.content.Context;

final class DesignScale {
    private static final float DESIGN_WIDTH = 1080f;

    private DesignScale() {
    }

    static float factor(Context context) {
        return context.getResources().getDisplayMetrics().widthPixels / DESIGN_WIDTH;
    }

    static int px(Context context, float designPixels) {
        return Math.max(1, Math.round(designPixels * factor(context)));
    }

    // 2.3.23 was approved on the 1264px / 3x-density phone. Preserve those
    // proportions across phone resolutions without scaling dp by density twice.
    static int referenceDp(Context context, float dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().widthPixels * 3f / 1264f);
    }

    static float referenceSp(Context context, float sp) {
        return referenceDp(context, sp) * context.getResources().getConfiguration().fontScale;
    }
}
