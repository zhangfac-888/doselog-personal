package com.personal.doselog;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

public final class Reminders {
    private static final String CHANNEL = "doses";
    private static final String PREFS = "scheduled-alarms";
    private Reminders() {}

    static void channel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "服药与库存提醒", NotificationManager.IMPORTANCE_HIGH));
    }

    static boolean canNotify(Context context) {
        return Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean exactAllowed(Context context) {
        AlarmManager alarm = context.getSystemService(AlarmManager.class);
        return Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms();
    }

    static void sync(Context context, MedicineBook book) {
        channel(context);
        AlarmManager alarm = context.getSystemService(AlarmManager.class);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> previous = prefs.getStringSet("keys", new HashSet<>());
        Set<String> desired = new HashSet<>();
        long now = System.currentTimeMillis();
        for (MedicineBook.Due due : book.scheduled(now + 1000, now + 48L * 3_600_000, ZoneId.systemDefault())) {
            if (book.recorded(due)) continue;
            String key = "due:" + due.key(); desired.add(key);
            MedicineBook.Plan p = book.plan(due.planId);
            if (p.followUpMinutes > 0) desired.add("follow:" + due.key());
        }
        for (String key : previous) if (!desired.contains(key)) alarm.cancel(pending(context, key));
        for (String key : desired) if (!previous.contains(key)) {
            String[] parts = key.split(":", 3);
            long dueAt = Long.parseLong(parts[2]);
            MedicineBook.Plan p = book.plan(parts[1]);
            if (p == null) continue;
            long fire = dueAt + (parts[0].equals("follow") ? p.followUpMinutes * 60_000L : 0);
            if (fire <= now) continue;
            try {
                if (exactAllowed(context)) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fire, pending(context, key));
                else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fire, pending(context, key));
            } catch (SecurityException ignored) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fire, pending(context, key));
            }
        }
        prefs.edit().putStringSet("keys", desired).apply();
    }

    static PendingIntent pending(Context context, String key) {
        Intent intent = new Intent(context, ReminderReceiver.class).setAction("com.personal.doselog.ALARM").putExtra("key", key);
        intent.setData(android.net.Uri.parse("doselog://alarm/" + key));
        return PendingIntent.getBroadcast(context, key.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void post(Context context, String title, String body, int id) {
        if (!canNotify(context)) return;
        channel(context);
        PendingIntent open = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(title).setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true).build();
        context.getSystemService(NotificationManager.class).notify(id, notification);
    }

    static void lowStock(Context context, MedicineBook book, MedicineBook.Medicine medicine) {
        if (medicine.stockKnown && book.balance(medicine.id).compareTo(medicine.low) < 0)
            post(context, medicine.name + " 库存偏低", "剩余 " + book.balance(medicine.id).stripTrailingZeros().toPlainString() + " " + medicine.unit + "，请核对并补货。", medicine.id.hashCode());
    }
}
