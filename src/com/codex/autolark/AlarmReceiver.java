package com.codex.autolark;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

public final class AlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        final PendingResult result = goAsync();
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "AutoLark:alarm");
        lock.acquire(20_000L);
        new Thread(() -> {
            try {
                Log.i(Schedule.TAG, "Alarm broadcast: " + intent.getStringExtra("slot")
                        + " " + intent.getStringExtra("event"));
                Schedule.handle(context, intent);
            } catch (Exception e) {
                Log.e(Schedule.TAG, "Alarm execution failed", e);
                try { Schedule.reschedule(context); }
                catch (Exception retry) { Log.e(Schedule.TAG, "Reschedule failed", retry); }
            } finally {
                if (lock.isHeld()) lock.release();
                result.finish();
            }
        }, "AutoLarkAlarm").start();
    }
}
