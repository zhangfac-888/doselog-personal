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
import android.graphics.drawable.GradientDrawable;
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
    private ZoneId zone = ZoneId.systemDefault();
    private static final int INK = Color.rgb(27, 45, 48);
    private static final int MUTED = Color.rgb(102, 121, 122);
    private static final int TEAL = Color.rgb(0, 128, 118);
    private static final int DARK_TEAL = Color.rgb(11, 104, 97);
    private static final int PALE_TEAL = Color.rgb(226, 246, 241);
    private static final int BG = Color.rgb(246, 248, 246);
    private static final int LINE = Color.rgb(226, 232, 229);
    private static final int ORANGE = Color.rgb(166, 94, 40);
    private static final int PALE_ORANGE = Color.rgb(255, 244, 227);
    private static final int RED = Color.rgb(180, 67, 73);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new BookStore(this);
        try { book = store.load(); }
        catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("本机数据读取失败")
                    .setMessage("数据不会被覆盖。请保留应用，并联系开发者处理。\n" + e.getMessage())
                    .setPositiveButton("知道了", (d, w) -> finish()).setCancelable(false).show();
            return;
        }
        try { Reminders.sync(this, book); }
        catch (Exception e) { Toast.makeText(this, "提醒暂未安排：" + e.getMessage(), Toast.LENGTH_LONG).show(); }
        render();
    }

    @Override public void onResume() {
        super.onResume(); zone = ZoneId.systemDefault();
        if (book != null) {
            for (MedicineBook.Medicine m : book.medicines) Reminders.lowStock(this, book, m);
            render();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (book != null) {
            Reminders.sync(this, book);
            for (MedicineBook.Medicine m : book.medicines) Reminders.lowStock(this, book, m);
            render();
        }
    }

    private void save() {
        try { store.save(book); }
        catch (Exception e) {
            try { book = store.load(); render(); } catch (Exception ignored) { }
            Toast.makeText(this, "保存失败，操作未记入：" + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        try { Reminders.sync(this, book); }
        catch (Exception e) { Toast.makeText(this, "已保存记录，但提醒暂未安排", Toast.LENGTH_LONG).show(); }
        render();
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }

    private GradientDrawable shape(int fill, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill); drawable.setCornerRadius(dp(radius));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private TextView label(String value, int size, boolean bold, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER_VERTICAL); return t;
    }

    private TextView text(String value, int size, boolean bold) { return label(value, size, bold, INK); }

    private TextView action(String value, int fill, int foreground, Runnable onClick) {
        TextView t = label(value, 15, true, foreground); t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(48)); t.setPadding(dp(14), dp(10), dp(14), dp(10));
        t.setBackground(shape(fill, 14, 0)); t.setClickable(true); t.setFocusable(true);
        t.setOnClickListener(v -> onClick.run()); return t;
    }

    private Button button(String value, Runnable onClick) {
        Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setOnClickListener(v -> onClick.run()); return b;
    }

    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private LinearLayout card(int color) {
        LinearLayout c = column(); c.setPadding(dp(18), dp(17), dp(18), dp(17)); c.setBackground(shape(color, 20, color == Color.WHITE ? LINE : 0));
        return c;
    }
    private void gap(LinearLayout parent, int height) { View v = new View(this); parent.addView(v, new LinearLayout.LayoutParams(1, dp(height))); }
    private void addCard(LinearLayout card) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(12); root.addView(card, p);
    }
    private void section(String title, String caption) {
        LinearLayout line = row(); line.setPadding(0, dp(21), 0, dp(12));
        line.addView(text(title, 20, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (caption != null) line.addView(label(caption, 13, false, MUTED));
        root.addView(line);
    }
    private void section(String title) { section(title, null); }

    private void header(String kicker, String title, String subtitle) {
        TextView k = label(kicker, 13, true, TEAL); k.setLetterSpacing(.07f); root.addView(k);
        gap(root, 8); root.addView(text(title, 29, true)); gap(root, 4);
        root.addView(label(subtitle, 14, false, MUTED)); gap(root, 20);
    }

    private void render() {
        if (book == null) return;
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout screen = column(); screen.setBackgroundColor(BG);
        screen.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom()); return insets;
        });
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        root = column(); root.setPadding(dp(20), dp(24), dp(20), dp(28)); scroll.addView(root);
        screen.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        screen.addView(bottomNavigation()); setContentView(screen);
        switch (tab) { case "药品计划": showPlans(); break; case "七天核对": showAudit(); break; default: showToday(); }
    }

    private View bottomNavigation() {
        LinearLayout bar = row(); bar.setPadding(dp(12), dp(8), dp(12), dp(8)); bar.setBackgroundColor(Color.WHITE);
        String[] names = {"今日", "药品计划", "七天核对"};
        String[] icons = {"●", "▤", "◷"};
        for (int i = 0; i < names.length; i++) {
            final String name = names[i]; boolean selected = tab.equals(name);
            LinearLayout item = column(); item.setGravity(Gravity.CENTER); item.setPadding(0, dp(5), 0, dp(5));
            TextView icon = label(icons[i], 18, true, selected ? TEAL : MUTED); icon.setGravity(Gravity.CENTER);
            TextView text = label(name.equals("药品计划") ? "药品" : name.equals("七天核对") ? "核对" : name, 12, selected, selected ? TEAL : MUTED);
            text.setGravity(Gravity.CENTER); item.addView(icon); item.addView(text);
            item.setContentDescription(name); item.setClickable(true); item.setOnClickListener(v -> { tab = name; render(); });
            bar.addView(item, new LinearLayout.LayoutParams(0, dp(54), 1));
        }
        return bar;
    }

    private void showToday() {
        long now = System.currentTimeMillis();
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        String date = today.format(DateTimeFormatter.ofPattern("M月d日 EEEE", java.util.Locale.CHINA));
        header("安心用药 · 本机记录", "今天的用药", date + " · 只记录实际服用");
        long start = today.atStartOfDay(zone).toInstant().toEpochMilli();
        long end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1;
        List<MedicineBook.Due> dues = book.scheduled(start, end, zone);
        int finished = 0; for (MedicineBook.Due d : dues) if (book.recorded(d)) finished++;
        LinearLayout summary = card(DARK_TEAL);
        summary.addView(label("今日进度", 13, true, Color.rgb(196, 236, 229))); gap(summary, 7);
        summary.addView(label(dues.isEmpty() ? "今天没有用药计划" : "已记录 " + finished + " / " + dues.size() + " 次", 26, true, Color.WHITE)); gap(summary, 5);
        summary.addView(label("服药后点对应药品，自动记下当时的时间", 13, false, Color.rgb(211, 240, 236))); gap(summary, 16);
        LinearLayout progress = row(); progress.setBackground(shape(Color.rgb(75, 150, 143), 5, 0));
        View fill = new View(this); fill.setBackground(shape(Color.WHITE, 5, 0));
        progress.addView(fill, new LinearLayout.LayoutParams(0, dp(6), dues.isEmpty() ? .001f : finished));
        progress.addView(new View(this), new LinearLayout.LayoutParams(0, dp(6), dues.isEmpty() ? 1f : Math.max(dues.size() - finished, .001f)));
        summary.addView(progress); addCard(summary);

        section("今日应服", dues.size() + " 次计划");
        if (dues.isEmpty()) {
            LinearLayout empty = card(Color.WHITE);
            empty.addView(text(book.medicines.isEmpty() ? "还没有药品和计划" : "今天没有需要打卡的计划", 17, true)); gap(empty, 6);
            empty.addView(label(book.medicines.isEmpty() ? "先添加药品，再按医嘱设置服药时间。" : "如实际服用了计划外药品，可在下方单独记录。", 14, false, MUTED));
            if (book.medicines.isEmpty()) { gap(empty, 15); empty.addView(action("添加第一种药品", TEAL, Color.WHITE, () -> { tab = "药品计划"; render(); addMedicine(); })); }
            addCard(empty);
        }
        for (MedicineBook.Due due : dues) showDueCard(due, now);
        if (book.medicines.stream().anyMatch(m -> !m.archived)) {
            gap(root, 5);
            root.addView(action("＋ 记录计划外用药", PALE_TEAL, DARK_TEAL, this::chooseManualMedicine));
            gap(root, 10);
            root.addView(action("补录过去的服药", Color.WHITE, DARK_TEAL, this::backfill));
        }

        List<MedicineBook.Dose> records = new ArrayList<>();
        for (MedicineBook.Dose d : book.doses) if (!d.voided && Instant.ofEpochMilli(d.takenAt).atZone(zone).toLocalDate().equals(today)) records.add(d);
        records.sort(Comparator.comparingLong(d -> d.takenAt));
        section("实际记录", records.isEmpty() ? "暂无" : records.size() + " 条");
        LinearLayout history = card(Color.WHITE);
        if (records.isEmpty()) history.addView(label("服药后，这里会显示准确时间。", 14, false, MUTED));
        for (int i = 0; i < records.size(); i++) {
            MedicineBook.Dose d = records.get(i); MedicineBook.Medicine m = book.medicine(d.medicineId);
            if (i > 0) { gap(history, 12); divider(history); gap(history, 12); }
            LinearLayout line = row();
            String time = Instant.ofEpochMilli(d.takenAt).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"));
            TextView hour = label(time, 17, true, TEAL); line.addView(hour, new LinearLayout.LayoutParams(dp(68), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout detail = column(); detail.addView(text((m == null ? "药品" : m.label()) + " · " + dose(d.amount) + (m == null ? "" : m.unit), 14, true));
            if (d.backfilled) detail.addView(label("补录", 12, false, MUTED));
            line.addView(detail, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView undo = label("撤销", 13, true, RED); undo.setPadding(dp(8), dp(10), 0, dp(10)); undo.setOnClickListener(v -> { d.voided = true; save(); });
            line.addView(undo); history.addView(line);
        }
        addCard(history);
        root.addView(label("误触可撤销，库存会随记录恢复。", 12, false, MUTED));
    }

    private void showDueCard(MedicineBook.Due due, long now) {
        MedicineBook.Medicine m = book.medicine(due.medicineId); if (m == null || m.archived) return;
        LinearLayout c = card(Color.WHITE);
        LinearLayout top = row();
        String time = Instant.ofEpochMilli(due.at).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"));
        TextView hour = label(time, 21, true, INK); top.addView(hour, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        BigDecimal taken = book.recordedAmount(due);
        MedicineBook.Plan plan = book.plan(due.planId);
        long followUp = plan == null ? 30 : plan.followUpMinutes;
        String state = taken.compareTo(due.amount) >= 0 ? "已记录" : taken.signum() > 0 ? "部分记录" : due.at + followUp * 60_000 < now ? "待核对" : "计划中";
        boolean done = taken.compareTo(due.amount) >= 0;
        TextView badge = label(state, 12, true, done ? DARK_TEAL : state.equals("待核对") ? ORANGE : MUTED);
        badge.setPadding(dp(10), dp(5), dp(10), dp(5)); badge.setBackground(shape(done ? PALE_TEAL : state.equals("待核对") ? PALE_ORANGE : BG, 11, 0));
        top.addView(badge); c.addView(top); gap(c, 12);
        c.addView(text(m.label(), 18, true)); gap(c, 4);
        c.addView(label("计划服用 " + dose(due.amount) + m.unit + (taken.signum() > 0 && !done ? " · 已记录 " + dose(taken) + m.unit : ""), 14, false, MUTED));
        if (m.stockKnown) { gap(c, 4); c.addView(label("库存 " + dose(book.balance(m.id)) + m.unit + (book.balance(m.id).compareTo(m.low) < 0 ? " · 需要补货" : ""), 12, false, book.balance(m.id).compareTo(m.low) < 0 ? ORANGE : MUTED)); }
        gap(c, 15);
        if (!done) {
            BigDecimal remaining = due.amount.subtract(taken);
            c.addView(action("已服 " + dose(remaining) + m.unit + " · 记录现在", TEAL, Color.WHITE, () -> {
                long actual = System.currentTimeMillis(); book.takeScheduled(due, remaining, actual, actual); save(); Reminders.lowStock(this, book, m);
            }));
            gap(c, 8);
            TextView different = label("实际剂量不同？按实际数量记录", 13, true, TEAL); different.setGravity(Gravity.CENTER); different.setMinHeight(dp(42));
            different.setOnClickListener(v -> actualDoseDialog(due, m)); c.addView(different);
        } else {
            long last = 0; for (MedicineBook.Dose d : book.doses) if (!d.voided && due.planId.equals(d.planId) && d.scheduledAt != null && d.scheduledAt == due.at) last = Math.max(last, d.takenAt);
            c.addView(label("✓ 实际记录于 " + Instant.ofEpochMilli(last).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")), 14, true, DARK_TEAL));
        }
        addCard(c);
    }

    private void actualDoseDialog(MedicineBook.Due due, MedicineBook.Medicine m) {
        EditText input = new EditText(this); input.setSingleLine(true); input.setHint("实际服用多少 " + m.unit);
        new AlertDialog.Builder(this).setTitle("记录 " + m.name + " 的实际剂量").setView(input).setNegativeButton("取消", null)
                .setPositiveButton("记录现在", (dialog, which) -> {
                    try { long actual = System.currentTimeMillis(); book.takeScheduled(due, positive(input.getText().toString()), actual, actual); save(); Reminders.lowStock(this, book, m); }
                    catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
                }).show();
    }

    private void chooseManualMedicine() {
        List<MedicineBook.Medicine> options = new ArrayList<>(); for (MedicineBook.Medicine m : book.medicines) if (!m.archived) options.add(m);
        String[] names = options.stream().map(MedicineBook.Medicine::label).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("选择实际服用的药品").setItems(names, (d, which) -> manualDose(options.get(which))).show();
    }

    private void divider(LinearLayout parent) { View line = new View(this); line.setBackgroundColor(LINE); parent.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))); }

    private void showPlans() {
        header("按医嘱设置", "药品与计划", "每种药的剂量、时间与库存独立记录");
        root.addView(action("＋ 添加药品", TEAL, Color.WHITE, this::addMedicine));
        section("正在使用", book.medicines.stream().filter(m -> !m.archived).count() + " 种药品");
        if (book.medicines.stream().noneMatch(m -> !m.archived)) {
            LinearLayout empty = card(Color.WHITE); empty.addView(text("还没有添加药品", 17, true)); gap(empty, 5);
            empty.addView(label("请按药盒与医嘱填写，不确定的项目不要猜。", 14, false, MUTED)); addCard(empty);
        }
        for (MedicineBook.Medicine m : book.medicines) {
            if (m.archived) continue;
            LinearLayout c = card(Color.WHITE); LinearLayout top = row();
            TextView avatar = label(m.name.substring(0, 1), 19, true, DARK_TEAL); avatar.setGravity(Gravity.CENTER);
            avatar.setBackground(shape(PALE_TEAL, 16, 0)); top.addView(avatar, new LinearLayout.LayoutParams(dp(46), dp(46)));
            LinearLayout info = column(); info.setPadding(dp(12), 0, 0, 0);
            info.addView(text(m.name, 18, true)); info.addView(label(m.strength.isEmpty() ? "规格未填写" : m.strength, 13, false, MUTED));
            top.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)); c.addView(top); gap(c, 16);
            boolean low = m.stockKnown && book.balance(m.id).compareTo(m.low) < 0;
            c.addView(label(m.stockKnown ? "剩余 " + dose(book.balance(m.id)) + m.unit + "  ·  安全线 " + dose(m.low) + m.unit + (low ? "  ·  需补货" : "") : "库存待盘点  ·  安全线 " + dose(m.low) + m.unit, 14, low, low ? ORANGE : MUTED));
            gap(c, 13); divider(c); gap(c, 12);
            int active = 0;
            for (MedicineBook.Plan p : book.plans) if (p.medicineId.equals(m.id) && p.activeUntil > System.currentTimeMillis()) {
                active++;
                String timing = p.interval ? "每隔 " + p.intervalHours + " 小时" : "每天 " + times(p.dailyMinutes);
                String cycle = p.onDays > 0 ? "  ·  用 " + p.onDays + " 天 / 停 " + p.offDays + " 天" : "";
                c.addView(text(timing + "  ·  " + dose(p.amount) + m.unit + cycle, 14, true)); gap(c, 7);
                TextView stop = label("停止这条计划", 12, false, RED); stop.setMinHeight(dp(36));
                stop.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage("停止后不再生成新的提醒，原记录保留。")
                        .setNegativeButton("取消", null).setPositiveButton("停止", (d, w) -> { p.activeUntil = System.currentTimeMillis(); save(); }).show());
                c.addView(stop);
            }
            if (active == 0) c.addView(label("还没有正在使用的计划", 13, false, MUTED));
            gap(c, 9);
            LinearLayout actions = row(); TextView inventory = action("盘点 / 补货", PALE_TEAL, DARK_TEAL, () -> stockDialog(m));
            TextView add = action("＋ 用药计划", PALE_TEAL, DARK_TEAL, () -> addPlan(m));
            LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); left.rightMargin = dp(8);
            actions.addView(inventory, left); actions.addView(add, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            c.addView(actions); gap(c, 8);
            TextView threshold = label("调整库存安全线", 12, true, TEAL); threshold.setMinHeight(dp(36));
            threshold.setOnClickListener(v -> adjustLowStock(m)); c.addView(threshold); addCard(c);
        }
        section("提醒权限");
        LinearLayout reminder = card(Color.WHITE);
        reminder.addView(text(Reminders.canNotify(this) && Reminders.exactAllowed(this) ? "提醒已准备好" : "需要检查提醒权限", 17, true)); gap(reminder, 6);
        reminder.addView(label(Reminders.canNotify(this) ? "通知已允许" : "通知未允许：不会弹出服药提醒", 14, false, Reminders.canNotify(this) ? MUTED : ORANGE));
        reminder.addView(label(Reminders.exactAllowed(this) ? "精确闹钟已允许" : "精确闹钟未允许：提醒可能明显延迟", 14, false, Reminders.exactAllowed(this) ? MUTED : ORANGE));
        if (!Reminders.canNotify(this)) { gap(reminder, 10); reminder.addView(action("允许通知", TEAL, Color.WHITE, () -> {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 11);
        })); }
        if (!Reminders.exactAllowed(this)) { gap(reminder, 9); reminder.addView(action("允许精确提醒", PALE_TEAL, DARK_TEAL, () -> {
            if (Build.VERSION.SDK_INT >= 31) startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:" + getPackageName())));
        })); }
        addCard(reminder);
    }

    private void showAudit() {
        header("按已录入计划核对", "七天核对", "发现差异时，请核对实际记录与医嘱");
        List<MedicineBook.Finding> findings = book.audit(System.currentTimeMillis(), zone);
        LinearLayout summary = card(findings.isEmpty() ? PALE_TEAL : PALE_ORANGE);
        summary.addView(label(findings.isEmpty() ? "目前没有待核对项" : "有 " + findings.size() + " 项需要核对", 23, true, findings.isEmpty() ? DARK_TEAL : ORANGE));
        gap(summary, 7); summary.addView(label("这里只比较计划和记录，不能证明实际服药或判断医嘱是否正确。", 13, false, MUTED)); addCard(summary);
        section("发现的差异", findings.isEmpty() ? "0 项" : findings.size() + " 项");
        if (findings.isEmpty()) {
            LinearLayout empty = card(Color.WHITE); empty.addView(label("有新的记录后，系统会继续回溯最近七天。", 14, false, MUTED)); addCard(empty);
        }
        for (MedicineBook.Finding f : findings) {
            MedicineBook.Medicine m = book.medicine(f.medicineId); LinearLayout c = card(Color.WHITE);
            c.addView(label((m == null ? "药品" : m.name) + "  ·  " + f.title, 16, true, ORANGE)); gap(c, 6);
            c.addView(label(f.detail, 14, false, MUTED)); addCard(c);
        }
        section("最近七天的记录");
        List<MedicineBook.Dose> history = new ArrayList<>(); long start = System.currentTimeMillis() - 7L * 24 * 3_600_000;
        for (MedicineBook.Dose d : book.doses) if (!d.voided && d.takenAt >= start) history.add(d);
        history.sort((a, b) -> Long.compare(b.takenAt, a.takenAt));
        if (history.isEmpty()) { LinearLayout empty = card(Color.WHITE); empty.addView(label("还没有服药记录。", 14, false, MUTED)); addCard(empty); }
        for (MedicineBook.Dose d : history) {
            MedicineBook.Medicine m = book.medicine(d.medicineId); LinearLayout c = card(Color.WHITE);
            c.addView(text((m == null ? "药品" : m.label()) + "  ·  " + dose(d.amount) + (m == null ? "" : m.unit), 15, true)); gap(c, 5);
            c.addView(label(MedicineBook.fmt(d.takenAt, zone) + (d.backfilled ? "  ·  补录" : ""), 13, false, MUTED)); gap(c, 9);
            TextView undo = label("撤销误记录", 13, true, RED); undo.setMinHeight(dp(40)); undo.setOnClickListener(v -> { d.voided = true; save(); }); c.addView(undo); addCard(c);
        }
    }

    private EditText field(LinearLayout box, String hint, String value) {
        box.addView(text(hint, 14, false)); EditText edit = new EditText(this); edit.setSingleLine(true); edit.setText(value); box.addView(edit); return edit;
    }

    private void form(String title, LinearLayout box, Runnable submit) {
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(box).setNegativeButton("取消", null)
                .setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { submit.run(); dialog.dismiss(); }
            catch (Exception e) { Toast.makeText(this, "请检查输入：" + e.getMessage(), Toast.LENGTH_LONG).show(); }
        }));
        dialog.show();
    }

    private void addMedicine() {
        LinearLayout box = column(); box.setPadding(dp(18), dp(5), dp(18), 0);
        EditText name = field(box, "药品名称", ""); EditText strength = field(box, "规格（例如 10 mg）", "");
        EditText unit = field(box, "计数单位（片、粒、毫升等）", "片"); EditText low = field(box, "库存安全线", "");
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
        EditText amount = field(box, "每次服用多少 " + m.unit, "");
        EditText daily = field(box, "每天的时间，逗号隔开（例如 08:00,20:00）。若按小时间隔服用则留空", "");
        EditText interval = field(box, "按间隔服用：每隔多少小时（每天定时则留空）", "");
        EditText anchor = field(box, "间隔起点（年-月-日 时:分；按每日定时可留空）", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        EditText on = field(box, "周期用药：用药天数（无周期则留空）", "");
        EditText off = field(box, "周期用药：停药天数", "");
        EditText cycleStart = field(box, "周期起始日（年-月-日）", LocalDate.now().toString());
        EditText early = field(box, "允许提前分钟数（未规定可留空）", "");
        EditText late = field(box, "允许延后分钟数（未规定可留空）", "");
        EditText gap = field(box, "最短服药间隔小时数（未规定可留空）", "");
        EditText followUp = field(box, "未确认后再次提醒：分钟", "30");
        EditText source = field(box, "医嘱依据（处方或药袋）", "");
        ScrollView scroll = new ScrollView(this); scroll.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(m.name + " 的用药计划").setView(scroll).setNegativeButton("取消", null)
                .setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    try {
                        MedicineBook.Plan p = new MedicineBook.Plan(); p.medicineId = m.id;
                        p.amount = positive(amount.getText().toString()); p.activeFrom = System.currentTimeMillis();
                        String h = interval.getText().toString().trim();
                        if (!h.isEmpty()) {
                            p.interval = true; p.intervalHours = Integer.parseInt(h);
                            if (p.intervalHours < 1 || p.intervalHours > 168) throw new IllegalArgumentException("间隔须为 1～168 小时");
                            p.anchor = LocalDateTime.parse(anchor.getText().toString().trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                                    .atZone(zone).toInstant().toEpochMilli();
                        } else {
                            if (daily.getText().toString().trim().isEmpty()) throw new IllegalArgumentException("请输入每日时间");
                            for (String part : daily.getText().toString().split(",")) {
                                LocalTime time = LocalTime.parse(part.trim()); p.dailyMinutes.add(time.getHour() * 60 + time.getMinute());
                            }
                        }
                        p.onDays = optionalInt(on); p.offDays = optionalInt(off);
                        if (p.onDays > 0) p.cycleAnchor = LocalDate.parse(cycleStart.getText().toString().trim()).atStartOfDay(zone).toInstant().toEpochMilli();
                        if (p.onDays > 365 || p.offDays > 365 || (p.onDays == 0 && p.offDays > 0))
                            throw new IllegalArgumentException("周期用药天数须为 1～365，停药天数为 0～365");
                        p.earlyMinutes = optional(early); p.lateMinutes = optional(late); p.minimumGapHours = optional(gap);
                        if (p.minimumGapHours != null && p.minimumGapHours == 0) throw new IllegalArgumentException("最短间隔须大于 0");
                        p.followUpMinutes = Integer.parseInt(followUp.getText().toString().trim());
                        if (p.followUpMinutes < 0 || p.followUpMinutes > 10080) throw new IllegalArgumentException("再次提醒须为 0～10080 分钟");
                        p.directionSource = source.getText().toString().trim();
                        if (p.directionSource.isEmpty()) throw new IllegalArgumentException("请填写医嘱依据");
                        book.plans.add(p); save(); dialog.dismiss();
                    } catch (Exception e) { Toast.makeText(this, "计划输入有误：" + e.getMessage(), Toast.LENGTH_LONG).show(); }
                }));
        dialog.show();
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

    private void adjustLowStock(MedicineBook.Medicine m) {
        EditText input = new EditText(this); input.setSingleLine(true); input.setText(dose(m.low));
        new AlertDialog.Builder(this).setTitle("调整 " + m.name + " 的库存安全线").setView(input)
                .setNegativeButton("取消", null).setPositiveButton("保存", (d, w) -> {
                    try { m.low = positiveOrZero(input.getText().toString()); save(); Reminders.lowStock(this, book, m); }
                    catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
                }).show();
    }

    private void manualDose(MedicineBook.Medicine m) {
        EditText edit = new EditText(this); edit.setSingleLine(true); edit.setHint("实际服用多少 " + m.unit);
        new AlertDialog.Builder(this).setTitle("记录 " + m.name).setView(edit).setNegativeButton("取消", null)
                .setPositiveButton("立即记录", (d, w) -> {
                    try { long now = System.currentTimeMillis(); book.takeUnplanned(m.id, positive(edit.getText().toString()), now, now); save(); Reminders.lowStock(this, book, m); }
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
