package cloud.pcie.openaiq;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

final class EdgeTtsVersionProvider {
    private static final String TAG = "EdgeTtsVersion";
    private static final String UPDATE_URL =
            "https://edgeupdates.microsoft.com/api/products?view=enterprise";
    private static final String PREFS = "edge_tts_versions";
    private static final String KEY_BROWSER_VERSION = "browser_version";
    private static final String KEY_FETCHED_AT = "fetched_at";
    private static final long CACHE_TTL_MS = 24L * 60L * 60L * 1000L;
    static final String FALLBACK_BROWSER_VERSION = "156.0.4314.42";

    interface Listener {
        void onSuccess(VersionInfo version);
        void onError(String message);
    }

    static final class VersionInfo {
        final String browserVersion;
        final String gecVersion;

        VersionInfo(String browserVersion) {
            this.browserVersion = browserVersion;
            this.gecVersion = toGecVersion(browserVersion);
        }
    }

    private final SharedPreferences preferences;
    private final OkHttpClient httpClient;
    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);
    private final Object listenerLock = new Object();
    private final List<Listener> pendingListeners = new ArrayList<>();

    EdgeTtsVersionProvider(Context context, OkHttpClient httpClient) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.httpClient = httpClient;
    }

    VersionInfo current() {
        String cached = preferences.getString(KEY_BROWSER_VERSION, "");
        return new VersionInfo(isValidBrowserVersion(cached)
                ? cached
                : FALLBACK_BROWSER_VERSION);
    }

    void refreshIfNeeded() {
        long fetchedAt = preferences.getLong(KEY_FETCHED_AT, 0L);
        if (isCacheFresh(fetchedAt, System.currentTimeMillis())) {
            return;
        }
        refresh(new Listener() {
            @Override
            public void onSuccess(VersionInfo version) {
                Log.d(TAG, "Edge Stable version refreshed: " + version.browserVersion);
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "Edge Stable version refresh failed: " + message);
            }
        });
    }

    void forceRefresh(Listener listener) {
        refresh(listener);
    }

    private void refresh(Listener listener) {
        synchronized (listenerLock) {
            pendingListeners.add(listener);
            if (!refreshInFlight.compareAndSet(false, true)) {
                return;
            }
        }
        Request request = new Request.Builder()
                .url(UPDATE_URL)
                .header("Accept", "application/json")
                .build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, java.io.IOException exception) {
                finishError(readableError(exception));
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        finishError("HTTP " + response.code());
                        return;
                    }
                    String latest = parseLatestStableVersion(response.body().string());
                    if (latest.isEmpty()) {
                        finishError("响应中没有 Stable Windows x64 版本");
                        return;
                    }
                    preferences.edit()
                            .putString(KEY_BROWSER_VERSION, latest)
                            .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                            .apply();
                    finishSuccess(new VersionInfo(latest));
                } catch (Exception exception) {
                    finishError("解析版本失败：" + readableError(exception));
                }
            }
        });
    }

    private void finishSuccess(VersionInfo version) {
        List<Listener> listeners = takePendingListeners();
        for (Listener listener : listeners) {
            listener.onSuccess(version);
        }
    }

    private void finishError(String message) {
        List<Listener> listeners = takePendingListeners();
        for (Listener listener : listeners) {
            listener.onError(message);
        }
    }

    private List<Listener> takePendingListeners() {
        synchronized (listenerLock) {
            ArrayList<Listener> listeners = new ArrayList<>(pendingListeners);
            pendingListeners.clear();
            refreshInFlight.set(false);
            return listeners;
        }
    }

    static String parseLatestStableVersion(String json) throws Exception {
        JSONArray products = new JSONArray(json);
        String latest = "";
        for (int productIndex = 0; productIndex < products.length(); productIndex++) {
            JSONObject product = products.optJSONObject(productIndex);
            if (product == null || !"Stable".equalsIgnoreCase(product.optString("Product"))) {
                continue;
            }
            JSONArray releases = product.optJSONArray("Releases");
            if (releases == null) {
                continue;
            }
            for (int releaseIndex = 0; releaseIndex < releases.length(); releaseIndex++) {
                JSONObject release = releases.optJSONObject(releaseIndex);
                if (release == null
                        || !"Windows".equalsIgnoreCase(release.optString("Platform"))
                        || !"x64".equalsIgnoreCase(release.optString("Architecture"))) {
                    continue;
                }
                String candidate = release.optString("ProductVersion").trim();
                if (isValidBrowserVersion(candidate)
                        && (latest.isEmpty() || compareVersions(candidate, latest) > 0)) {
                    latest = candidate;
                }
            }
        }
        return latest;
    }

    static boolean isCacheFresh(long fetchedAt, long now) {
        return fetchedAt > 0L && now >= fetchedAt && now - fetchedAt < CACHE_TTL_MS;
    }

    static boolean isValidBrowserVersion(String version) {
        if (version == null || !version.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            return false;
        }
        for (String part : version.split("\\.")) {
            try {
                Integer.parseInt(part);
            } catch (NumberFormatException exception) {
                return false;
            }
        }
        return true;
    }

    static String toGecVersion(String browserVersion) {
        String safeVersion = isValidBrowserVersion(browserVersion)
                ? browserVersion
                : FALLBACK_BROWSER_VERSION;
        return "2-" + safeVersion;
    }

    static int compareVersions(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        for (int index = 0; index < Math.max(leftParts.length, rightParts.length); index++) {
            int leftPart = index < leftParts.length ? Integer.parseInt(leftParts[index]) : 0;
            int rightPart = index < rightParts.length ? Integer.parseInt(rightParts[index]) : 0;
            if (leftPart != rightPart) {
                return Integer.compare(leftPart, rightPart);
            }
        }
        return 0;
    }

    private static String readableError(Throwable throwable) {
        String message = throwable == null ? "" : throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "网络连接失败";
        }
        String clean = message.trim();
        return clean.length() <= 160 ? clean : clean.substring(0, 160);
    }
}
