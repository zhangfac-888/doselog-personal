package com.personal.doselog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class RescheduleReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        try { Reminders.sync(context, new BookStore(context).load()); }
        catch (Exception ignored) { /* Opening the app reports storage errors. */ }
    }
}
