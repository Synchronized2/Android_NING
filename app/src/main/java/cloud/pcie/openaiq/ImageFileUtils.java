package cloud.pcie.openaiq;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

final class ImageFileUtils {
    private ImageFileUtils() {
    }

    static Intent createSaveIntent(String name, String mime) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime == null || mime.isEmpty() ? "image/png" : mime);
        intent.putExtra(Intent.EXTRA_TITLE, safeName(name));
        return intent;
    }

    static void copy(Context context, String sourceUri, Uri destination) throws Exception {
        try (InputStream input = open(context, sourceUri);
             OutputStream output = context.getContentResolver().openOutputStream(destination, "w")) {
            if (input == null || output == null) {
                throw new IllegalStateException("无法打开图片文件");
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            output.flush();
        }
    }

    private static InputStream open(Context context, String sourceUri) throws Exception {
        Uri uri = Uri.parse(sourceUri);
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return new FileInputStream(new File(uri.getPath()));
        }
        return context.getContentResolver().openInputStream(uri);
    }

    private static String safeName(String name) {
        String cleaned = name == null ? "ning-image.png" : name.trim();
        cleaned = cleaned.replaceAll("[\\\\/:*?\"<>|]", "-");
        return cleaned.isEmpty() ? "ning-image.png" : cleaned;
    }
}
