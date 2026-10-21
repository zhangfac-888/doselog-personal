package com.personal.doselog;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pure business rules. Times are epoch milliseconds; daily plans follow the phone's local clock. */
public final class MedicineBook {
    public final List<Medicine> medicines = new ArrayList<>();
    public final List<Plan> plans = new ArrayList<>();
    public final List<Dose> doses = new ArrayList<>();
    public final List<Stock> stock = new ArrayList<>();

    public static final class Medicine {
        public String id = UUID.randomUUID().toString();
        public String name = "";
        public String strength = "";
        public String unit = "片";
        public BigDecimal low = BigDecimal.ZERO;
        public boolean stockKnown;
        public boolean archived;
        public String label() { return strength.isEmpty() ? name : name + " · " + strength; }
    }

    public static final class Plan {
        public String id = UUID.randomUUID().toString();
        public String medicineId;
        public BigDecimal amount = BigDecimal.ONE;
        public boolean interval;
        public final List<Integer> dailyMinutes = new ArrayList<>();
        public int intervalHours;
        public long anchor;
        public long activeFrom;
        public long activeUntil = Long.MAX_VALUE;
        public int onDays;
        public int offDays;
        public long cycleAnchor;
        public Integer earlyMinutes;
        public Integer lateMinutes;
        public Integer minimumGapHours;
        public int followUpMinutes = 30;
        public String directionSource = "";
    }

    public static final class Dose {
        public String id = UUID.randomUUID().toString();
        public String medicineId;
        public String planId;
        public Long scheduledAt;
        public BigDecimal amount;
        public long takenAt;
        public long enteredAt;
        public boolean backfilled;
        public boolean voided;
    }

    public static final class Stock {
        public String id = UUID.randomUUID().toString();
        public String medicineId;
        public BigDecimal delta;
        public long at;
        public boolean correction;
    }

    public static final class Due {
        public String planId;
        public String medicineId;
        public BigDecimal amount;
        public long at;
        public Due(Plan p, long time) { planId = p.id; medicineId = p.medicineId; amount = p.amount; at = time; }
        public String key() { return planId + ":" + at; }
    }

    public static final class Finding {
        public String title;
        public String detail;
        public String medicineId;
        public Finding(String m, String t, String d) { medicineId = m; title = t; detail = d; }
    }

    public Medicine medicine(String id) {
        for (Medicine m : medicines) if (m.id.equals(id)) return m;
        return null;
    }

    public Plan plan(String id) {
        for (Plan p : plans) if (p.id.equals(id)) return p;
        return null;
    }

    public static boolean cycleAllows(Plan p, long at, ZoneId zone) {
        if (p.onDays == 0 && p.offDays == 0) return true;
        if (p.onDays <= 0 || p.onDays > 365 || p.offDays < 0 || p.offDays > 365) return false;
        long days = java.time.temporal.ChronoUnit.DAYS.between(
                Instant.ofEpochMilli(p.cycleAnchor).atZone(zone).toLocalDate(),
                Instant.ofEpochMilli(at).atZone(zone).toLocalDate());
        return days >= 0 && days % (p.onDays + p.offDays) < p.onDays;
    }

    public List<Due> scheduled(long from, long through, ZoneId zone) {
        List<Due> result = new ArrayList<>();
        if (through < from) return result;
        for (Plan p : plans) {
            long lower = Math.max(from, p.activeFrom);
            long upper = Math.min(through, p.activeUntil == Long.MAX_VALUE ? Long.MAX_VALUE : p.activeUntil - 1);
            if (lower > upper || p.amount == null || p.amount.signum() <= 0) continue;
            if (p.interval) {
                if (p.intervalHours < 1 || p.intervalHours > 168) continue;
                long step = p.intervalHours * 3_600_000L;
                long n = Math.max(0, Math.floorDiv(lower - p.anchor + step - 1, step));
                for (int count = 0; count < 10000; count++, n++) {
                    long at;
                    try { at = Math.addExact(p.anchor, Math.multiplyExact(n, step)); }
                    catch (ArithmeticException e) { break; }
                    if (at > upper) break;
                    if (at >= lower && cycleAllows(p, at, zone)) result.add(new Due(p, at));
                }
            } else {
                LocalDate day = Instant.ofEpochMilli(lower).atZone(zone).toLocalDate();
                LocalDate last = Instant.ofEpochMilli(upper).atZone(zone).toLocalDate();
                for (int count = 0; !day.isAfter(last) && count < 370; count++, day = day.plusDays(1)) {
                    for (int minute : p.dailyMinutes) {
                        if (minute < 0 || minute >= 1440) continue;
                        // atZone resolves DST gaps forward and chooses the first overlap offset.
                        LocalDateTime local = LocalDateTime.of(day, LocalTime.of(minute / 60, minute % 60));
                        ZonedDateTime zoned = local.atZone(zone);
                        if (!zoned.toLocalDate().equals(day)) continue;
                        long at = zoned.toInstant().toEpochMilli();
                        if (at >= lower && at <= upper && cycleAllows(p, at, zone)) {
                            boolean duplicate = false;
                            for (Due old : result) if (old.planId.equals(p.id) && old.at == at) { duplicate = true; break; }
                            if (!duplicate) result.add(new Due(p, at));
                        }
                    }
                }
            }
        }
        result.sort(Comparator.comparingLong(d -> d.at));
        return result;
    }

