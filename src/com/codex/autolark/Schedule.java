package com.codex.autolark;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

final class Schedule {
    static final String TAG = "AutoLark";
    static final String AM = "am";
    static final String PM = "pm";
    private static final String ACTION_ALARM = "com.codex.autolark.ALARM";
    private static final String OPEN = "open";
    private static final String CLOSE = "close";
    private static final String DONE = "done";
    private static final long OPEN_MS = 30_000L;
    private static final long GAP_MS = 30_000L;
    private static final int AM_START = 9 * 3600;
    private static final int AM_END = AM_START + 29 * 60 + 59;
    private static final int PM_START = 21 * 3600;
    private static final int PM_END = PM_START + 29 * 60 + 59;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern CLOCK_PATTERN = Pattern.compile(
            "^(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$");

    private Schedule() {}

    static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences("schedule", Context.MODE_PRIVATE);
    }

    private static void save(SharedPreferences.Editor editor) {
        if (!editor.commit()) throw new IllegalStateException("Could not save schedule");
    }

    private static synchronized void migrate(Context context) {
        SharedPreferences p = prefs(context);
        int schema = p.getInt("schema", 0);
        if (schema >= 4) return;
        if (schema == 2) {
            cancel(context, PM);
            save(p.edit().putInt("schema", 3).remove("qa.end")
                    .remove(PM + ".date").remove(PM + ".zone")
                    .remove(PM + ".start").remove(PM + ".next")
                    .remove(PM + ".event").remove(PM + ".done")
                    .remove(PM + ".rounds"));
            Log.i(TAG, "Removed integration probe and reselected evening start");
            schema = 3;
        }
        if (schema < 3) {
            boolean enabled = p.contains("mode")
                    ? "prod".equals(p.getString("mode", "off"))
                    : p.getBoolean("enabled", true);
            cancel(context, "test");
            cancel(context, AM);
            cancel(context, PM);
            SharedPreferences.Editor e = p.edit().putInt("schema", 3)
                    .putBoolean("enabled", enabled)
                    .remove("mode").remove("test.when").remove("test.count")
                    .remove("test.last");
            for (String slot : new String[] { AM, PM }) {
                e.remove(slot + ".date").remove(slot + ".zone")
                        .remove(slot + ".when").remove(slot + ".done")
                        .remove(slot + ".last");
                clearRuntime(e, slot);
            }
            save(e);
        }
        // The first release with editable windows starts from the requested defaults.
        if (CLOSE.equals(p.getString(PM + ".event", ""))) {
            RootActions.Result result = RootActions.closeLark(
                    p.getInt(PM + ".timeout", 300_000));
            if (result.code != 0) throw new IllegalStateException(
                    "Could not close previous evening session: " + result.output);
        }
        cancel(context, PM);
        SharedPreferences.Editor e = p.edit().putInt("schema", 4)
                .putInt(AM + ".windowStart", AM_START)
                .putInt(AM + ".windowEnd", AM_END)
                .putBoolean(AM + ".enabled", true)
                .putInt(PM + ".windowStart", PM_START)
                .putInt(PM + ".windowEnd", PM_END)
                .putBoolean(PM + ".enabled", false);
        clearRuntime(e, PM);
        save(e);
        Log.i(TAG, "Migrated editable windows; morning on, evening off");
    }

    private static void clearRuntime(SharedPreferences.Editor e, String slot) {
        e.remove(slot + ".date").remove(slot + ".zone")
                .remove(slot + ".start").remove(slot + ".next")
                .remove(slot + ".event").remove(slot + ".done")
                .remove(slot + ".rounds");
    }

    static int defaultStart(String slot) { return AM.equals(slot) ? AM_START : PM_START; }
    static int defaultEnd(String slot) { return AM.equals(slot) ? AM_END : PM_END; }
    static boolean defaultEnabled(String slot) { return AM.equals(slot); }
    static int windowStart(SharedPreferences p, String slot) {
        return p.getInt(slot + ".windowStart", defaultStart(slot));
    }
    static int windowEnd(SharedPreferences p, String slot) {
        return p.getInt(slot + ".windowEnd", defaultEnd(slot));
    }
    static boolean slotEnabled(SharedPreferences p, String slot) {
        return p.getBoolean(slot + ".enabled", defaultEnabled(slot));
    }
    static boolean defaultsChanged(SharedPreferences p) {
        if (!p.getBoolean("enabled", true)) return true;
        for (String slot : new String[] { AM, PM }) {
            if (slotEnabled(p, slot) != defaultEnabled(slot)
                    || windowStart(p, slot) != defaultStart(slot)
                    || windowEnd(p, slot) != defaultEnd(slot)) return true;
        }
        return false;
    }

    static String clock(int seconds) {
        return String.format(java.util.Locale.US, "%02d:%02d:%02d",
                seconds / 3600, (seconds / 60) % 60, seconds % 60);
    }

    static boolean isValidClock(CharSequence value) {
        return value != null && CLOCK_PATTERN.matcher(value).matches();
    }

    static int parseClock(String value) {
        if (!isValidClock(value))
            throw new IllegalArgumentException("请使用 HH:mm:ss 格式（00:00:00–23:59:59）");
        int hour = Integer.parseInt(value.substring(0, 2));
        return hour * 3600 + Integer.parseInt(value.substring(3, 5)) * 60
                + Integer.parseInt(value.substring(6, 8));
    }

    private static String other(String slot) { return AM.equals(slot) ? PM : AM; }

    private static void validateWindow(SharedPreferences p, String slot,
                                       int from, int to, boolean enabled) {
        if (!AM.equals(slot) && !PM.equals(slot))
            throw new IllegalArgumentException("未知时段");
        if (from < 0 || to > 86_399 || from > to)
            throw new IllegalArgumentException("起止时间须在同一天内，且开始不晚于结束");
        String other = other(slot);
        if (enabled && slotEnabled(p, other)
                && from <= windowEnd(p, other)
                && windowStart(p, other) <= to)
            throw new IllegalArgumentException("两个启用时段不能重叠");
    }

    static void validateWindow(Context context, String slot, int from, int to) {
        SharedPreferences p = prefs(context);
        validateWindow(p, slot, from, to, slotEnabled(p, slot));
    }

    static synchronized void configure(Context context, boolean enabled) {
        migrate(context);
        SharedPreferences p = prefs(context);
        if (enabled == p.getBoolean("enabled", false)) return;
        cancel(context, AM);
        cancel(context, PM);
        if (!enabled) {
            SharedPreferences.Editor e = p.edit().putBoolean("enabled", false);
            for (String slot : new String[] { AM, PM }) {
                if (CLOSE.equals(p.getString(slot + ".event", ""))) {
                    RootActions.Result result = RootActions.closeLark(
                            p.getInt(slot + ".timeout", 300_000));
                    Log.i(TAG, "Stopped active " + slot + " exit=" + result.code
                            + " output=" + result.output);
                    if (result.code != 0) {
                        reschedule(context);
                        throw new IllegalStateException("Could not close Lark: " + result.output);
                    }
                }
                e.remove(slot + ".event").remove(slot + ".next");
            }
            save(e);
        } else {
            SharedPreferences.Editor e = p.edit().putBoolean("enabled", true);
            for (String slot : new String[] { AM, PM }) {
                clearRuntime(e, slot);
            }
            save(e);
            reschedule(context);
        }
        Log.i(TAG, "Enabled=" + enabled);
    }

    private static void closeIfActive(Context context, SharedPreferences p, String slot) {
        if (!CLOSE.equals(p.getString(slot + ".event", ""))) return;
        RootActions.Result result = RootActions.closeLark(p.getInt(slot + ".timeout", 300_000));
        Log.i(TAG, "Configuration closed " + slot + " exit=" + result.code);
        if (result.code != 0) {
            reschedule(context);
            throw new IllegalStateException("关闭飞书失败：" + result.output);
        }
    }

    static synchronized void setSlotEnabled(Context context, String slot, boolean enabled) {
        migrate(context);
        SharedPreferences p = prefs(context);
        if (!AM.equals(slot) && !PM.equals(slot))
            throw new IllegalArgumentException("未知时段");
        if (enabled == slotEnabled(p, slot)) return;
        validateWindow(p, slot, windowStart(p, slot), windowEnd(p, slot), enabled);
        cancel(context, slot);
        closeIfActive(context, p, slot);
        SharedPreferences.Editor e = p.edit().putBoolean(slot + ".enabled", enabled);
        clearRuntime(e, slot);
        save(e);
        reschedule(context);
        Log.i(TAG, "Slot " + slot + " enabled=" + enabled);
    }

    static synchronized void updateWindow(Context context, String slot, int from, int to) {
        migrate(context);
        SharedPreferences p = prefs(context);
        validateWindow(p, slot, from, to, slotEnabled(p, slot));
        if (from == windowStart(p, slot) && to == windowEnd(p, slot)) return;
        cancel(context, slot);
        closeIfActive(context, p, slot);
        SharedPreferences.Editor e = p.edit()
                .putInt(slot + ".windowStart", from)
                .putInt(slot + ".windowEnd", to);
        clearRuntime(e, slot);
        save(e);
        reschedule(context);
        Log.i(TAG, "Window " + slot + " " + clock(from) + "–" + clock(to));
    }

    static synchronized void resetDefaults(Context context) {
        migrate(context);
        SharedPreferences p = prefs(context);
        cancel(context, AM);
        cancel(context, PM);
        for (String slot : new String[] { AM, PM }) closeIfActive(context, p, slot);
        SharedPreferences.Editor e = p.edit().putBoolean("enabled", true);
        for (String slot : new String[] { AM, PM }) {
            e.putBoolean(slot + ".enabled", defaultEnabled(slot))
                    .putInt(slot + ".windowStart", defaultStart(slot))
                    .putInt(slot + ".windowEnd", defaultEnd(slot));
            clearRuntime(e, slot);
        }
        save(e);
        reschedule(context);
        Log.i(TAG, "Reset to default configuration");
    }

    static synchronized void reschedule(Context context) {
        migrate(context);
        SharedPreferences p = prefs(context);
        if (!p.getBoolean("enabled", false)) {
            cancel(context, AM);
            cancel(context, PM);
            Log.i(TAG, "Schedule disabled");
            return;
        }
        for (String slot : new String[] { AM, PM }) {
            if (slotEnabled(p, slot)) scheduleSlot(context, slot);
            else cancel(context, slot);
        }
    }

    private static long start(Context context, LocalDate date, String slot, ZoneId zone) {
        return date.atTime(LocalTime.ofSecondOfDay(
                windowStart(prefs(context), slot))).atZone(zone).toInstant().toEpochMilli();
    }

    private static long endExclusive(Context context, LocalDate date, String slot, ZoneId zone) {
        return date.atTime(LocalTime.ofSecondOfDay(
                windowEnd(prefs(context), slot))).atZone(zone).toInstant().toEpochMilli()
                + 1_000L;
    }

    private static void scheduleSlot(Context context, String slot) {
        SharedPreferences p = prefs(context);
        long now = System.currentTimeMillis();
        ZoneId zone = ZoneId.systemDefault();
        String savedDate = p.getString(slot + ".date", "");
        String savedZone = p.getString(slot + ".zone", "");
        String event = p.getString(slot + ".event", "");
        long next = p.getLong(slot + ".next", 0L);

        // An opened round must finish even if the time window has ended.
        if (CLOSE.equals(event) && !savedDate.isEmpty()) {
            arm(context, slot, savedDate, CLOSE, next, Math.max(next, now + 1_000L));
            return;
        }
        if (OPEN.equals(event) && !savedDate.isEmpty()
                && zone.getId().equals(savedZone)) {
            LocalDate date = LocalDate.parse(savedDate);
            if (now < endExclusive(context, date, slot, zone) && next > 0L) {
                arm(context, slot, savedDate, OPEN, next, Math.max(next, now + 1_000L));
                return;
            }
            save(p.edit().putString(slot + ".done", savedDate)
                    .putString(slot + ".event", DONE).remove(slot + ".next"));
        }

        LocalDate today = LocalDate.now(zone);
        for (int offset = 0; offset < 3; offset++) {
            LocalDate date = today.plusDays(offset);
            String day = date.toString();
            if (day.equals(p.getString(slot + ".done", ""))) continue;
            long earliest = Math.max(start(context, date, slot, zone), now + 5_000L);
            long end = endExclusive(context, date, slot, zone);
            if (earliest >= end) continue;
            long when = earliest + RANDOM.nextInt((int) (end - earliest));
            save(p.edit().putString(slot + ".date", day)
                    .putString(slot + ".zone", zone.getId())
                    .putString(slot + ".event", OPEN)
                    .putLong(slot + ".start", when)
                    .putLong(slot + ".next", when)
                    .putInt(slot + ".rounds", 0));
            arm(context, slot, day, OPEN, when, when);
            return;
        }
        Log.w(TAG, "No upcoming window for " + slot);
    }

    private static PendingIntent pending(Context context, String slot, String date,
                                         String event, long token, int flags) {
        Intent intent = new Intent(context, AlarmReceiver.class)
                .setAction(ACTION_ALARM + "." + slot)
                .putExtra("slot", slot).putExtra("date", date)
                .putExtra("event", event).putExtra("token", token);
        int id = AM.equals(slot) ? 2 : PM.equals(slot) ? 3 : 1;
        return PendingIntent.getBroadcast(context, id, intent,
                flags | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void arm(Context context, String slot, String date, String event,
                            long token, long when) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent operation = pending(context, slot, date, event, token,
                PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent display = PendingIntent.getActivity(context, 4,
                new Intent(context, ControllerActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.setAlarmClock(new AlarmManager.AlarmClockInfo(when, display), operation);
        Log.i(TAG, "Armed " + slot + " " + event + " for " + format(when));
    }

    private static void cancel(Context context, String slot) {
        PendingIntent operation = pending(context, slot, "", "", 0L,
                PendingIntent.FLAG_NO_CREATE);
        if (operation != null) {
            ((AlarmManager) context.getSystemService(Context.ALARM_SERVICE)).cancel(operation);
            operation.cancel();
        }
    }

    static synchronized void handle(Context context, Intent intent) {
        migrate(context);
        String slot = intent.getStringExtra("slot");
        if (!AM.equals(slot) && !PM.equals(slot)) return;
        SharedPreferences p = prefs(context);
        if (!p.getBoolean("enabled", false) || !slotEnabled(p, slot)) return;
        String date = intent.getStringExtra("date");
        String event = intent.getStringExtra("event");
        long token = intent.getLongExtra("token", 0L);
        if (date == null || event == null
                || !date.equals(p.getString(slot + ".date", ""))
                || !event.equals(p.getString(slot + ".event", ""))
                || token != p.getLong(slot + ".next", -1L)) {
            Log.w(TAG, "Ignored stale alarm " + slot + " " + event);
            return;
        }
        LocalDate day = LocalDate.parse(date);
        long end = endExclusive(context, day, slot, ZoneId.systemDefault());
        long now = System.currentTimeMillis();
        if (OPEN.equals(event)) {
            long windowBegin = start(context, day, slot, ZoneId.systemDefault());
            if (now < windowBegin) {
                arm(context, slot, date, OPEN, token, Math.max(token, windowBegin));
                return;
            }
            if (now >= end) {
                finishDay(context, slot, date);
                return;
            }
            int timeout = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, 300_000);
            RootActions.Result result = RootActions.openLark(timeout);
            Log.i(TAG, "Open " + slot + " exit=" + result.code + " output=" + result.output);
            if (result.code == 0) {
                long closeAt = System.currentTimeMillis() + OPEN_MS;
                save(p.edit().putString(slot + ".event", CLOSE)
                        .putLong(slot + ".next", closeAt)
                        .putInt(slot + ".timeout", timeout)
                        .putInt(slot + ".rounds", p.getInt(slot + ".rounds", 0) + 1)
                        .putLong(slot + ".lastOpen", System.currentTimeMillis())
                        .remove(slot + ".error"));
                arm(context, slot, date, CLOSE, closeAt, closeAt);
            } else {
                RootActions.Result cleanup = RootActions.closeLark(timeout);
                save(p.edit().putString(slot + ".error", "打开失败：" + result.output));
                Log.e(TAG, "Open failed; cleanup exit=" + cleanup.code);
                if (cleanup.code == 0) {
                    scheduleNextOpenOrFinish(context, slot, date, System.currentTimeMillis());
                } else {
                    long retry = System.currentTimeMillis() + 5_000L;
                    save(p.edit().putString(slot + ".event", CLOSE)
                            .putLong(slot + ".next", retry)
                            .putInt(slot + ".timeout", timeout));
                    arm(context, slot, date, CLOSE, retry, retry);
                }
            }
        } else if (CLOSE.equals(event)) {
            int timeout = p.getInt(slot + ".timeout", 300_000);
            RootActions.Result result = RootActions.closeLark(timeout);
            Log.i(TAG, "Close " + slot + " exit=" + result.code + " output=" + result.output);
            if (result.code != 0) {
                long retry = System.currentTimeMillis() + 5_000L;
                save(p.edit().putLong(slot + ".next", retry)
                        .putString(slot + ".error", "关闭失败：" + result.output));
                arm(context, slot, date, CLOSE, retry, retry);
                return;
            }
            long closedAt = System.currentTimeMillis();
            save(p.edit().putLong(slot + ".lastClose", closedAt).remove(slot + ".error"));
            scheduleNextOpenOrFinish(context, slot, date, closedAt);
        }
    }

    private static void scheduleNextOpenOrFinish(Context context, String slot,
                                                  String date, long closedAt) {
        long next = closedAt + GAP_MS;
        long end = endExclusive(context, LocalDate.parse(date), slot,
                ZoneId.systemDefault());
        if (next >= end) {
            finishDay(context, slot, date);
            return;
        }
        save(prefs(context).edit().putString(slot + ".event", OPEN)
                .putLong(slot + ".next", next));
        arm(context, slot, date, OPEN, next, next);
    }

    private static void finishDay(Context context, String slot, String date) {
        save(prefs(context).edit().putString(slot + ".done", date)
                .putString(slot + ".event", DONE).remove(slot + ".next"));
        Log.i(TAG, "Window finished " + slot + " " + date);
        scheduleSlot(context, slot);
    }

    static String format(long millis) {
        if (millis <= 0) return "未设置";
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .format(ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis),
                        ZoneId.systemDefault()));
    }

    static String slotStatus(SharedPreferences p, String slot, String label) {
        String event = p.getString(slot + ".event", "");
        String phase = !slotEnabled(p, slot) ? "已禁用" :
                !p.getBoolean("enabled", false) ? "总任务已暂停" :
                CLOSE.equals(event) ? "飞书打开中" :
                OPEN.equals(event) && p.getInt(slot + ".rounds", 0) > 0 ? "等待下一轮" :
                OPEN.equals(event) ? "等待随机开始" : "已完成";
        return label + "：" + phase
                + (slotEnabled(p, slot)
                    ? "\n随机起点：" + format(p.getLong(slot + ".start", 0L))
                        + "\n下一动作：" + format(p.getLong(slot + ".next", 0L))
                    : "")
                + "\n最近打开：" + format(p.getLong(slot + ".lastOpen", 0L))
                + "\n本时段轮次：" + p.getInt(slot + ".rounds", 0)
                + (p.contains(slot + ".error") ? "\n异常：" + p.getString(slot + ".error", "") : "");
    }
}
