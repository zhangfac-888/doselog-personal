package com.personal.doselog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.time.ZoneId;

public final class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        try {
            MedicineBook book = new BookStore(context).load();
            String key = intent.getStringExtra("key");
            if (key != null) {
                String[] parts = key.split(":", 3);
                if (parts.length == 3) {
                    MedicineBook.Plan plan = book.plan(parts[1]);
                    long at = Long.parseLong(parts[2]);
                    MedicineBook.Medicine medicine = plan == null ? null : book.medicine(plan.medicineId);
                    if (medicine != null && !medicine.archived) {
                        MedicineBook.Due due = new MedicineBook.Due(plan, at);
                        if (!book.recorded(due)) {
                            String title = parts[0].equals("follow") ? "请核对是否已服药" : "到服药时间了";
                            Reminders.post(context, title, medicine.label() + " · " + plan.amount.stripTrailingZeros().toPlainString() + medicine.unit
                                    + " · 计划 " + MedicineBook.fmt(at, ZoneId.systemDefault()), key.hashCode());
                        }
                    }
                }
            }
            Reminders.sync(context, book);
        } catch (Exception ignored) { /* Preserve data; opening the app shows the read error. */ }
    }
}
