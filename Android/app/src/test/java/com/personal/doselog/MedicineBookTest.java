package com.personal.doselog;

import org.junit.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import static org.junit.Assert.*;

public class MedicineBookTest {
    private final ZoneId zone = ZoneId.of("Asia/Shanghai");
    private long at(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli();
    }
    private MedicineBook setup() {
        MedicineBook b = new MedicineBook(); MedicineBook.Medicine m = new MedicineBook.Medicine();
        m.name = "测试药"; m.low = new BigDecimal("3"); b.medicines.add(m); return b;
    }
    private MedicineBook.Plan daily(MedicineBook b, int minute) {
        MedicineBook.Plan p = new MedicineBook.Plan(); p.medicineId = b.medicines.get(0).id;
        p.dailyMinutes.add(minute); p.activeFrom = at(19, 0, 0); b.plans.add(p); return p;
    }

    @Test public void dailyScheduleAndLocalMidnight() {
        MedicineBook b = setup(); daily(b, 8 * 60);
        List<MedicineBook.Due> due = b.scheduled(at(19, 23, 59), at(21, 9, 0), zone);
        assertEquals(2, due.size()); assertEquals(at(20, 8, 0), due.get(0).at);
        assertEquals(at(21, 8, 0), due.get(1).at);
    }

    @Test public void oneTapMatchesSpecificPlanAndAuditDetectsMissing() {
        MedicineBook b = setup(); MedicineBook.Plan p = daily(b, 8 * 60);
        p.activeFrom = at(20, 0, 0);
        MedicineBook.Dose d = b.take(p.medicineId, BigDecimal.ONE, at(20, 8, 2), at(20, 8, 2), zone);
        assertEquals(p.id, d.planId); assertEquals(Long.valueOf(at(20, 8, 0)), d.scheduledAt);
        assertTrue(b.audit(at(20, 8, 31), zone).isEmpty());
        assertTrue(b.audit(at(21, 8, 31), zone).stream().anyMatch(f -> f.title.contains("尚未确认")));
    }

    @Test public void voidRestoresInventoryAndBackfillBeforeCountDoesNotConsumeAgain() {
        MedicineBook b = setup(); MedicineBook.Medicine m = b.medicines.get(0);
        MedicineBook.Stock s = new MedicineBook.Stock(); s.medicineId = m.id; s.delta = new BigDecimal("10");
        s.at = at(20, 9, 0); s.correction = true; b.stock.add(s); m.stockKnown = true;
        MedicineBook.Dose old = b.take(m.id, BigDecimal.ONE, at(20, 8, 0), at(20, 10, 0), zone);
        assertEquals(0, new BigDecimal("10").compareTo(b.balance(m.id)));
        MedicineBook.Dose current = b.take(m.id, new BigDecimal("2"), at(20, 10, 0), at(20, 10, 0), zone);
        assertEquals(0, new BigDecimal("8").compareTo(b.balance(m.id)));
        current.voided = true; assertEquals(0, new BigDecimal("10").compareTo(b.balance(m.id)));
    }

    @Test public void intervalAndCycleUseElapsedHoursAndCalendarDays() {
        MedicineBook b = setup(); MedicineBook.Plan p = new MedicineBook.Plan();
        p.medicineId = b.medicines.get(0).id; p.interval = true; p.intervalHours = 12;
        p.anchor = at(19, 8, 0); p.activeFrom = p.anchor; p.onDays = 1; p.offDays = 1; p.cycleAnchor = p.anchor;
        b.plans.add(p);
        List<MedicineBook.Due> due = b.scheduled(at(19, 0, 0), at(21, 23, 0), zone);
        assertEquals(4, due.size()); assertEquals(at(19, 8, 0), due.get(0).at);
        assertEquals(at(21, 8, 0), due.get(2).at);
    }

    @Test public void strictWindowAndMinimumGapAreFlagged() {
        MedicineBook b = setup(); MedicineBook.Plan p = daily(b, 8 * 60);
        p.earlyMinutes = 5; p.lateMinutes = 5; p.minimumGapHours = 6;
        b.take(p.medicineId, BigDecimal.ONE, at(20, 7, 50), at(20, 7, 50), zone);
        b.take(p.medicineId, BigDecimal.ONE, at(20, 8, 0), at(20, 8, 0), zone);
        List<MedicineBook.Finding> findings = b.audit(at(20, 8, 1), zone);
        assertTrue(findings.stream().anyMatch(f -> f.title.contains("早于")));
        assertTrue(findings.stream().anyMatch(f -> f.title.contains("间隔")));
        assertTrue(findings.stream().anyMatch(f -> f.title.contains("超过计划")));
    }

    @Test public void repeatTapMatchesSameOccurrenceInsteadOfTomorrow() {
        MedicineBook b = setup(); MedicineBook.Plan p = daily(b, 8 * 60);
        b.take(p.medicineId, BigDecimal.ONE, at(20, 8, 0), at(20, 8, 0), zone);
        MedicineBook.Dose duplicate = b.take(p.medicineId, BigDecimal.ONE, at(20, 8, 1), at(20, 8, 1), zone);
        assertEquals(Long.valueOf(at(20, 8, 0)), duplicate.scheduledAt);
        assertTrue(b.audit(at(20, 8, 2), zone).stream().anyMatch(f -> f.title.contains("超过计划")));
    }

    @Test public void partialScheduledDoseKeepsRemainingAmountAndStockAccurate() {
        MedicineBook b = setup(); MedicineBook.Plan p = daily(b, 8 * 60);
        MedicineBook.Due due = b.scheduled(at(20, 0, 0), at(20, 23, 0), zone).get(0);
        b.takeScheduled(due, new BigDecimal("0.5"), at(20, 8, 1), at(20, 8, 1));
        assertEquals(0, new BigDecimal("0.5").compareTo(b.recordedAmount(due)));
        assertFalse(b.recorded(due));
        b.takeScheduled(due, new BigDecimal("0.5"), at(20, 8, 5), at(20, 8, 5));
        assertTrue(b.recorded(due));
        assertEquals(0, new BigDecimal("-1").compareTo(b.balance(p.medicineId)));
    }

    @Test public void explicitUnplannedDoseDoesNotSilentlyAttachToNearestPlan() {
        MedicineBook b = setup(); MedicineBook.Plan p = daily(b, 8 * 60);
        MedicineBook.Dose d = b.takeUnplanned(p.medicineId, BigDecimal.ONE, at(20, 8, 1), at(20, 8, 1));
        assertNull(d.planId); assertNull(d.scheduledAt);
        MedicineBook.Due due = b.scheduled(at(20, 0, 0), at(20, 23, 0), zone).get(0);
        assertFalse(b.recorded(due));
    }

    @Test public void unplannedDoseOnOffCycleDayIsFlagged() {
        MedicineBook b = setup(); MedicineBook.Plan p = daily(b, 8 * 60);
        p.onDays = 1; p.offDays = 1; p.cycleAnchor = at(19, 0, 0);
        b.takeUnplanned(p.medicineId, BigDecimal.ONE, at(20, 8, 0), at(20, 8, 0));
        assertTrue(b.audit(at(20, 9, 0), zone).stream().anyMatch(f -> f.title.contains("计划外")));
    }
}
