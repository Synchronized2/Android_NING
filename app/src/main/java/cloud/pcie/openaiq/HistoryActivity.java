package cloud.pcie.openaiq;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class HistoryActivity extends Activity {
    private final ArrayList<ConversationStore.Summary> conversations = new ArrayList<>();
    private ConversationStore store;
    private HistoryAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        setResult(RESULT_OK);
        store = new ConversationStore(this);
        ListView list = findViewById(R.id.historyList);
        adapter = new HistoryAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.historyEmpty));
        findViewById(R.id.historyNewButton).setOnClickListener(view -> {
            store.createConversation();
            returnToConversation();
        });
        findViewById(R.id.historySettingsButton).setOnClickListener(
                view -> startActivity(new Intent(this, SettingsActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        conversations.clear();
        conversations.addAll(store.list());
        adapter.notifyDataSetChanged();
    }

    private void openConversation(ConversationStore.Summary conversation) {
        if (store.selectConversation(conversation.id)) {
            returnToConversation();
        }
    }

    private void returnToConversation() {
        setResult(RESULT_OK);
        finish();
    }

    private void renameConversation(ConversationStore.Summary conversation) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint(R.string.conversation_name_hint);
        input.setText(conversation.title);
        input.setSelection(input.length());
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout wrapper = new android.widget.FrameLayout(this);
        wrapper.setPadding(padding, 0, padding, 0);
        wrapper.addView(input, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.rename_conversation)
                .setView(wrapper)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    if (store.renameConversation(conversation.id, input.getText().toString())) {
                        dialog.dismiss();
                        refresh();
                    } else {
                        input.setError(getString(R.string.conversation_name_hint));
                    }
                }));
        dialog.show();
    }

    private void deleteConversation(ConversationStore.Summary conversation) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_conversation)
                .setMessage(R.string.confirm_delete_conversation)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete_message, (dialog, which) -> {
                    store.deleteConversation(conversation.id);
                    refresh();
                })
                .show();
    }

    private String formatTime(long timestamp) {
        Date value = new Date(timestamp);
        Date now = new Date();
        SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd", Locale.CHINA);
        if (day.format(value).equals(day.format(now))) {
            return new SimpleDateFormat("HH:mm", Locale.CHINA).format(value);
        }
        return new SimpleDateFormat("M月d日", Locale.CHINA).format(value);
    }

    private final class HistoryAdapter extends ArrayAdapter<ConversationStore.Summary> {
        HistoryAdapter() {
            super(HistoryActivity.this, R.layout.item_conversation, conversations);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(getContext()).inflate(R.layout.item_conversation, parent, false);
            }
            ConversationStore.Summary item = getItem(position);
            if (item == null) {
                return row;
            }
            row.setBackgroundColor(item.active ? getColor(R.color.surface) : Color.TRANSPARENT);
            ((TextView) row.findViewById(R.id.conversationTitle)).setText(item.title);
            ((TextView) row.findViewById(R.id.conversationTime)).setText(formatTime(item.updatedAt));
            ((TextView) row.findViewById(R.id.conversationPreview)).setText(item.preview);
            ((TextView) row.findViewById(R.id.conversationCount)).setText(getString(
                    R.string.history_message_count,
                    item.messageCount,
                    item.active ? getString(R.string.history_current) : ""));
            View main = row.findViewById(R.id.conversationRow);
            main.setOnClickListener(view -> openConversation(item));
            Button rename = row.findViewById(R.id.conversationRenameButton);
            rename.setOnClickListener(view -> renameConversation(item));
            Button delete = row.findViewById(R.id.conversationDeleteButton);
            delete.setOnClickListener(view -> deleteConversation(item));
            return row;
        }
    }
}
