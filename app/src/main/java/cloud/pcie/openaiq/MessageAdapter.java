package cloud.pcie.openaiq;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageButton;
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
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(rd(14), rd(5), rd(14), rd(5));
        row.setGravity((user ? Gravity.END : Gravity.START) | Gravity.TOP);

        LinearLayout bubble = new LinearLayout(context);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(rd(14), rd(11), rd(14), rd(11));
        int avatarSize = rd(40);
        int avatarGap = rd(10);
        int rowWidth = parent.getWidth() > 0 ? parent.getWidth()
                : parent.getResources().getDisplayMetrics().widthPixels;
        int contentMaxWidth = Math.max(1, rowWidth - row.getPaddingLeft()
                - row.getPaddingRight() - avatarSize - avatarGap
                - bubble.getPaddingLeft() - bubble.getPaddingRight() - sx(12));
        if (!user && !message.hasGeneratedImage()) {
            bubble.setMinimumWidth(Math.min(rd(180), contentMaxWidth));
        }
        bubble.setBackground(new CyberBubbleDrawable(context, user));
        bindDeleteOnLongPress(bubble, message);

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
        if (!displayContent.isEmpty()) {
            TextView text = new TextView(context);
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, DesignScale.referenceSp(context, 16));
            text.setIncludeFontPadding(true);
            text.setTextIsSelectable(true);
            text.setLineSpacing(0, 1.08f);
            text.setTextColor(context.getColor(
                    message.error ? R.color.warning : R.color.text_primary));
            if (!message.error && !user) text.setTextColor(0xFFB9DEFA);
            text.setTypeface(
                    Typeface.DEFAULT,
                    message.error ? Typeface.ITALIC : Typeface.NORMAL);
            text.setMaxWidth(Math.min(Math.round(rowWidth * 0.78f), contentMaxWidth));
            text.setText(displayContent);
            bubble.addView(text);
        }

        if (message.hasGeneratedImage()) {
            int imageWidth = Math.min(sx(936), contentMaxWidth);
            int imageHeight = imageDisplayHeight(message.imageUri, imageWidth);
            ImageView image = new ImageView(context);
            image.setContentDescription(context.getString(R.string.generated_image));
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setAdjustViewBounds(true);
            image.setBackgroundColor(context.getColor(R.color.bg));
            image.setImageURI(Uri.parse(message.imageUri));
            image.setOnClickListener(view -> actions.onOpenImage(message));
            bindDeleteOnLongPress(image, message);
            LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(imageWidth, imageHeight);
            if (!displayContent.isEmpty() || !message.meta.isEmpty()) {
                imageParams.topMargin = sx(16);
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
            bubble.addView(imageActions, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        if (message.error && message.retryable) {
            Button retry = actionButton(R.string.retry, view -> actions.onRetry(position));
            LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    sx(56));
            retryParams.gravity = Gravity.END;
            bubble.addView(retry, retryParams);
        }

        boolean speakable = !message.hasGeneratedImage() && message.isSpeakable();
        if (!message.meta.isEmpty() || speakable) {
            LinearLayout footer = new LinearLayout(context);
            footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            footer.setOrientation(LinearLayout.HORIZONTAL);
            if (!message.meta.isEmpty()) {
                TextView meta = new TextView(context);
                meta.setText(message.meta);
                meta.setTextSize(TypedValue.COMPLEX_UNIT_PX, DesignScale.referenceSp(context, 11.52f));
                meta.setTextColor(0xFF6CADD0);
                meta.setIncludeFontPadding(false);
                meta.setMaxWidth(Math.min(sx(600), Math.max(1, contentMaxWidth - sx(84))));
                footer.addView(meta);
            }
            if (speakable) {
                boolean speaking = actions.isSpeaking(message);
                footer.addView(iconButton(
                        speaking ? R.drawable.ic_stop : R.drawable.ic_speak,
                        speaking ? R.string.stop_speaking : R.string.speak,
                        R.color.accent, view -> actions.onSpeak(message)));
            }
            LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            footerParams.topMargin = sx(9.6f);
            bubble.addView(footer, footerParams);
        }

        ImageView avatar = messageAvatar(user);
        bindDeleteOnLongPress(avatar, message);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(avatarGap, sx(1));
        LinearLayout.LayoutParams bubbleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        if (user) {
            row.addView(bubble, bubbleParams);
            row.addView(new View(context), gap);
            row.addView(avatar, avatarParams);
        } else {
            row.addView(avatar, avatarParams);
            row.addView(new View(context), gap);
            row.addView(bubble, bubbleParams);
        }
        return row;
    }

    private ImageView messageAvatar(boolean user) {
        ImageView avatar = new ImageView(context);
        avatar.setScaleType(ImageView.ScaleType.FIT_CENTER);
        avatar.setImageResource(user
                ? R.drawable.ic_user_avatar
                : R.drawable.n_icon_transparent);
        avatar.setContentDescription(context.getString(
                user ? R.string.user_avatar : R.string.ning_avatar));
        return avatar;
    }

    private int rd(float referenceDp) {
        return DesignScale.referenceDp(context, referenceDp);
    }

    private Button actionButton(int textResource, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(textResource);
        button.setTextColor(context.getColor(R.color.accent));
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, sx(22));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(sx(20), 0, sx(20), 0);
        button.setBackgroundTintList(context.getColorStateList(R.color.surface_high));
        button.setOnClickListener(listener);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                sx(56)));
        return button;
    }

    private ImageButton iconButton(
            int iconResource,
            int descriptionResource,
            int tintResource,
            View.OnClickListener listener) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(iconResource);
        button.setColorFilter(context.getColor(tintResource));
        button.setContentDescription(context.getString(descriptionResource));
        android.util.TypedValue selectable = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, selectable, true);
        button.setBackgroundResource(selectable.resourceId);
        button.setPadding(sx(16.8f), sx(16.8f), sx(16.8f), sx(16.8f));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(sx(72), sx(72));
        params.setMarginStart(sx(12));
        button.setLayoutParams(params);
        return button;
    }

    private void bindDeleteOnLongPress(View view, ChatMessage message) {
        view.setOnLongClickListener(target -> {
            target.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            actions.onDelete(message);
            return true;
        });
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
            return Math.min(sx(800), Math.max(sx(450), sx(600)));
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            return sx(600);
        }
        float ratio = options.outHeight / (float) options.outWidth;
        return Math.max(sx(400), Math.min(sx(1050), Math.round(imageWidth * ratio)));
    }

    private int sx(float designPixels) {
        return DesignScale.px(context, designPixels);
    }
}
