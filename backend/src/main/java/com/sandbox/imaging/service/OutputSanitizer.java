package com.sandbox.imaging.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 输出脱敏器：把原始聚合表转换为对外输出单元。
 * 审查发布与交付复现共用同一逻辑，保证冻结载荷与重建结果逐单元同构、可比对。
 * 规则：计数 &lt; 最小单元 => SUPPRESSED（不输出真实计数与衍生度量）；
 * 否则按舍入步长 ROUNDED 或精确 EXACT；分组查询补 TOTAL 行。
 */
@Component
public class OutputSanitizer {

    public List<OutputCell> sanitize(QueryExecutor.RawTable raw, List<String> groups,
                                     int minUnit, Integer roundingStep) {
        List<OutputCell> out = new ArrayList<>();
        for (QueryExecutor.RawCell cell : raw.cells) {
            OutputCell oc = new OutputCell();
            oc.dimKey = cell.dimKey;
            oc.visitCount = cell.visitCount;
            if (cell.patientCount < minUnit) {
                oc.status = "SUPPRESSED";
                oc.displayCount = null;
                oc.avgMaxMm = null;
                oc.avgLesionCount = null;
            } else if (roundingStep != null && roundingStep > 1) {
                oc.status = "ROUNDED";
                oc.displayCount = Math.floorDiv(cell.patientCount, roundingStep) * roundingStep;
                oc.roundingStep = roundingStep;
                oc.avgMaxMm = cell.avgMaxMm;
                oc.avgLesionCount = cell.avgLesionCount;
            } else {
                oc.status = "EXACT";
                oc.displayCount = cell.patientCount;
                oc.avgMaxMm = cell.avgMaxMm;
                oc.avgLesionCount = cell.avgLesionCount;
            }
            out.add(oc);
        }
        // 分组查询补总量行；空分组时执行器已产出 dimKey=TOTAL 的单元，避免重复
        if (!groups.isEmpty()) {
            OutputCell total = new OutputCell();
            total.dimKey = "TOTAL";
            total.visitCount = raw.totalVisits;
            if (raw.totalPatients >= minUnit) {
                total.status = "EXACT";
                total.displayCount = raw.totalPatients;
            } else if (raw.totalPatients > 0) {
                total.status = "SUPPRESSED";
                total.displayCount = null;
            } else {
                total.status = "EXACT";
                total.displayCount = 0;
            }
            out.add(total);
        }
        return out;
    }
}
