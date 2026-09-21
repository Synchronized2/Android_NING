package cloud.pcie.openaiq;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class AvatarCatalog {
    static final class Avatar {
        final String id;
        final String name;
        final String family;
        final String preview;

        Avatar(String id, String name, String family, String preview) {
            this.id = id;
            this.name = name;
            this.family = family;
            this.preview = preview;
        }
    }

    private static List<Avatar> cached;

    static synchronized List<Avatar> load(Context context) {
        if (cached != null) {
            return cached;
        }
        ArrayList<Avatar> result = new ArrayList<>();
        try (InputStream input = context.getAssets().open("live2d/catalog.json")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            JSONArray array = new JSONArray(output.toString(StandardCharsets.UTF_8.name()));
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                String id = item.optString("id").trim();
                String name = item.optString("name").trim();
                String preview = item.optString("preview").trim();
                if (!id.isEmpty() && !name.isEmpty() && !preview.isEmpty()) {
                    result.add(new Avatar(id, name, item.optString("family"), preview));
                }
            }
        } catch (Exception ignored) {
            result.clear();
        }
        cached = Collections.unmodifiableList(result);
        return cached;
    }

    static Avatar find(Context context, String id) {
        for (Avatar avatar : load(context)) {
            if (avatar.id.equals(id)) {
                return avatar;
            }
        }
        List<Avatar> avatars = load(context);
        return avatars.isEmpty() ? null : avatars.get(0);
    }

    private AvatarCatalog() { }
}
