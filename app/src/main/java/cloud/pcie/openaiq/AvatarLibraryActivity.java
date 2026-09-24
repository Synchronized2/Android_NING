package cloud.pcie.openaiq;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class AvatarLibraryActivity extends Activity {
    private final List<AvatarCatalog.Avatar> visibleAvatars = new ArrayList<>();
    private List<AvatarCatalog.Avatar> avatars;
    private String selectedId;
    private String focusedId;
    private String selectedFamily;
    private boolean fullBody;
    private boolean selectionChanged;
    private AvatarAdapter adapter;
    private Live2DAvatarView preview;
    private TextView previewTitle;
    private TextView previewStatus;
    private TextView previewMeta;
    private TextView currentLabel;
    private Button useButton;
    private Button viewToggle;
    private Button portraitButton;
    private EditText searchInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_avatar_library);
        avatars = AvatarCatalog.load(this);
        selectedId = AppSettings.load(this).avatarId;
        AvatarCatalog.Avatar selected = AvatarCatalog.find(this, selectedId);
        focusedId = selected == null ? "hiyori" : selected.id;
        fullBody = AppSettings.loadAvatarFullBody(this);

        preview = findViewById(R.id.avatarLibraryPreview);
        previewTitle = findViewById(R.id.avatarLibraryPreviewTitle);
        previewStatus = findViewById(R.id.avatarLibraryPreviewStatus);
        previewMeta = findViewById(R.id.avatarLibraryPreviewMeta);
        currentLabel = findViewById(R.id.avatarCurrentLabel);
        useButton = findViewById(R.id.useAvatarButton);
        viewToggle = findViewById(R.id.libraryViewToggleButton);
        portraitButton = findViewById(R.id.libraryPortraitButton);
        searchInput = findViewById(R.id.avatarSearchInput);
        ListView list = findViewById(R.id.avatarList);
        Spinner familySpinner = findViewById(R.id.avatarFamilySpinner);
        styleLibrary(familySpinner);

        adapter = new AvatarAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) ->
                focusAvatar(visibleAvatars.get(position)));
        preview.setListener(new Live2DAvatarView.Listener() {
            @Override
            public void onReady(String modelName) {
                AvatarCatalog.Avatar focused = focusedAvatar();
                if (focused == null) {
                    return;
                }
                previewStatus.setText(getString(R.string.avatar_preview_ready, focused.name));
                useButton.setEnabled(!focused.id.equals(selectedId));
                setViewModesEnabled(true);
                preview.triggerGesture();
            }

            @Override
            public void onError(String message) {
                previewStatus.setText(R.string.avatar_failed);
                useButton.setEnabled(false);
                setViewModesEnabled(false);
                Toast.makeText(AvatarLibraryActivity.this,
                        getString(R.string.avatar_failed) + "：" + message,
                        Toast.LENGTH_LONG).show();
            }
        });

        ArrayList<String> families = new ArrayList<>();
        families.add(getString(R.string.avatar_all_families));
        LinkedHashSet<String> uniqueFamilies = new LinkedHashSet<>();
        for (AvatarCatalog.Avatar avatar : avatars) {
            if (avatar.family != null && !avatar.family.trim().isEmpty()) {
                uniqueFamilies.add(avatar.family.trim());
            }
        }
        families.addAll(uniqueFamilies);
        selectedFamily = families.get(0);
        ArrayAdapter<String> familyAdapter = new ArrayAdapter<>(
                this, R.layout.item_voice_selected, families);
        familyAdapter.setDropDownViewResource(R.layout.item_voice_dropdown);
        familySpinner.setAdapter(familyAdapter);
        familySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedFamily = families.get(position);
                filterAvatars();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterAvatars();
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        updateViewToggle();
        setViewModesEnabled(false);
        viewToggle.setOnClickListener(view -> setFullBody(true));
        portraitButton.setOnClickListener(view -> setFullBody(false));
        useButton.setOnClickListener(view -> useFocusedAvatar());
        findViewById(R.id.avatarBackButton).setOnClickListener(view -> finishWithResult());

        filterAvatars();
        focusAvatar(selected == null && !avatars.isEmpty() ? avatars.get(0) : selected);
        list.post(() -> {
            int position = indexOfVisible(focusedId);
            if (position >= 0) {
                list.setSelection(position);
            }
        });
    }

    private void filterAvatars() {
        if (avatars == null || adapter == null) {
            return;
        }
        String query = searchInput.getText().toString().trim().toLowerCase(Locale.ROOT);
        String all = getString(R.string.avatar_all_families);
        visibleAvatars.clear();
        for (AvatarCatalog.Avatar avatar : avatars) {
            String searchable = (avatar.name + " " + avatar.family).toLowerCase(Locale.ROOT);
            boolean matchesQuery = query.isEmpty() || searchable.contains(query);
            boolean matchesFamily = selectedFamily == null || selectedFamily.equals(all)
                    || selectedFamily.equals(avatar.family);
            if (matchesQuery && matchesFamily) {
                visibleAvatars.add(avatar);
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void focusAvatar(AvatarCatalog.Avatar avatar) {
        if (avatar == null) {
            return;
        }
        boolean changed = !avatar.id.equals(focusedId);
        focusedId = avatar.id;
        previewTitle.setText(avatar.name);
        previewMeta.setText(getString(R.string.avatar_local_meta, avatar.family));
        boolean ready = preview.isReadyForModel(avatar.id);
        previewStatus.setText(getString(ready ? R.string.avatar_preview_ready
                : R.string.avatar_preview_loading, avatar.name));
        useButton.setEnabled(ready && !avatar.id.equals(selectedId));
        setViewModesEnabled(ready);
        updateSelectionActions();
        adapter.notifyDataSetChanged();
        if (changed || !preview.isReadyForModel(avatar.id)) {
            preview.setModel(avatar.id);
        }
        preview.setViewMode(fullBody);
    }

    private void useFocusedAvatar() {
        AvatarCatalog.Avatar focused = focusedAvatar();
        if (focused == null || focused.id.equals(selectedId)) {
            return;
        }
        if (!AppSettings.saveAvatarSelection(this, focused.id)) {
            Toast.makeText(this, R.string.avatar_save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        selectedId = focused.id;
        selectionChanged = true;
        setResult(RESULT_OK);
        updateSelectionActions();
        adapter.notifyDataSetChanged();
        Toast.makeText(this, R.string.avatar_selected, Toast.LENGTH_SHORT).show();
    }

    private void updateSelectionActions() {
        boolean current = focusedId != null && focusedId.equals(selectedId);
        currentLabel.setVisibility(current ? View.VISIBLE : View.GONE);
        useButton.setVisibility(current ? View.GONE : View.VISIBLE);
    }

    private void updateViewToggle() {
        viewToggle.setSelected(fullBody);
        portraitButton.setSelected(!fullBody);
        viewToggle.setTextColor(getColor(fullBody ? R.color.accent : R.color.text_secondary));
        portraitButton.setTextColor(getColor(fullBody ? R.color.text_secondary : R.color.accent));
    }

    private void setFullBody(boolean value) {
        if (fullBody == value) return;
        fullBody = value;
        AppSettings.saveAvatarFullBody(this, fullBody);
        preview.setViewMode(fullBody);
        updateViewToggle();
    }

    private void setViewModesEnabled(boolean enabled) {
        viewToggle.setEnabled(enabled);
        portraitButton.setEnabled(enabled);
        viewToggle.setAlpha(enabled ? 1f : .45f);
        portraitButton.setAlpha(enabled ? 1f : .45f);
    }

    private void styleLibrary(Spinner familySpinner) {
        findViewById(R.id.avatarLibraryHeader).setBackgroundResource(R.drawable.bg_header_cyber);
        findViewById(R.id.avatarPreviewStage).setForeground(new AvatarPreviewFrame(this));
        for (int id : new int[]{R.id.avatarBackButton, R.id.useAvatarButton,
                R.id.libraryViewToggleButton, R.id.libraryPortraitButton}) {
            Button button = findViewById(id);
            button.setBackgroundTintList(null);
            button.setBackground(new CyberPanelDrawable(this, id == R.id.useAvatarButton
                    ? CyberPanelDrawable.Kind.PRIMARY : CyberPanelDrawable.Kind.CHOICE));
            button.setTextColor(getColor(R.color.accent));
        }
        searchInput.setBackground(new CyberPanelDrawable(this, CyberPanelDrawable.Kind.INPUT));
        familySpinner.setBackground(new CyberPanelDrawable(this, CyberPanelDrawable.Kind.ACTION));
        currentLabel.setBackgroundResource(R.drawable.bg_status_pill);
        currentLabel.setPadding(dp(10), dp(6), dp(10), dp(6));
        currentLabel.setTextColor(getColor(R.color.cyber_mint));
    }

    private AvatarCatalog.Avatar focusedAvatar() {
        for (AvatarCatalog.Avatar avatar : avatars) {
            if (avatar.id.equals(focusedId)) {
                return avatar;
            }
        }
        return null;
    }

    private int indexOfVisible(String id) {
        for (int index = 0; index < visibleAvatars.size(); index++) {
            if (visibleAvatars.get(index).id.equals(id)) {
                return index;
            }
        }
        return -1;
    }

    private void finishWithResult() {
        if (selectionChanged) {
            setResult(RESULT_OK);
        }
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        preview.onResume();
    }

    @Override
    protected void onPause() {
        preview.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        preview.destroy();
        super.onDestroy();
    }

    private final class AvatarAdapter extends BaseAdapter {
        private final LruCache<String, Bitmap> previews = new LruCache<>(16);

        @Override public int getCount() { return visibleAvatars.size(); }
        @Override public AvatarCatalog.Avatar getItem(int position) { return visibleAvatars.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder holder;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(AvatarLibraryActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(14), dp(8), dp(14), dp(8));
                row.setMinimumHeight(dp(78));

                ImageView image = new ImageView(AvatarLibraryActivity.this);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setBackgroundResource(R.drawable.bg_view_toggle);
                image.setClipToOutline(true);
                row.addView(image, new LinearLayout.LayoutParams(dp(60), dp(60)));

                LinearLayout copy = new LinearLayout(AvatarLibraryActivity.this);
                copy.setOrientation(LinearLayout.VERTICAL);
                copy.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                        0, dp(60), 1f);
                copyParams.setMarginStart(dp(14));
                row.addView(copy, copyParams);

                TextView name = new TextView(AvatarLibraryActivity.this);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                name.setTextColor(getColor(R.color.text_primary));
                name.setTextSize(15);
                copy.addView(name, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

                TextView meta = new TextView(AvatarLibraryActivity.this);
                meta.setSingleLine(true);
                meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
                meta.setTextColor(getColor(R.color.text_secondary));
                meta.setTextSize(12);
                copy.addView(meta, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

                TextView state = new TextView(AvatarLibraryActivity.this);
                state.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
                state.setTextColor(getColor(R.color.accent));
                state.setTextSize(12);
                row.addView(state, new LinearLayout.LayoutParams(dp(64), dp(60)));
                holder = new Holder(image, name, meta, state);
                row.setTag(holder);
                convertView = row;
            } else {
                holder = (Holder) convertView.getTag();
            }

            AvatarCatalog.Avatar avatar = getItem(position);
            holder.name.setText(avatar.name);
            holder.meta.setText(avatar.family);
            holder.state.setText(avatar.id.equals(selectedId)
                    ? getString(R.string.avatar_in_use)
                    : avatar.id.equals(focusedId) ? getString(R.string.avatar_previewing) : "");
            convertView.setBackground(new CyberPanelDrawable(AvatarLibraryActivity.this,
                    CyberPanelDrawable.Kind.SECTION));
            convertView.setSelected(avatar.id.equals(focusedId));
            Bitmap bitmap = previews.get(avatar.preview);
            if (bitmap == null) {
                bitmap = loadPreview(avatar.preview);
                if (bitmap != null) {
                    previews.put(avatar.preview, bitmap);
                }
            }
            holder.image.setImageBitmap(bitmap);
            return convertView;
        }

        private Bitmap loadPreview(String assetPath) {
            try (InputStream input = getAssets().open("live2d/" + assetPath)) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 2;
                return BitmapFactory.decodeStream(input, null, options);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static final class Holder {
        final ImageView image;
        final TextView name;
        final TextView meta;
        final TextView state;

        Holder(ImageView image, TextView name, TextView meta, TextView state) {
            this.image = image;
            this.name = name;
            this.meta = meta;
            this.state = state;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
