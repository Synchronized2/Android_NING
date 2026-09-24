package cloud.pcie.openaiq;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.ListView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Loads each model in the real preview WebView without changing the selected avatar. */
final class AvatarAudit {
    private final Instrumentation test;
    AvatarAudit(Instrumentation test) { this.test = test; }
    void run(Bundle args) throws Exception {
        int start = Integer.parseInt(args.getString("from", "0"));
        int end = Integer.parseInt(args.getString("to", "9999"));
        boolean fullBefore = AppSettings.loadAvatarFullBody(test.getTargetContext());
        List<AvatarCatalog.Avatar> catalog = AvatarCatalog.load(test.getTargetContext());
        JSONArray report = new JSONArray();
        File folder = new File(test.getTargetContext().getExternalFilesDir(null), "avatar-audit");
        folder.mkdirs();
        try {
            for (int i = start; i < Math.min(end, catalog.size()); i++) {
                AvatarCatalog.Avatar avatar = catalog.get(i);
                Activity screen = test.startActivitySync(new Intent(test.getTargetContext(), AvatarLibraryActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                JSONObject item = new JSONObject().put("index",i).put("id",avatar.id).put("name",avatar.name);
                try {
                    final int position = i;
                    test.runOnMainSync(() -> {
                        screen.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        ListView list = screen.findViewById(R.id.avatarList);
                        list.performItemClick(null, position, list.getItemIdAtPosition(position));
                    });
                    long deadline = SystemClock.uptimeMillis() + 15000;
                    boolean[] ready = {false};
                    do {
                        test.runOnMainSync(() -> ready[0] = screen.findViewById(R.id.libraryViewToggleButton).isEnabled());
                        if (!ready[0]) SystemClock.sleep(150);
                    } while (!ready[0] && SystemClock.uptimeMillis()<deadline);
                    item.put("ready",ready[0]);
                    for (boolean full : new boolean[]{true,false}) {
                        test.runOnMainSync(() -> screen.findViewById(full ? R.id.libraryViewToggleButton : R.id.libraryPortraitButton).performClick());
                        SystemClock.sleep(650);
                        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                        String[] diagnostic = {"null"};
                        test.runOnMainSync(() -> ((Live2DAvatarView)screen.findViewById(R.id.avatarLibraryPreview))
                                .evaluateJavascript("window.avatar&&window.avatar.diagnostics?window.avatar.diagnostics():null", value -> {
                                    diagnostic[0] = value; latch.countDown();
                                }));
                        latch.await(2, java.util.concurrent.TimeUnit.SECONDS);
                        item.put(full ? "fullGeometry" : "portraitGeometry", diagnostic[0]);
                        Bitmap shot = test.getUiAutomation().takeScreenshot();
                        int[] location = new int[2], size = new int[2];
                        test.runOnMainSync(() -> {
                            View stage = screen.findViewById(R.id.avatarPreviewStage);
                            stage.getLocationOnScreen(location); size[0]=stage.getWidth(); size[1]=stage.getHeight();
                        });
                        Bitmap crop=Bitmap.createBitmap(shot,location[0],location[1],size[0],size[1]);
                        Bitmap thumb=Bitmap.createScaledBitmap(crop,400,Math.round(400f*size[1]/size[0]),true);
                        try(FileOutputStream output = new FileOutputStream(new File(folder,String.format(java.util.Locale.ROOT,"%03d-%s.jpg",i,full?"full":"portrait")))) {
                            thumb.compress(Bitmap.CompressFormat.JPEG,88,output);
                        }
                        thumb.recycle(); crop.recycle(); shot.recycle();
                    }
                } catch(Throwable error) { item.put("error",error.toString()); }
                finally { test.runOnMainSync(screen::finish); }
                report.put(item);
                try(FileOutputStream output=new FileOutputStream(new File(folder,"report-"+start+".json"))) {
                    output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
                }
                Bundle status=new Bundle(); status.putString("model",(i+1)+"/"+catalog.size()+" "+avatar.name+" ready="+item.optBoolean("ready"));
                test.sendStatus(0,status);
            }
        } finally { test.runOnMainSync(() -> AppSettings.saveAvatarFullBody(test.getTargetContext(),fullBefore)); }
    }
}
