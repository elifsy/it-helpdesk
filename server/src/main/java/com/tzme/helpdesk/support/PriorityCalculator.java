package com.tzme.helpdesk.support;

import java.util.List;

/**
 * 优先级 = 影响范围 × 工作影响。员工不能自己选“紧急”，避免人人都是 P1。
 * 信息安全类无条件 P1。
 */
public final class PriorityCalculator {

    private static final List<String> IMPACTS = List.of("SELF", "DEPT", "FLOOR", "ALL");
    private static final List<String> WORKS = List.of("FINE", "SLOW", "BLOCKED");

    /** 行：工作影响 FINE / SLOW / BLOCKED；列：影响范围 SELF / DEPT / FLOOR / ALL */
    private static final String[][] MATRIX = {
            {"P4", "P4", "P3", "P3"},
            {"P4", "P3", "P2", "P2"},
            {"P3", "P2", "P1", "P1"}
    };

    private PriorityCalculator() {
    }

    public static String calc(String cat1, String impact, String workImpact) {
        if (cat1 != null && (cat1.contains("信息安全") || cat1.contains("安全事件"))) {
            return "P1";
        }
        int w = WORKS.indexOf(workImpact == null ? "SLOW" : workImpact);
        int i = IMPACTS.indexOf(impact == null ? "SELF" : impact);
        if (w < 0) {
            w = 1;
        }
        if (i < 0) {
            i = 0;
        }
        return MATRIX[w][i];
    }
}
