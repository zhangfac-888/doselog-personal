package com.personal.doselog;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;

public final class MainActivity extends Activity {
    private MedicineBook book;
    private BookStore store;
    private LinearLayout root;
    private String tab = "今日";
    private final ZoneId zone = ZoneId.systemDefault();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new BookStore(this);
        try { book = store.load(); Reminders.sync(this, book); }
        catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("本机数据读取失败")
                    .setMessage("数据不会被覆盖。请保留应用，并联系开发者处理。\n" + e.getMessage())
                    .setPositiveButton("知道了", (d, w) -> finish()).setCancelable(false).show();
            return;
        }
        render();
    }

    @Override public void onResume() { super.onResume(); if (book != null) render(); }

    private void save() {
        try { store.save(book); Reminders.sync(this, book); render(); }
        catch (Exception e) { Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }

    private TextView text(String value, int size, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(Color.rgb(29, 39, 48));
        if (bold) t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, dp(5), 0, dp(5)); return t;
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setOnClickListener(v -> action.run()); return b;
    }

    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }

    private void section(String title) {
        TextView t = text(title, 20, true); t.setPadding(0, dp(18), 0, dp(8)); root.addView(t);
    }

    private void render() {
        if (book == null) return;
        ScrollView scroll = new ScrollView(this); root = column(); root.setPadding(dp(18), dp(12), dp(18), dp(35)); scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(0, bars.top, 0, bars.bottom); return insets;
        });
        setContentView(scroll);
        root.addView(text("用药记录", 28, true));
        root.addView(text("仅保存在本机 · 记录的是实际发生的时间", 13, false));
        LinearLayout tabs = new LinearLayout(this);
        for (String name : new String[]{"今日", "药品计划", "七天核对"}) {
            Button b = button(name, () -> { tab = name; render(); });
            b.setEnabled(!tab.equals(name)); tabs.addView(b, new LinearLayout.LayoutParams(0, dp(48), 1));
        }
        root.addView(tabs);
        switch (tab) { case "药品计划": showPlans(); break; case "七天核对": showAudit(); break; default: showToday(); }
    }

    private void showToday() {
        long now = System.currentTimeMillis();
        section("一键记录");
        if (book.medicines.stream().noneMatch(m -> !m.archived)) root.addView(text("先到“药品计划”添加药品和用药计划。", 15, false));
        for (MedicineBook.Medicine m : book.medicines) {
            if (m.archived) continue;
            MedicineBook.Due due = book.closestDue(m.id, now, zone);
            String label = due == null ? "" : " · 本次计划 " + MedicineBook.fmt(due.at, zone) + " / " + dose(due.amount) + m.unit;
            root.addView(text(m.label() + label, 16, true));
            if (due != null) root.addView(button("已服 " + m.name + " " + dose(due.amount) + m.unit + " · 记当前时间", () -> {
                book.take(m.id, due.amount, System.currentTimeMillis(), System.currentTimeMillis(), zone);
                save(); Reminders.lowStock(this, book, m);
            }));
            else root.addView(button("记录 " + m.name + "（选择剂量）", () -> manualDose(m)));
            if (m.stockKnown) root.addView(text("库存 " + dose(book.balance(m.id)) + m.unit + (book.balance(m.id).compareTo(m.low) < 0 ? " · 低于安全线" : ""), 13, false));
        }
        root.addView(button("补录过去的服药", this::backfill));
        section("今天的记录");
        LocalDate day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        List<MedicineBook.Dose> today = new ArrayList<>();
        for (MedicineBook.Dose d : book.doses) if (!d.voided && Instant.ofEpochMilli(d.takenAt).atZone(zone).toLocalDate().equals(day)) today.add(d);
        today.sort(Comparator.comparingLong(d -> d.takenAt));
        if (today.isEmpty()) root.addView(text("今天还没有记录。", 15, false));
        for (MedicineBook.Dose d : today) {
            MedicineBook.Medicine m = book.medicine(d.medicineId);
            root.addView(text(MedicineBook.fmt(d.takenAt, zone) + "  " + (m == null ? "已删除药品" : m.label()) + "  " + dose(d.amount) + (m == null ? "" : m.unit) + (d.backfilled ? " · 补录" : ""), 15, false));
            root.addView(button("撤销这条记录", () -> { d.voided = true; save(); }));
        }
        root.addView(text("误触可撤销；撤销后库存会自动恢复。", 13, false));
    }

    private void showPlans() {
        section("药品与计划");
        root.addView(button("添加药品", this::addMedicine));
        for (MedicineBook.Medicine m : book.medicines) {
            if (m.archived) continue;
            section(m.label());
            root.addView(text("库存单位：" + m.unit + " · 安全线：" + dose(m.low) + (m.stockKnown ? " · 现有：" + dose(book.balance(m.id)) : " · 尚未盘点"), 14, false));
            root.addView(button("盘点 / 补货", () -> stockDialog(m)));
            root.addView(button("添加用药计划", () -> addPlan(m)));
            for (MedicineBook.Plan p : book.plans) if (p.medicineId.equals(m.id)) {
                String timing = p.interval ? "每 " + p.intervalHours + " 小时" : "每天 " + times(p.dailyMinutes);
                String cycle = p.onDays > 0 ? " · 用 " + p.onDays + " 天 / 停 " + p.offDays + " 天" : "";
                root.addView(text(timing + " · 每次 " + dose(p.amount) + m.unit + cycle, 15, false));
                root.addView(button("停止此计划", () -> new AlertDialog.Builder(this).setMessage("停止后不再生成新的提醒，原记录保留。")
                        .setNegativeButton("取消", null).setPositiveButton("停止", (d, w) -> { p.activeUntil = System.currentTimeMillis(); save(); }).show()));
            }
        }
        section("提醒状态");
        root.addView(text(Reminders.canNotify(this) ? "通知：已允许" : "通知：未允许，无法弹出提醒", 14, false));
        if (!Reminders.canNotify(this)) root.addView(button("允许通知", () -> {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 11);
        }));
        root.addView(text(Reminders.exactAllowed(this) ? "精确闹钟：已允许" : "精确闹钟：未允许，提醒可能明显延迟", 14, false));
        if (!Reminders.exactAllowed(this)) root.addView(button("允许精确提醒", () -> {
            if (Build.VERSION.SDK_INT >= 31) startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:" + getPackageName())));
        }));
    }

    private void showAudit() {
        section("近七天核对");
        root.addView(text("只按你录入的计划和记录提示差异；不能判断医嘱是否正确，也不能证明你实际服过药。", 14, false));
        List<MedicineBook.Finding> findings = book.audit(System.currentTimeMillis(), zone);
        if (findings.isEmpty()) root.addView(text("目前没有发现需核对的记录。", 15, false));
        for (MedicineBook.Finding f : findings) {
            MedicineBook.Medicine m = book.medicine(f.medicineId);
            root.addView(text((m == null ? "药品" : m.name) + " · " + f.title, 16, true));
            root.addView(text(f.detail, 14, false));
        }
        section("最近记录");
        List<MedicineBook.Dose> history = new ArrayList<>();
        long start = System.currentTimeMillis() - 7L * 24 * 3_600_000;
        for (MedicineBook.Dose d : book.doses) if (!d.voided && d.takenAt >= start) history.add(d);
        history.sort((a, b) -> Long.compare(b.takenAt, a.takenAt));
        for (MedicineBook.Dose d : history) {
            MedicineBook.Medicine m = book.medicine(d.medicineId);
            root.addView(text(MedicineBook.fmt(d.takenAt, zone) + " · " + (m == null ? "药品" : m.label()) + " " + dose(d.amount), 14, false));
            root.addView(button("撤销", () -> { d.voided = true; save(); }));
        }
    }

    private EditText field(LinearLayout box, String hint, String value) {
        box.addView(text(hint, 14, false)); EditText edit = new EditText(this); edit.setSingleLine(true); edit.setText(value); box.addView(edit); return edit;
    }

    private void form(String title, LinearLayout box, Runnable submit) {
        new AlertDialog.Builder(this).setTitle(title).setView(box).setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> { try { submit.run(); } catch (Exception e) { new AlertDialog.Builder(this).setMessage("输入有误：" + e.getMessage()).setPositiveButton("知道了", null).show(); } }).show();
    }

    private void addMedicine() {
        LinearLayout box = column(); box.setPadding(dp(18), dp(5), dp(18), 0);
        EditText name = field(box, "药品名称", ""); EditText strength = field(box, "规格（例如 10 mg）", "");
        EditText unit = field(box, "计数单位（片、粒、毫升等）", "片"); EditText low = field(box, "库存安全线", "5");
        form("添加药品", box, () -> {
            if (name.getText().toString().trim().isEmpty()) throw new IllegalArgumentException("请填写药品名");
            MedicineBook.Medicine m = new MedicineBook.Medicine(); m.name = name.getText().toString().trim();
            m.strength = strength.getText().toString().trim(); m.unit = unit.getText().toString().trim();
            if (m.unit.isEmpty()) throw new IllegalArgumentException("请填写计数单位");
            m.low = positiveOrZero(low.getText().toString()); book.medicines.add(m); save();
        });
    }

    private void addPlan(MedicineBook.Medicine m) {
        LinearLayout box = column(); box.setPadding(dp(18), dp(5), dp(18), 0);
        EditText amount = field(box, "每次服用多少 " + m.unit, "1");
        EditText daily = field(box, "每天的时间，逗号隔开（例如 08:00,20:00）。若按小时间隔服用则留空", "08:00");
        EditText interval = field(box, "按间隔服用：每隔多少小时（每天定时则留空）", "");
        EditText on = field(box, "周期用药：用药天数（无周期则留空）", "");
        EditText off = field(box, "周期用药：停药天数", "");
        EditText early = field(box, "允许提前分钟数（未规定可留空）", "");
        EditText late = field(box, "允许延后分钟数（未规定可留空）", "");
        EditText gap = field(box, "最短服药间隔小时数（未规定可留空）", "");
        EditText source = field(box, "医嘱来源备注", "");
        ScrollView scroll = new ScrollView(this); scroll.addView(box);
        new AlertDialog.Builder(this).setTitle(m.name + " 的用药计划").setView(scroll).setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        MedicineBook.Plan p = new MedicineBook.Plan(); p.medicineId = m.id;
                        p.amount = positive(amount.getText().toString()); p.activeFrom = System.currentTimeMillis(); p.anchor = p.activeFrom;
                        String h = interval.getText().toString().trim();
                        if (!h.isEmpty()) {
                            p.interval = true; p.intervalHours = Integer.parseInt(h);
                            if (p.intervalHours < 1 || p.intervalHours > 168) throw new IllegalArgumentException("间隔须为 1～168 小时");
                        } else {
                            for (String part : daily.getText().toString().split(",")) {
                                LocalTime time = LocalTime.parse(part.trim()); p.dailyMinutes.add(time.getHour() * 60 + time.getMinute());
                            }
                            if (p.dailyMinutes.isEmpty()) throw new IllegalArgumentException("请输入每日时间");
                        }
                        p.onDays = optionalInt(on); p.offDays = optionalInt(off); p.cycleAnchor = p.activeFrom;
                        if ((p.onDays == 0) != (p.offDays == 0) || p.onDays > 365 || p.offDays > 365)
                            throw new IllegalArgumentException("周期需同时填写用药和停药天数，且不超过 365 天");
                        p.earlyMinutes = optional(early); p.lateMinutes = optional(late); p.minimumGapHours = optional(gap);
                        p.directionSource = source.getText().toString().trim(); book.plans.add(p); save();
                    } catch (Exception e) { new AlertDialog.Builder(this).setMessage("计划输入有误：" + e.getMessage()).setPositiveButton("知道了", null).show(); }
                }).show();
    }

    private void stockDialog(MedicineBook.Medicine m) {
        String[] choices = {"盘点：把现有数量设为", "补货：在现有数量上增加"};
        new AlertDialog.Builder(this).setTitle(m.name + " 库存").setItems(choices, (d, which) -> {
            EditText edit = new EditText(this); edit.setSingleLine(true); edit.setHint("数量（" + m.unit + "）");
            new AlertDialog.Builder(this).setTitle(choices[which]).setView(edit).setNegativeButton("取消", null)
                    .setPositiveButton("保存", (dialog, w) -> {
                        try {
                            MedicineBook.Stock s = new MedicineBook.Stock(); s.medicineId = m.id;
                            s.delta = positiveOrZero(edit.getText().toString()); s.at = System.currentTimeMillis(); s.correction = which == 0;
                            if (which == 1 && !m.stockKnown) throw new IllegalArgumentException("请先盘点现有库存");
                            m.stockKnown = true; book.stock.add(s); save(); Reminders.lowStock(this, book, m);
                        } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
                    }).show();
        }).show();
    }

    private void manualDose(MedicineBook.Medicine m) {
        EditText edit = new EditText(this); edit.setSingleLine(true); edit.setHint("实际服用多少 " + m.unit);
        new AlertDialog.Builder(this).setTitle("记录 " + m.name).setView(edit).setNegativeButton("取消", null)
                .setPositiveButton("立即记录", (d, w) -> {
                    try { long now = System.currentTimeMillis(); book.take(m.id, positive(edit.getText().toString()), now, now, zone); save(); Reminders.lowStock(this, book, m); }
                    catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
                }).show();
    }

    private void backfill() {
        List<MedicineBook.Medicine> options = new ArrayList<>();
        for (MedicineBook.Medicine m : book.medicines) if (!m.archived) options.add(m);
        if (options.isEmpty()) { Toast.makeText(this, "请先添加药品", Toast.LENGTH_SHORT).show(); return; }
        String[] names = options.stream().map(MedicineBook.Medicine::label).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("选择药品").setItems(names, (d, which) -> {
            MedicineBook.Medicine m = options.get(which); LinearLayout box = column(); box.setPadding(dp(18), 0, dp(18), 0);
            EditText amount = field(box, "实际服用数量（" + m.unit + "）", "1");
            EditText time = field(box, "实际服药时间（年-月-日 时:分）", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            form("补录 " + m.name, box, () -> {
                LocalDateTime local = LocalDateTime.parse(time.getText().toString().trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                long takenAt = local.atZone(zone).toInstant().toEpochMilli(), now = System.currentTimeMillis();
                if (takenAt > now || takenAt < now - 365L * 24 * 3_600_000) throw new IllegalArgumentException("补录时间应在过去一年内");
                book.take(m.id, positive(amount.getText().toString()), takenAt, now, zone); save(); Reminders.lowStock(this, book, m);
            });
        }).show();
    }

    private static BigDecimal positive(String s) { BigDecimal n = new BigDecimal(s.trim()); if (n.signum() <= 0) throw new IllegalArgumentException("数量必须大于 0"); return n; }
    private static BigDecimal positiveOrZero(String s) { BigDecimal n = new BigDecimal(s.trim()); if (n.signum() < 0) throw new IllegalArgumentException("数量不能为负"); return n; }
    private static Integer optional(EditText e) { String s = e.getText().toString().trim(); if (s.isEmpty()) return null; int n = Integer.parseInt(s); if (n < 0) throw new IllegalArgumentException("不能填负数"); return n; }
    private static int optionalInt(EditText e) { Integer n = optional(e); return n == null ? 0 : n; }
    private static String dose(BigDecimal n) { return n.stripTrailingZeros().toPlainString(); }
    private static String times(List<Integer> minutes) { List<String> out = new ArrayList<>(); for (int n : minutes) out.add(String.format(java.util.Locale.CHINA, "%02d:%02d", n / 60, n % 60)); return String.join("、", out); }
}
