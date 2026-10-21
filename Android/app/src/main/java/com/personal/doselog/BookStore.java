package com.personal.doselog;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/** Private, atomic local JSON file. A corrupt file is reported, never silently overwritten. */
public final class BookStore {
    private final AtomicFile file;
    public BookStore(Context context) { file = new AtomicFile(context.getFileStreamPath("medicine-book.json")); }

    public MedicineBook load() throws IOException, JSONException {
        if (!file.getBaseFile().exists()) return new MedicineBook();
        JSONObject root = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
        if (root.getInt("version") != 1) throw new JSONException("未知数据版本");
        MedicineBook book = new MedicineBook();
        JSONArray a = root.getJSONArray("medicines");
        for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.getJSONObject(i); MedicineBook.Medicine m = new MedicineBook.Medicine();
            m.id = j.getString("id"); m.name = j.getString("name"); m.strength = j.optString("strength");
            m.unit = j.getString("unit"); m.low = dec(j.getString("low"));
            m.stockKnown = j.getBoolean("stockKnown"); m.archived = j.optBoolean("archived"); book.medicines.add(m);
        }
        a = root.getJSONArray("plans");
        for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.getJSONObject(i); MedicineBook.Plan p = new MedicineBook.Plan();
            p.id = j.getString("id"); p.medicineId = j.getString("medicineId"); p.amount = dec(j.getString("amount"));
            p.interval = j.getBoolean("interval"); p.intervalHours = j.optInt("intervalHours"); p.anchor = j.optLong("anchor");
            p.activeFrom = j.getLong("activeFrom"); p.activeUntil = j.optLong("activeUntil", Long.MAX_VALUE);
            p.onDays = j.optInt("onDays"); p.offDays = j.optInt("offDays"); p.cycleAnchor = j.optLong("cycleAnchor");
            if (!j.isNull("earlyMinutes")) p.earlyMinutes = j.getInt("earlyMinutes");
            if (!j.isNull("lateMinutes")) p.lateMinutes = j.getInt("lateMinutes");
            if (!j.isNull("minimumGapHours")) p.minimumGapHours = j.getInt("minimumGapHours");
            p.followUpMinutes = j.optInt("followUpMinutes", 30); p.directionSource = j.optString("directionSource");
            JSONArray times = j.getJSONArray("dailyMinutes"); for (int n = 0; n < times.length(); n++) p.dailyMinutes.add(times.getInt(n));
            book.plans.add(p);
        }
        a = root.getJSONArray("doses");
        for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.getJSONObject(i); MedicineBook.Dose d = new MedicineBook.Dose();
            d.id = j.getString("id"); d.medicineId = j.getString("medicineId"); d.amount = dec(j.getString("amount"));
            d.planId = j.isNull("planId") ? null : j.getString("planId");
            d.scheduledAt = j.isNull("scheduledAt") ? null : j.getLong("scheduledAt");
            d.takenAt = j.getLong("takenAt"); d.enteredAt = j.getLong("enteredAt");
            d.backfilled = j.optBoolean("backfilled"); d.voided = j.optBoolean("voided"); book.doses.add(d);
        }
        a = root.getJSONArray("stock");
        for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.getJSONObject(i); MedicineBook.Stock s = new MedicineBook.Stock();
            s.id = j.getString("id"); s.medicineId = j.getString("medicineId"); s.delta = dec(j.getString("delta"));
            s.at = j.getLong("at"); s.correction = j.getBoolean("correction"); book.stock.add(s);
        }
        return book;
    }

    public void save(MedicineBook book) throws IOException, JSONException {
        JSONObject root = new JSONObject(); root.put("version", 1);
        JSONArray a = new JSONArray();
        for (MedicineBook.Medicine m : book.medicines) {
            JSONObject j = new JSONObject(); j.put("id", m.id); j.put("name", m.name); j.put("strength", m.strength);
            j.put("unit", m.unit); j.put("low", m.low.toPlainString()); j.put("stockKnown", m.stockKnown); j.put("archived", m.archived); a.put(j);
        }
        root.put("medicines", a); a = new JSONArray();
        for (MedicineBook.Plan p : book.plans) {
            JSONObject j = new JSONObject(); j.put("id", p.id); j.put("medicineId", p.medicineId); j.put("amount", p.amount.toPlainString());
            j.put("interval", p.interval); j.put("intervalHours", p.intervalHours); j.put("anchor", p.anchor);
            j.put("activeFrom", p.activeFrom); j.put("activeUntil", p.activeUntil);
            j.put("onDays", p.onDays); j.put("offDays", p.offDays); j.put("cycleAnchor", p.cycleAnchor);
            j.put("earlyMinutes", p.earlyMinutes == null ? JSONObject.NULL : p.earlyMinutes);
            j.put("lateMinutes", p.lateMinutes == null ? JSONObject.NULL : p.lateMinutes);
            j.put("minimumGapHours", p.minimumGapHours == null ? JSONObject.NULL : p.minimumGapHours);
            j.put("followUpMinutes", p.followUpMinutes); j.put("directionSource", p.directionSource);
            JSONArray times = new JSONArray(); for (int minute : p.dailyMinutes) times.put(minute); j.put("dailyMinutes", times); a.put(j);
        }
        root.put("plans", a); a = new JSONArray();
        for (MedicineBook.Dose d : book.doses) {
            JSONObject j = new JSONObject(); j.put("id", d.id); j.put("medicineId", d.medicineId); j.put("amount", d.amount.toPlainString());
            j.put("planId", d.planId == null ? JSONObject.NULL : d.planId);
            j.put("scheduledAt", d.scheduledAt == null ? JSONObject.NULL : d.scheduledAt);
            j.put("takenAt", d.takenAt); j.put("enteredAt", d.enteredAt); j.put("backfilled", d.backfilled); j.put("voided", d.voided); a.put(j);
        }
        root.put("doses", a); a = new JSONArray();
        for (MedicineBook.Stock s : book.stock) {
            JSONObject j = new JSONObject(); j.put("id", s.id); j.put("medicineId", s.medicineId); j.put("delta", s.delta.toPlainString());
            j.put("at", s.at); j.put("correction", s.correction); a.put(j);
        }
        root.put("stock", a);
        FileOutputStream out = null;
        try {
            out = file.startWrite(); out.write(root.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(out);
        } catch (IOException e) { if (out != null) file.failWrite(out); throw e; }
    }

    private static BigDecimal dec(String value) throws JSONException {
        try { return new BigDecimal(value); } catch (NumberFormatException e) { throw new JSONException("数量格式错误"); }
    }
}
