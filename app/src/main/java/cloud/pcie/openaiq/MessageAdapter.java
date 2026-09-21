package cloud.pcie.openaiq;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.io.File;
import java.io.InputStream;

final class MessageAdapter extends BaseAdapter {
    interface Actions {
        void onRetry(int position);
        void onOpenImage(ChatMessage message);
        void onDownloadImage(ChatMessage message);
        void onDelete(ChatMessage message);
        void onSpeak(ChatMessage message);
        boolean isSpeaking(ChatMessage message);
    }

    private final Context context;
    private final List<ChatMessage> messages;
    private final Actions actions;

    MessageAdapter(Context context, List<ChatMessage> messages, Actions actions) {
        this.context = context;
        this.messages = messages;
        this.actions = actions;
    }

    @Override
    public int getCount() {
        return messages.size();
    }

    @Override
    public ChatMessage getItem(int position) {
        return messages.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ChatMessage message = getItem(position);
        boolean user = ChatMessage.ROLE_USER.equals(message.role);

        LinearLayout row = new LinearLayout(context);
        row.setPadding(dp(14), dp(5), dp(14), dp(5));
        row.setGravity(user ? Gravity.END : Gravity.START);

        LinearLayout bubble = new LinearLayout(context);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(14), dp(11), dp(14), dp(11));
        if (!user && !message.hasGeneratedImage()) {
            bubble.setMinimumWidth(dp(180));
        }
        bubble.setBackgroundResource(user
                ? R.drawable.bg_message_user
                : R.drawable.bg_message_assistant);

        String displayContent = message.content;
        if (message.hasImage() && !message.hasGeneratedImage()) {
            String imageLabel = context.getString(
                    R.string.image_attached,
                    message.imageName.isEmpty()
                            ? context.getString(R.string.image)
                            : message.imageName);
            displayContent = displayContent.isEmpty()
                    ? imageLabel
                    : imageLabel + "\n" + displayContent;
        }
        if (displayContent.isEmpty() && !message.hasGeneratedImage()) {
            displayContent = "...";
        }
        if (!displayContent.isEmpty() || !message.meta.isEmpty()) {
            TextView text = new TextView(context);
            text.setTextSize(16);
            text.setTextIsSelectable(true);
            text.setLineSpacing(0, 1.08f);
            text.setTextColor(context.getColor(
                    message.error ? R.color.warning : R.color.text_primary));
            text.setTypeface(
                    Typeface.DEFAULT,
                    message.error ? Typeface.ITALIC : Typeface.NORMAL);
            text.setMaxWidth((int) (parent.getResources()
                    .getDisplayMetrics().widthPixels * 0.78f));
            text.setText(renderText(displayContent, message.meta));
            bubble.addView(text);
        }

        if (message.hasGeneratedImage()) {
            int availableWidth = parent.getResources().getDisplayMetrics().widthPixels - dp(84);
            int imageWidth = Math.min(dp(320), Math.max(dp(180), availableWidth));
            int imageHeight = imageDisplayHeight(message.imageUri, imageWidth);
            ImageView image = new ImageView(context);
            image.setContentDescription(context.getString(R.string.generated_image));
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setAdjustViewBounds(true);
            image.setBackgroundColor(context.getColor(R.color.bg));
            image.setImageURI(Uri.parse(message.imageUri));
            image.setOnClickListener(view -> actions.onOpenImage(message));
            LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(imageWidth, imageHeight);
            if (!displayContent.isEmpty() || !message.meta.isEmpty()) {
                imageParams.topMargin = dp(10);
            }
            bubble.addView(image, imageParams);

            LinearLayout imageActions = new LinearLayout(context);
            imageActions.setGravity(Gravity.END);
            imageActions.setOrientation(LinearLayout.HORIZONTAL);
            imageActions.addView(actionButton(
                    R.string.view_image,
                    view -> actions.onOpenImage(message)));
            imageActions.addView(actionButton(
                    R.string.download,
                    view -> actions.onDownloadImage(message)));
            imageActions.addView(deleteButton(message));
            bubble.addView(imageActions, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        if (message.error && message.retryable) {
            Button retry = actionButton(R.string.retry, view -> actions.onRetry(position));
            LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(38));
            retryParams.gravity = Gravity.END;
            bubble.addView(retry, retryParams);
        }

        if (!message.hasGeneratedImage()) {
            LinearLayout textActions = new LinearLayout(context);
            textActions.setGravity(Gravity.END);
            textActions.setOrientation(LinearLayout.HORIZONTAL);
            if (message.isSpeakable()) {
                textActions.addView(actionButton(
                        actions.isSpeaking(message)
                                ? R.string.stop_speaking
                                : R.string.speak,
                        view -> actions.onSpeak(message)));
            }
            textActions.addView(deleteButton(message));
            bubble.addView(textActions, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        row.addView(bubble, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private CharSequence renderText(String content, String meta) {
        SpannableStringBuilder rendered = new SpannableStringBuilder(content);
        if (!meta.isEmpty()) {
            int start = rendered.length() + (rendered.length() == 0 ? 0 : 1);
            if (rendered.length() > 0) {
                rendered.append('\n');
            }
            rendered.append(meta);
            rendered.setSpan(
                    new RelativeSizeSpan(0.72f),
                    start,
                    rendered.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            rendered.setSpan(
                    new ForegroundColorSpan(context.getColor(R.color.text_secondary)),
                    start,
                    rendered.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return rendered;
    }

    private Button actionButton(int textResource, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(textResource);
        button.setTextColor(context.getColor(R.color.accent));
        button.setTextSize(13);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackgroundTintList(context.getColorStateList(R.color.surface_high));
        button.setOnClickListener(listener);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(38)));
        return button;
    }

    private Button deleteButton(ChatMessage message) {
        Button button = actionButton(R.string.delete_message, view -> actions.onDelete(message));
        button.setTextColor(context.getColor(R.color.warning));
        return button;
    }

    private int imageDisplayHeight(String source, int imageWidth) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try {
            Uri uri = Uri.parse(source);
            if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
                BitmapFactory.decodeFile(new File(uri.getPath()).getAbsolutePath(), options);
            } else {
                try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                    BitmapFactory.decodeStream(input, null, options);
                }
            }
        } catch (Exception ignored) {
            return Math.min(dp(320), Math.max(dp(180), dp(240)));
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            return dp(240);
        }
        float ratio = options.outHeight / (float) options.outWidth;
        return Math.max(dp(160), Math.min(dp(420), Math.round(imageWidth * ratio)));
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
