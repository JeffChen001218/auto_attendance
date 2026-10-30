package com.codex.autolark;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public final class ControllerActivity extends Activity {
    private Switch master;
    private Switch morning;
    private Switch evening;
    private TextView status;
    private TextView morningRange;
    private TextView eveningRange;
    private TextView morningStatus;
    private TextView eveningStatus;
    private Button morningEdit;
    private Button eveningEdit;
    private LinearLayout resetGroup;
    private Button reset;
    private boolean busy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Schedule.reschedule(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(36, 42, 36, 42);
        scroll.addView(layout);

        master = new Switch(this);
        master.setText("定时打开飞书");
        master.setTextSize(20);
        layout.addView(master);
        status = label(layout, 16, 16);
        section(layout, Schedule.AM);
        section(layout, Schedule.PM);

        resetGroup = new LinearLayout(this);
        resetGroup.setOrientation(LinearLayout.VERTICAL);
        resetGroup.setPadding(0, 28, 0, 0);
        TextView hint = new TextView(this);
        hint.setText("当前配置与默认配置不同");
        hint.setTextSize(16);
        resetGroup.addView(hint);
        reset = new Button(this);
        reset.setText("重置为默认配置");
        reset.setOnClickListener(v -> applyChange(() -> Schedule.resetDefaults(this)));
        resetGroup.addView(reset);
        layout.addView(resetGroup);

        setContentView(scroll);
        render();
    }

    private TextView label(LinearLayout parent, int size, int top) {
        TextView text = new TextView(this);
        text.setTextSize(size);
        text.setPadding(0, top, 0, 0);
        parent.addView(text);
        return text;
    }

    private void section(LinearLayout layout, String slot) {
        View line = new View(this);
        line.setBackgroundColor(0xffdddddd);
        LinearLayout.LayoutParams separator = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        separator.topMargin = 30;
        layout.addView(line, separator);

        Switch toggle = new Switch(this);
        toggle.setText(Schedule.AM.equals(slot) ? "早间时段" : "晚间时段");
        toggle.setTextSize(18);
        toggle.setPadding(0, 20, 0, 0);
        layout.addView(toggle);
        TextView range = label(layout, 16, 12);
        Button edit = new Button(this);
        edit.setText("修改时段");
        edit.setOnClickListener(v -> showTimeDialog(slot));
        layout.addView(edit);
        TextView detail = label(layout, 15, 8);
        if (Schedule.AM.equals(slot)) {
            morning = toggle;
            morningRange = range;
            morningEdit = edit;
            morningStatus = detail;
        } else {
            evening = toggle;
            eveningRange = range;
            eveningEdit = edit;
            eveningStatus = detail;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (status != null) render();
    }

    private void bind(Switch toggle, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        toggle.setOnCheckedChangeListener(null);
        toggle.setChecked(checked);
        toggle.setEnabled(!busy);
        toggle.setOnCheckedChangeListener(listener);
    }

    private void render() {
        SharedPreferences p = Schedule.prefs(this);
        boolean on = p.getBoolean("enabled", false);
        bind(master, on, (button, checked) ->
                applyChange(() -> Schedule.configure(this, checked)));
        bind(morning, Schedule.slotEnabled(p, Schedule.AM), (button, checked) ->
                applyChange(() -> Schedule.setSlotEnabled(this, Schedule.AM, checked)));
        bind(evening, Schedule.slotEnabled(p, Schedule.PM), (button, checked) ->
                applyChange(() -> Schedule.setSlotEnabled(this, Schedule.PM, checked)));
        status.setText("总任务：" + (on ? "已启用" : "已停用"));
        morningRange.setText("时间：" + Schedule.clock(Schedule.windowStart(p, Schedule.AM))
                + "–" + Schedule.clock(Schedule.windowEnd(p, Schedule.AM)));
        eveningRange.setText("时间：" + Schedule.clock(Schedule.windowStart(p, Schedule.PM))
                + "–" + Schedule.clock(Schedule.windowEnd(p, Schedule.PM)));
        morningStatus.setText(Schedule.slotStatus(p, Schedule.AM, "早间"));
        eveningStatus.setText(Schedule.slotStatus(p, Schedule.PM, "晚间"));
        morningEdit.setEnabled(!busy);
        eveningEdit.setEnabled(!busy);
        reset.setEnabled(!busy);
        resetGroup.setVisibility(Schedule.defaultsChanged(p) ? View.VISIBLE : View.GONE);
    }

    private void applyChange(Runnable action) {
        busy = true;
        master.setEnabled(false);
        morning.setEnabled(false);
        evening.setEnabled(false);
        morningEdit.setEnabled(false);
        eveningEdit.setEnabled(false);
        reset.setEnabled(false);
        status.setText("正在保存配置…");
        new Thread(() -> {
            try {
                action.run();
                runOnUiThread(() -> {
                    busy = false;
                    render();
                });
            } catch (Exception e) {
                Log.e(Schedule.TAG, "Configuration failed", e);
                runOnUiThread(() -> {
                    busy = false;
                    render();
                    Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }, "AutoLarkConfigure").start();
    }

    private void showTimeDialog(String slot) {
        SharedPreferences p = Schedule.prefs(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(32, 20, 32, 0);
        TextView note = label(form, 14, 0);
        String formatHint = "使用 HH:mm:ss（00:00:00–23:59:59）；起止时间须在同一天内";
        note.setText(formatHint);
        label(form, 16, 18).setText("开始时间");
        EditText fromInput = new EditText(this);
        fromInput.setSingleLine(true);
        fromInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        fromInput.setSelectAllOnFocus(true);
        fromInput.setText(Schedule.clock(Schedule.windowStart(p, slot)));
        form.addView(fromInput);
        label(form, 16, 18).setText("结束时间");
        EditText toInput = new EditText(this);
        toInput.setSingleLine(true);
        toInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        toInput.setSelectAllOnFocus(true);
        toInput.setText(Schedule.clock(Schedule.windowEnd(p, slot)));
        form.addView(toInput);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(Schedule.AM.equals(slot) ? "修改早间时段" : "修改晚间时段")
                .setView(form).setPositiveButton("保存", null)
                .setNegativeButton("取消", null).create();
        dialog.setOnShowListener(ignored -> {
            Button saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Runnable refreshSave = () -> {
                String fromText = fromInput.getText().toString();
                String toText = toInput.getText().toString();
                boolean fromValid = Schedule.isValidClock(fromText);
                boolean toValid = Schedule.isValidClock(toText);
                fromInput.setError(fromValid ? null : "请输入有效的 HH:mm:ss 时间");
                toInput.setError(toValid ? null : "请输入有效的 HH:mm:ss 时间");
                boolean valid = fromValid && toValid;
                if (valid) {
                    try {
                        Schedule.validateWindow(this, slot,
                                Schedule.parseClock(fromText), Schedule.parseClock(toText));
                        note.setText(formatHint);
                    } catch (IllegalArgumentException e) {
                        valid = false;
                        note.setText(e.getMessage());
                    }
                } else {
                    note.setText("时间格式不正确：请输入 00:00:00–23:59:59");
                }
                saveButton.setEnabled(valid);
            };
            TextWatcher watcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start,
                                                         int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start,
                                                     int before, int count) {}
                @Override public void afterTextChanged(Editable s) { refreshSave.run(); }
            };
            fromInput.addTextChangedListener(watcher);
            toInput.addTextChangedListener(watcher);
            refreshSave.run();
            saveButton.setOnClickListener(v -> {
                    try {
                        int from = Schedule.parseClock(fromInput.getText().toString());
                        int to = Schedule.parseClock(toInput.getText().toString());
                        Schedule.validateWindow(this, slot, from, to);
                        dialog.dismiss();
                        applyChange(() -> Schedule.updateWindow(this, slot, from, to));
                    } catch (IllegalArgumentException e) {
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
        });
        dialog.show();
    }
}