    public BigDecimal balance(String medicineId) {
        Stock baseline = null;
        int baselineIndex = -1;
        for (int i = 0; i < stock.size(); i++) {
            Stock s = stock.get(i);
            if (s.medicineId.equals(medicineId) && s.correction) { baseline = s; baselineIndex = i; }
        }
        BigDecimal result = baseline == null ? BigDecimal.ZERO : baseline.delta;
        for (int i = baselineIndex + 1; i < stock.size(); i++) {
            Stock s = stock.get(i);
            if (s.medicineId.equals(medicineId) && !s.correction) result = result.add(s.delta);
        }
        for (Dose d : doses) {
            if (!d.medicineId.equals(medicineId) || d.voided) continue;
            if (baseline != null && d.takenAt <= baseline.at && !(d.takenAt == baseline.at && d.enteredAt > baseline.at)) continue;
            result = result.subtract(d.amount);
        }
        return result;
    }

    public BigDecimal recordedAmount(Due due) {
        BigDecimal total = BigDecimal.ZERO;
        for (Dose d : doses) if (!d.voided && due.planId.equals(d.planId)
                && d.scheduledAt != null && d.scheduledAt == due.at) total = total.add(d.amount);
        return total;
    }

    public boolean recorded(Due due) {
        return recordedAmount(due).compareTo(due.amount) >= 0;
    }

    public Due closestDue(String medicineId, long now, ZoneId zone) {
        Due best = null;
        long distance = Long.MAX_VALUE;
        for (Due due : scheduled(now - 24L * 3_600_000, now + 24L * 3_600_000, zone)) {
            if (!due.medicineId.equals(medicineId)) continue;
            long delta = Math.abs(due.at - now);
            if (delta > 12L * 3_600_000) continue;
            if (delta < distance || (delta == distance && best != null && recorded(best) && !recorded(due))) {
                distance = delta; best = due;
            }
        }
        return best;
    }

    public Dose takeScheduled(Due due, BigDecimal actualAmount, long takenAt, long enteredAt) {
        if (plan(due.planId) == null || medicine(due.medicineId) == null || actualAmount == null || actualAmount.signum() <= 0)
            throw new IllegalArgumentException("计划或药品无效");
        Dose d = new Dose();
        d.medicineId = due.medicineId; d.amount = actualAmount; d.planId = due.planId; d.scheduledAt = due.at;
        d.takenAt = takenAt; d.enteredAt = enteredAt; d.backfilled = takenAt != enteredAt;
        doses.add(d); return d;
    }

    public Dose takeScheduled(Due due, long takenAt, long enteredAt) {
        return takeScheduled(due, due.amount, takenAt, enteredAt);
    }

    public Dose take(String medicineId, BigDecimal amount, long takenAt, long enteredAt, ZoneId zone) {
        if (amount == null || amount.signum() <= 0 || medicine(medicineId) == null) throw new IllegalArgumentException("药品或剂量无效");
        Dose d = new Dose();
        d.medicineId = medicineId;
        d.amount = amount;
        d.takenAt = takenAt;
        d.enteredAt = enteredAt;
        d.backfilled = takenAt != enteredAt;
        Due due = closestDue(medicineId, takenAt, zone);
        if (due != null) { d.planId = due.planId; d.scheduledAt = due.at; }
        doses.add(d);
        return d;
    }

