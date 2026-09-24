package cloud.pcie.openaiq;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Image workspace; callbacks reuse the same persisted generation pipeline as chat. */
final class ImageWorkbenchView extends LinearLayout {
    interface Actions {
        void generate();
        void pickReference();
        void clearReference();
        void retry();
        void openImage(ChatMessage image, List<ChatMessage> gallery);
    }

    private final Actions actions;
    private final ExecutorService thumbnails = Executors.newSingleThreadExecutor();
    private final ArrayList<ChatMessage> gallery = new ArrayList<>();
    private final ArrayList<View> choices = new ArrayList<>();
    private final EditText prompt;
    private final Button generate;
    private final Button reference;
    private final Button removeReference;
    private final Button retry;
    private final TextView jobStatus;
    private final TextView referenceName;
    private final ImageView referencePreview;
    private final LinearLayout referenceRow;
    private final LinearLayout recent;
    private int ratioIndex;
    private int styleIndex;
    private int qualityIndex = 1;
    private String gallerySignature = "";

    ImageWorkbenchView(Context context, Actions actions) {
        super(context);
        this.actions = actions;
        setId(R.id.imageWorkbench);
        setOrientation(VERTICAL);
        setPadding(d(14), d(10), d(14), d(10));
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        addView(scroll, new LayoutParams(-1, 0, 1));
        LinearLayout content = column();
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        LinearLayout description = panel(content, "图片描述");
        ((LayoutParams) description.getLayoutParams()).weight = 2;
        prompt = new EditText(context);
        prompt.setId(R.id.imagePromptInput);
        prompt.setHint("描述你想生成的图片…");
        prompt.setTextColor(0xFFE0F4FF);
        prompt.setHintTextColor(0xFF80A5D3);
        prompt.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(16));
        prompt.setGravity(Gravity.TOP | Gravity.START);
        prompt.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        prompt.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500)});
        prompt.setPadding(d(12), d(12), d(12), d(8));
        prompt.setBackground(new CyberPanelDrawable(context, CyberPanelDrawable.Kind.INPUT));
        description.addView(prompt, new LayoutParams(-1, d(102), 1));
        TextView counter = label("0/500", 11);
        counter.setGravity(Gravity.END);
        counter.setTextColor(0xFF729EC7);
        description.addView(counter);
        prompt.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { counter.setText(s.length() + "/500"); }
            public void afterTextChanged(Editable s) { }
        });

        LinearLayout dual = row();
        LayoutParams dualParams = new LayoutParams(-1, -2);
        dualParams.topMargin = d(10);
        content.addView(dual, dualParams);
        LinearLayout ratios = section("图片比例");
        LinearLayout styles = section("图片风格");
        LayoutParams half = new LayoutParams(0, -1, 1);
        half.setMarginEnd(d(8));
        dual.addView(ratios, half);
        dual.addView(styles, new LayoutParams(0, -1, 1));
        addChoices(ratios, ImageGenerationOptions.RATIOS, 0);
        addChoices(styles, ImageGenerationOptions.STYLES, 1);

        LinearLayout quality = panel(content, "生成质量");
        addChoices(quality, new String[]{"快速", "标准", "高清"}, 2);

        LinearLayout recentPanel = panel(content, null);
        ((LayoutParams) recentPanel.getLayoutParams()).weight = 1;
        LinearLayout recentHeader = row();
        recentHeader.addView(heading("最近生成"), new LayoutParams(0, d(32), 1));
        Button all = button("查看全部 ›");
        all.setBackgroundColor(Color.TRANSPARENT);
        all.setOnClickListener(v -> showGallery());
        recentHeader.addView(all, new LayoutParams(d(88), d(32)));
        recentPanel.addView(recentHeader);
        HorizontalScrollView strip = new HorizontalScrollView(context);
        strip.setHorizontalScrollBarEnabled(false);
        recent = row();
        strip.addView(recent);
        recentPanel.addView(strip, new LayoutParams(-1, d(72), 1));
        updateGallery(new ArrayList<>());

        LinearLayout job = column();
        content.addView(job, new LayoutParams(-1, -2));
        jobStatus = label("", 12);
        jobStatus.setId(R.id.imageJobStatus);
        jobStatus.setPadding(d(8), d(8), d(8), d(6));
        job.addView(jobStatus);
        retry = button("重试这次生图");
        retry.setId(R.id.imageRetryButton);
        retry.setOnClickListener(v -> actions.retry());
        job.addView(retry, new LayoutParams(-1, d(36)));
        retry.setVisibility(GONE);

        referenceRow = row();
        referenceRow.setPadding(d(6), d(5), d(6), d(5));
        referencePreview = new ImageView(context);
        referencePreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        referenceRow.addView(referencePreview, new LayoutParams(d(36), d(36)));
        referenceName = label("", 12);
        referenceName.setSingleLine(true);
        referenceName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        referenceName.setPadding(d(8), 0, d(8), 0);
        referenceRow.addView(referenceName, new LayoutParams(0, -2, 1));
        removeReference = button("移除");
        removeReference.setOnClickListener(v -> actions.clearReference());
        referenceRow.addView(removeReference, new LayoutParams(d(52), d(36)));
        referenceRow.setVisibility(GONE);
        content.addView(referenceRow);

        LinearLayout bottom = row();
        bottom.setPadding(0, d(10), 0, 0);
        reference = button("＋  添加参考图");
        reference.setId(R.id.imageReferenceButton);
        reference.setOnClickListener(v -> actions.pickReference());
        LayoutParams referenceParams = new LayoutParams(d(140), d(48));
        referenceParams.setMarginEnd(d(8));
        bottom.addView(reference, referenceParams);
        generate = button("生成图片");
        generate.setId(R.id.generateImageButton);
        generate.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(18));
        generate.setBackground(new CyberPanelDrawable(context, CyberPanelDrawable.Kind.PRIMARY));
        generate.setSelected(true);
        generate.setOnClickListener(v -> actions.generate());
        bottom.addView(generate, new LayoutParams(0, d(48), 1));
        content.addView(bottom);
    }

    String description() { return prompt.getText().toString().trim(); }
    void clearDraft() { prompt.setText(""); }
    String generationPrompt() { return ImageGenerationOptions.prompt(description(), ratioIndex, styleIndex); }
    ImageGenerationOptions options() {
        return new ImageGenerationOptions(ImageGenerationOptions.SIZES[ratioIndex], ImageGenerationOptions.QUALITIES[qualityIndex]);
    }

    void saveState(Bundle state) {
        state.putString("studio_prompt", prompt.getText().toString());
        state.putInt("studio_ratio", ratioIndex);
        state.putInt("studio_style", styleIndex);
        state.putInt("studio_quality", qualityIndex);
    }

    void restoreState(Bundle state) {
        if (state == null) return;
        prompt.setText(state.getString("studio_prompt", ""));
        ratioIndex = Math.max(0, Math.min(3, state.getInt("studio_ratio")));
        styleIndex = Math.max(0, Math.min(3, state.getInt("studio_style")));
        qualityIndex = Math.max(0, Math.min(2, state.getInt("studio_quality", 1)));
        updateSelection();
    }

    void setReference(String uri, String name) {
        referenceRow.setVisibility(uri.isEmpty() ? GONE : VISIBLE);
        referenceName.setText(name);
        if (uri.isEmpty()) { referencePreview.setTag(null); referencePreview.setImageDrawable(null); }
        else if (!uri.equals(referencePreview.getTag())) loadThumbnail(referencePreview, uri);
    }

    void updateJob(boolean busy, boolean imageJob, ChatMessage latest) {
        prompt.setEnabled(!busy);
        reference.setEnabled(!busy);
        removeReference.setEnabled(!busy);
        for (View choice : choices) choice.setEnabled(!busy);
        generate.setText(busy ? (imageJob ? "停止生成" : "对话处理中") : "生成图片");
        generate.setEnabled(!busy || imageJob);
        jobStatus.setText(busy ? (imageJob ? "正在生成图片，可切换到对话页查看进度…" : "对话正在处理中…")
                : latest == null ? "" : latest.content + (latest.meta.isEmpty() ? "" : " · " + latest.meta));
        jobStatus.setTextColor(latest != null && latest.error && !busy ? 0xFFFFB77B : 0xFF98DDF7);
        jobStatus.setVisibility(jobStatus.length() == 0 ? GONE : VISIBLE);
        retry.setVisibility(!busy && latest != null && latest.error && latest.retryable ? VISIBLE : GONE);
    }

    void updateGallery(List<ChatMessage> images) {
        StringBuilder signature = new StringBuilder();
        for (ChatMessage image : images) signature.append(image.imageUri).append('\n');
        if (signature.toString().equals(gallerySignature) && recent.getChildCount() > 0) return;
        gallerySignature = signature.toString();
        gallery.clear();
        gallery.addAll(images);
        recent.removeAllViews();
        if (gallery.isEmpty()) {
            TextView empty = label("还没有作品\n生成的图片会保存在这里", 13);
            empty.setTextColor(0xFF7095B9);
            empty.setGravity(Gravity.CENTER);
            recent.addView(empty, new LayoutParams(d(346), d(68)));
        }
        for (int i = 0; i < Math.min(8, gallery.size()); i++) {
            ChatMessage image = gallery.get(i);
            ImageView thumbnail = thumbnail(image);
            LayoutParams params = new LayoutParams(d(68), d(68));
            params.setMarginEnd(d(6));
            recent.addView(thumbnail, params);
        }
    }

    private ImageView thumbnail(ChatMessage message) {
        ImageView image = new ImageView(getContext());
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(new CyberPanelDrawable(getContext(), CyberPanelDrawable.Kind.ACTION));
        image.setPadding(d(3), d(3), d(3), d(3));
        image.setContentDescription("查看生成图片：" + message.imageName);
        loadThumbnail(image, message.imageUri);
        image.setOnClickListener(v -> actions.openImage(message, new ArrayList<>(gallery)));
        return image;
    }

    private void showGallery() {
        if (gallery.isEmpty()) {
            new AlertDialog.Builder(getContext()).setTitle("全部作品").setMessage("还没有生成的图片。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        ScrollView scroll = new ScrollView(getContext());
        LinearLayout grid = column();
        grid.setPadding(d(12), d(8), d(12), d(8));
        scroll.addView(grid);
        Button more = button("加载更多");
        int[] shown = {0};
        Runnable append = () -> {
            grid.removeView(more);
            int end = Math.min(gallery.size(), shown[0] + 12);
            while (shown[0] < end) {
                LinearLayout row = row();
                for (int col = 0; col < 3; col++) {
                    LayoutParams cell = new LayoutParams(0, d(100), 1);
                    cell.setMargins(d(3), d(3), d(3), d(3));
                    row.addView(shown[0] < end ? thumbnail(gallery.get(shown[0]++)) : new View(getContext()), cell);
                }
                grid.addView(row);
            }
            if (shown[0] < gallery.size()) grid.addView(more);
        };
        more.setOnClickListener(v -> append.run());
        append.run();
        new AlertDialog.Builder(getContext()).setTitle("全部作品 · " + gallery.size())
                .setView(scroll).setNegativeButton("关闭", null).show();
    }

    private void loadThumbnail(ImageView target, String uri) {
        target.setTag(uri);
        target.setImageDrawable(null);
        thumbnails.execute(() -> {
            Bitmap bitmap = null;
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                try (InputStream input = getContext().getContentResolver().openInputStream(Uri.parse(uri))) {
                    BitmapFactory.decodeStream(input, null, options);
                }
                options.inSampleSize = 1;
                while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 400) options.inSampleSize *= 2;
                options.inJustDecodeBounds = false;
                try (InputStream input = getContext().getContentResolver().openInputStream(Uri.parse(uri))) {
                    bitmap = BitmapFactory.decodeStream(input, null, options);
                }
            } catch (Exception ignored) { }
            Bitmap decoded = bitmap;
            post(() -> { if (uri.equals(target.getTag())) target.setImageBitmap(decoded); });
        });
    }

    void release() { thumbnails.shutdownNow(); }

    private void addChoices(LinearLayout parent, String[] titles, int group) {
        LinearLayout row = row();
        row.setGravity(Gravity.TOP);
        for (int i = 0; i < titles.length; i++) {
            final int index = i;
            LinearLayout choice = column();
            choice.setGravity(Gravity.CENTER);
            choice.setPadding(group == 1 ? 0 : d(3), d(4), group == 1 ? 0 : d(3), d(4));
            if (group != 1) choice.setBackground(new CyberPanelDrawable(getContext(), CyberPanelDrawable.Kind.CHOICE));
            choice.setTag(new int[]{group, i});
            choice.setContentDescription((group == 0 ? "比例 " : group == 1 ? "风格 " : "质量 ") + titles[i]);
            choice.setFocusable(true);
            if (group == 1) {
                int[] artwork = {R.drawable.style_default, R.drawable.style_anime,
                        R.drawable.style_realistic, R.drawable.style_scifi};
                choice.addView(new StylePreviewView(getContext(), artwork[i]), new LayoutParams(-1, -2));
            } else if (group == 0) {
                View art = new ChoiceArt(getContext(), i);
                choice.addView(art, new LayoutParams(-1, d(30)));
            }
            TextView title = label(titles[i], group == 2 ? 14 : 11);
            title.setGravity(Gravity.CENTER);
            choice.addView(title, new LayoutParams(-1, d(24)));
            choice.setOnClickListener(v -> {
                if (group == 0) ratioIndex = index;
                else if (group == 1) styleIndex = index;
                else qualityIndex = index;
                updateSelection();
            });
            choices.add(choice);
            LayoutParams params = new LayoutParams(0, group == 1 ? -2 : d(group == 2 ? 40 : 62), 1);
            if (i > 0) params.setMarginStart(d(3));
            row.addView(choice, params);
        }
        parent.addView(row, new LayoutParams(-1, -2));
        updateSelection();
    }

    private void updateSelection() {
        for (View choice : choices) {
            int[] tag = (int[]) choice.getTag();
            boolean selected = tag[1] == (tag[0] == 0 ? ratioIndex : tag[0] == 1 ? styleIndex : qualityIndex);
            choice.setSelected(selected);
            if (tag[0] == 1) ((ViewGroup) choice).getChildAt(0).setSelected(selected);
            TextView title = (TextView) ((ViewGroup) choice).getChildAt(((ViewGroup) choice).getChildCount() - 1);
            title.setTextColor(selected ? 0xFFF0FDFF : 0xFFB0CBE7);
        }
    }

    private LinearLayout panel(LinearLayout parent, String title) {
        LinearLayout panel = section(title);
        LayoutParams params = new LayoutParams(-1, -2);
        if (parent.getChildCount() > 0) params.topMargin = d(10);
        parent.addView(panel, params);
        return panel;
    }

    private LinearLayout section(String title) {
        LinearLayout panel = column();
        panel.setPadding(d(8), d(6), d(8), d(8));
        panel.setBackground(new CyberPanelDrawable(getContext(), CyberPanelDrawable.Kind.SECTION));
        panel.setSelected(true);
        if (title != null) panel.addView(heading(title), new LayoutParams(-1, d(32)));
        return panel;
    }

    private TextView heading(String title) {
        TextView view = label(title, 15);
        int resource = "最近生成".equals(title) ? R.drawable.ic_history
                : "图片比例".equals(title) ? R.drawable.ic_ratio
                : "图片风格".equals(title) ? R.drawable.ic_palette
                : "生成质量".equals(title) ? R.drawable.ic_layers : R.drawable.ic_image_tab;
        android.graphics.drawable.Drawable icon = getContext().getDrawable(resource).mutate();
        icon.setTint(0xFF35E5FF);
        icon.setBounds(0, 0, d(21), d(21));
        view.setCompoundDrawablesRelative(icon, null, null, null);
        view.setCompoundDrawablePadding(d(7));
        return view;
    }

    private LinearLayout column() { LinearLayout v = new LinearLayout(getContext()); v.setOrientation(VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(getContext()); v.setOrientation(HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); v.setBaselineAligned(false); return v; }
    private TextView label(String text, float size) {
        TextView view = new TextView(getContext());
        view.setText(text); view.setTextColor(0xFFD9EEFF); view.setGravity(Gravity.CENTER_VERTICAL);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(size));
        return view;
    }
    private Button button(String text) {
        Button button = new Button(getContext());
        button.setText(text); button.setTextColor(0xFF92E9FF); button.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        button.setAllCaps(false); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setMinHeight(0); button.setMinimumHeight(0); button.setPadding(d(6), 0, d(6), 0);
        button.setBackground(new CyberPanelDrawable(getContext(), CyberPanelDrawable.Kind.ACTION));
        return button;
    }
    private int d(float value) { return DesignScale.referenceDp(getContext(), value); }
    private float sp(float value) { return DesignScale.referenceSp(getContext(), value); }

    /** Aspect-ratio diagrams remain vectors; style artwork is packaged separately. */
    private static final class ChoiceArt extends View {
        private final int index;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ChoiceArt(Context context, int index) { super(context); this.index = index; }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            paint.setStyle(Paint.Style.FILL);
                float ratio = new float[]{1f, 0.75f, 0.5625f, 1.7778f}[index];
                // Equal longest edge makes each aspect ratio readable at the same visual size.
                float edge = Math.min(h * .75f, w * .72f);
                float rh = ratio >= 1 ? edge / ratio : edge, rw = rh * ratio;
                paint.setColor(0xFFB3E8FF); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(w * .04f);
                canvas.drawRoundRect((w-rw)/2, (h-rh)/2, (w+rw)/2, (h+rh)/2, 3, 3, paint);
        }
    }
}
