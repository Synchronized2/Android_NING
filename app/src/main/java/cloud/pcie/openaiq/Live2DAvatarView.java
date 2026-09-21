package cloud.pcie.openaiq;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.InputStream;

public final class Live2DAvatarView extends WebView {
    private static final String ASSET_ORIGIN = "https://ning.local/";
    interface Listener {
        void onReady(String modelName);
        void onError(String message);
    }

    private Listener listener;
    private boolean pageLoaded;
    private String pendingState = "idle";
    private boolean pendingSpeaking;
    private String pendingViewMode = "portrait";
    private String modelId = "hiyori";

    public Live2DAvatarView(Context context) {
        this(context, null);
    }

    public Live2DAvatarView(Context context, AttributeSet attributes) {
        super(context, attributes);
        initialize();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void initialize() {
        setBackgroundColor(Color.TRANSPARENT);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);
        WebSettings settings = getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        // The bundled Cubism 2 runtime uses sessionStorage for interaction state.
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        addJavascriptInterface(new AvatarBridge(), "AndroidAvatar");
        setWebChromeClient(new WebChromeClient());
        setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!"ning.local".equals(uri.getHost())) {
                    return null;
                }
                String path = uri.getPath();
                if (path == null || !path.startsWith("/live2d/") || path.contains("..")) {
                    return new WebResourceResponse("text/plain", "UTF-8", null);
                }
                String assetPath = path.substring(1);
                try {
                    InputStream input = getContext().getAssets().open(assetPath);
                    return new WebResourceResponse(mimeType(assetPath), "UTF-8", input);
                } catch (Exception ignored) {
                    return new WebResourceResponse("text/plain", "UTF-8", null);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                applyState();
            }
        });
        loadModelPage();
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setModel(String modelId) {
        String next = modelId == null || modelId.trim().isEmpty() ? "hiyori" : modelId.trim();
        if (next.equals(this.modelId) && pageLoaded) {
            return;
        }
        this.modelId = next;
        pageLoaded = false;
        loadModelPage();
    }

    boolean isReadyForModel(String modelId) {
        return pageLoaded && this.modelId.equals(modelId);
    }

    private void loadModelPage() {
        loadUrl(ASSET_ORIGIN + "live2d/index.html?model=" + Uri.encode(modelId));
    }

    private static String mimeType(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    void setAvatarState(String state) {
        pendingState = state == null || state.isEmpty() ? "idle" : state;
        applyState();
    }

    void setSpeaking(boolean speaking) {
        pendingSpeaking = speaking;
        applyState();
    }

    void setViewMode(boolean fullBody) {
        pendingViewMode = fullBody ? "full" : "portrait";
        applyState();
    }

    void triggerGesture() {
        evaluate("window.avatar&&typeof window.avatar.triggerGesture==='function'"
                + "&&window.avatar.triggerGesture();");
    }

    private void applyState() {
        String escapedState = pendingState.replace("\\", "\\\\").replace("'", "\\'");
        evaluate("window.avatar&&typeof window.avatar.setState==='function'"
                + "&&window.avatar.setState('" + escapedState + "');"
                + "window.avatar&&typeof window.avatar.setSpeaking==='function'"
                + "&&window.avatar.setSpeaking(" + pendingSpeaking + ");"
                + "window.avatar&&typeof window.avatar.setViewMode==='function'"
                + "&&window.avatar.setViewMode('" + pendingViewMode + "');");
    }

    private void evaluate(String script) {
        if (pageLoaded) {
            evaluateJavascript(script, null);
        }
    }

    private final class AvatarBridge {
        @JavascriptInterface
        public void onReady(String modelName) {
            post(() -> {
                if (listener != null) {
                    listener.onReady(modelName);
                }
                applyState();
            });
        }

        @JavascriptInterface
        public void onError(String message) {
            post(() -> {
                if (listener != null) {
                    listener.onError(message);
                }
            });
        }
    }
}
