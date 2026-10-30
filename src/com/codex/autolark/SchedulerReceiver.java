package com.codex.autolark;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class SchedulerReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        try {
            Log.i(Schedule.TAG, "Receiver: " + intent.getAction());
            Schedule.reschedule(context);
        } catch (Exception e) {
            Log.e(Schedule.TAG, "Could not reschedule", e);
        }
    }
}
