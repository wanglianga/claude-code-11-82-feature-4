package com.sandbox.imaging.service;

import java.util.List;
import java.util.Map;

/** 平台允许的结构化字段目录（无原始影像、无患者级身份字段） */
public final class FieldCatalog {

    private FieldCatalog() {}

    /** 可分组/过滤的维度字段 */
    public static final List<String> DIMENSION_FIELDS =
            List.of("hospital", "ageBand", "sex", "modality", "bodyPart");

    /** 结构化特征数值字段 */
    public static final List<String> FEATURE_FIELDS =
            List.of("lesion_count", "max_mm", "density_score");

    /** 随访字段 */
    public static final List<String> VISIT_FIELDS = List.of("followUpDays");

    /** 允许的度量 */
    public static final List<String> MEASURES =
            List.of("PATIENT_COUNT", "VISIT_COUNT", "AVG_MAX_MM", "AVG_LESION_COUNT");

    public static final Map<String, String> FIELD_SOURCE = Map.of(
            "hospital", "patient", "ageBand", "patient", "sex", "patient",
            "modality", "imaging", "bodyPart", "imaging",
            "lesion_count", "feature", "max_mm", "feature", "density_score", "feature",
            "followUpDays", "visit");

    public static boolean isKnownField(String field) {
        return DIMENSION_FIELDS.contains(field) || FEATURE_FIELDS.contains(field)
                || VISIT_FIELDS.contains(field);
    }
}