    public Dose takeUnplanned(String medicineId, BigDecimal amount, long takenAt, long enteredAt) {
        if (amount == null || amount.signum() <= 0 || medicine(medicineId) == null) throw new IllegalArgumentException("药品或剂量无效");
        Dose d = new Dose(); d.medicineId = medicineId; d.amount = amount;
        d.takenAt = takenAt; d.enteredAt = enteredAt; d.backfilled = takenAt != enteredAt;
        doses.add(d); return d;
    }

    public List<Finding> audit(long now, ZoneId zone) {
        long start = now - 7L * 24 * 3_600_000;
        List<Finding> out = new ArrayList<>();
        Map<String, BigDecimal> totals = new HashMap<>();
        for (Dose d : doses) if (!d.voided && d.planId != null && d.scheduledAt != null) {
            String key = d.planId + ":" + d.scheduledAt;
            totals.put(key, totals.getOrDefault(key, BigDecimal.ZERO).add(d.amount));
        }
        for (Due due : scheduled(start, now, zone)) {
            Plan p = plan(due.planId);
            BigDecimal total = totals.getOrDefault(due.key(), BigDecimal.ZERO);
            if (total.compareTo(due.amount) < 0 && now >= due.at + p.followUpMinutes * 60_000L)
                out.add(new Finding(due.medicineId, "尚未确认服药", fmt(due.at, zone) + "，已记录 " + total + "/" + due.amount));
            if (total.compareTo(due.amount) > 0)
                out.add(new Finding(due.medicineId, "记录剂量超过计划", fmt(due.at, zone) + "，计划 " + due.amount + "，记录 " + total));
        }
        for (Dose d : doses) {
            if (d.voided || d.takenAt < start || d.takenAt > now) continue;
            Plan p = d.planId == null ? null : plan(d.planId);
            if (p == null) {
                Plan applicable = null;
                int count = 0;
                for (Plan candidate : plans) if (candidate.medicineId.equals(d.medicineId)
                        && candidate.activeFrom <= d.takenAt && d.takenAt < candidate.activeUntil) {
                    applicable = candidate; count++;
                }
                if (count == 1 && applicable != null && !cycleAllows(applicable, d.takenAt, zone))
                    out.add(new Finding(d.medicineId, "停药阶段有计划外服药记录", fmt(d.takenAt, zone)));
                continue;
            }
            if (d.scheduledAt != null) {
                if (p.earlyMinutes != null && d.takenAt < d.scheduledAt - p.earlyMinutes * 60_000L)
                    out.add(new Finding(d.medicineId, "早于医嘱时间窗口", fmt(d.takenAt, zone)));
                if (p.lateMinutes != null && d.takenAt > d.scheduledAt + p.lateMinutes * 60_000L)
                    out.add(new Finding(d.medicineId, "晚于医嘱时间窗口", fmt(d.takenAt, zone)));
            }
            if (!cycleAllows(p, d.takenAt, zone)) out.add(new Finding(d.medicineId, "停药阶段有服药记录", fmt(d.takenAt, zone)));
        }
        for (Medicine m : medicines) {
            List<Dose> history = new ArrayList<>();
            for (Dose d : doses) if (!d.voided && d.medicineId.equals(m.id) && d.takenAt <= now) history.add(d);
            history.sort(Comparator.comparingLong(d -> d.takenAt));
            for (int i = 1; i < history.size(); i++) {
                Dose prev = history.get(i - 1), d = history.get(i);
                Plan p = d.planId == null ? null : plan(d.planId);
                if (p == null || d.takenAt < start) continue;
                long elapsed = d.takenAt - prev.takenAt;
                if (p.minimumGapHours != null && elapsed < p.minimumGapHours * 3_600_000L)
                    out.add(new Finding(m.id, "服药间隔短于医嘱下限", fmt(prev.takenAt, zone) + " → " + fmt(d.takenAt, zone)));
                if (p.interval && p.id.equals(prev.planId) && Math.abs(elapsed - p.intervalHours * 3_600_000L) > 60_000L)
                    out.add(new Finding(m.id, "实际间隔与计划不同", "计划 " + p.intervalHours + " 小时，实际 " + elapsed / 60_000L + " 分钟"));
            }
        }
        return out;
    }

    public static String fmt(long at, ZoneId zone) {
        return java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm").format(Instant.ofEpochMilli(at).atZone(zone));
    }
}
