package cloud.pcie.openaiq;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.WindowManager;
import android.widget.ListView;
import org.json.JSONObject;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** No API calls, chat writes or avatar selection changes. */
final class AvatarAnimationAudit {
    private final Instrumentation test;
    AvatarAnimationAudit(Instrumentation test) { this.test = test; }

    void run() throws Exception {
        String selected = AppSettings.load(test.getTargetContext()).avatarId;
        boolean originalFull = AppSettings.loadAvatarFullBody(test.getTargetContext());
        List<AvatarCatalog.Avatar> catalog = AvatarCatalog.load(test.getTargetContext());
        try {
            for (int i = 0; i < catalog.size(); i++) {
                AvatarCatalog.Avatar avatar = catalog.get(i);
                if (!avatar.id.equals(selected) && !avatar.name.equals("Ping Hai")
                        && !avatar.name.equals("Wanko") && !avatar.name.equals("miku")) continue;
                Activity screen = test.startActivitySync(new Intent(test.getTargetContext(), AvatarLibraryActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                try {
                    int position = i;
                    test.runOnMainSync(() -> {
                        screen.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        ListView list = screen.findViewById(R.id.avatarList);
                        list.performItemClick(null, position, list.getItemIdAtPosition(position));
                    });
                    Live2DAvatarView view = screen.findViewById(R.id.avatarLibraryPreview);
                    long deadline = SystemClock.uptimeMillis() + 30000;
                    while (true) {
                        String value = js(view, "window.avatar ? window.avatar.diagnostics().animationFrames : 0");
                        if (!value.equals("null") && Integer.parseInt(value) > 5) break;
                        if (SystemClock.uptimeMillis() > deadline) throw new AssertionError("Model not animating: " + avatar.name);
                        SystemClock.sleep(200);
                    }
                    js(view, "window.__refits=0;window.__oldMode=window.avatar.setViewMode;"
                            + "window.avatar.setViewMode=function(v){window.__refits++;window.__oldMode(v)};true");
                    for (String phase : new String[]{"idle", "thinking", "answering", "idle"}) {
                        test.runOnMainSync(() -> {
                            for (int repeat=0; repeat<100; repeat++) {
                                view.setAvatarState(phase);
                                view.setSpeaking(phase.equals("answering"));
                            }
                        });
                        JSONObject before = new JSONObject(js(view, "window.avatar.diagnostics()"));
                        SystemClock.sleep(1400);
                        JSONObject after = new JSONObject(js(view, "window.avatar.diagnostics()"));
                        int frames = after.getInt("animationFrames") - before.getInt("animationFrames");
                        if (!after.getString("phase").equals(phase) || frames < 5)
                            throw new AssertionError(avatar.name + " frozen in " + phase + ": " + frames);
                        if (!js(view, "window.__refits").equals("0"))
                            throw new AssertionError("Animation state triggered viewport refit");
                        Bundle status = new Bundle();
                        status.putString("animation", avatar.name + " " + phase + ": " + frames + " frames / 1400ms");
                        test.sendStatus(0, status);
                    }
                } finally { test.runOnMainSync(screen::finish); }
            }
        } finally { test.runOnMainSync(() -> AppSettings.saveAvatarFullBody(test.getTargetContext(), originalFull)); }
    }

    private String js(Live2DAvatarView view, String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        String[] result = {"null"};
        test.runOnMainSync(() -> view.evaluateJavascript(script, value -> {result[0]=value; done.countDown();}));
        if (!done.await(5, TimeUnit.SECONDS)) throw new AssertionError("WebView stopped responding");
        return result[0];
    }
}
