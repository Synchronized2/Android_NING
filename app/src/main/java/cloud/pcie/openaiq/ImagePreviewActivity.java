package cloud.pcie.openaiq;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ImagePreviewActivity extends Activity {
    static final String EXTRA_URI = "image_uri";
    static final String EXTRA_MIME = "image_mime";
    static final String EXTRA_NAME = "image_name";
    static final String EXTRA_GALLERY = "image_gallery";
    static final String EXTRA_INDEX = "image_index";
    private static final int REQUEST_SAVE_IMAGE = 71;

    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private final ArrayList<ImageEntry> gallery = new ArrayList<>();
    private ZoomImageView imageView;
    private TextView positionText;
    private Button previousButton;
    private Button nextButton;
    private int currentIndex;
    private String imageUri = "";
    private String imageMime = "image/png";
    private String imageName = "ning-image.png";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_preview);

        readGallery();
        if (gallery.isEmpty()) {
            finish();
            return;
        }

        imageView = findViewById(R.id.fullImageView);
        positionText = findViewById(R.id.imagePositionText);
        previousButton = findViewById(R.id.previousImageButton);
        nextButton = findViewById(R.id.nextImageButton);
        imageView.setSwipeListener(new ZoomImageView.SwipeListener() {
            @Override public void onSwipePrevious() { showImage(currentIndex - 1); }
            @Override public void onSwipeNext() { showImage(currentIndex + 1); }
        });
        previousButton.setOnClickListener(view -> showImage(currentIndex - 1));
        nextButton.setOnClickListener(view -> showImage(currentIndex + 1));
        findViewById(R.id.closePreviewButton).setOnClickListener(view -> finish());
        findViewById(R.id.downloadPreviewButton).setOnClickListener(view -> {
            startActivityForResult(
                    ImageFileUtils.createSaveIntent(imageName, imageMime),
                    REQUEST_SAVE_IMAGE);
        });
        currentIndex = Math.max(0, Math.min(
                getIntent().getIntExtra(EXTRA_INDEX, 0),
                gallery.size() - 1));
        showImage(currentIndex);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SAVE_IMAGE
                || resultCode != RESULT_OK
                || data == null
                || data.getData() == null) {
            return;
        }
        Uri destination = data.getData();
        String source = imageUri;
        fileExecutor.execute(() -> {
            try {
                ImageFileUtils.copy(this, source, destination);
                runOnUiThread(() -> Toast.makeText(
                        this,
                        R.string.image_saved,
                        Toast.LENGTH_SHORT).show());
            } catch (Exception exception) {
                runOnUiThread(() -> Toast.makeText(
                        this,
                        getString(R.string.image_save_failed, exception.getMessage()),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void readGallery() {
        String serialized = getIntent().getStringExtra(EXTRA_GALLERY);
        if (serialized != null && !serialized.isEmpty()) {
            try {
                JSONArray array = new JSONArray(serialized);
                for (int index = 0; index < array.length(); index++) {
                    JSONObject item = array.optJSONObject(index);
                    if (item == null || item.optString("uri").isEmpty()) {
                        continue;
                    }
                    gallery.add(new ImageEntry(
                            item.optString("uri"),
                            item.optString("mime", "image/png"),
                            item.optString("name", "ning-image.png")));
                }
            } catch (Exception ignored) {
                gallery.clear();
            }
        }
        if (gallery.isEmpty()) {
            String uri = getIntent().getStringExtra(EXTRA_URI);
            if (uri != null && !uri.isEmpty()) {
                gallery.add(new ImageEntry(
                        uri,
                        getIntent().getStringExtra(EXTRA_MIME),
                        getIntent().getStringExtra(EXTRA_NAME)));
            }
        }
    }

    private void showImage(int index) {
        if (index < 0 || index >= gallery.size()) {
            return;
        }
        currentIndex = index;
        ImageEntry current = gallery.get(index);
        imageUri = current.uri;
        imageMime = current.mime == null || current.mime.isEmpty() ? "image/png" : current.mime;
        imageName = current.name == null || current.name.isEmpty()
                ? "ning-image.png"
                : current.name;
        imageView.showImageCentered(Uri.parse(imageUri));
        positionText.setText(getString(R.string.image_position, index + 1, gallery.size()));
        previousButton.setEnabled(index > 0);
        nextButton.setEnabled(index < gallery.size() - 1);
        previousButton.setAlpha(index > 0 ? 1f : 0.35f);
        nextButton.setAlpha(index < gallery.size() - 1 ? 1f : 0.35f);
    }

    @Override
    protected void onDestroy() {
        fileExecutor.shutdownNow();
        super.onDestroy();
    }

    private static final class ImageEntry {
        final String uri;
        final String mime;
        final String name;

        ImageEntry(String uri, String mime, String name) {
            this.uri = uri;
            this.mime = mime;
            this.name = name;
        }
    }
}
