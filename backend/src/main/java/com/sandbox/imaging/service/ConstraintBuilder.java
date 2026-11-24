package com.sandbox.imaging.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandbox.imaging.domain.Constraint;
import com.sandbox.imaging.inference.LinConstraint;
import org.springframework.stereotype.Component;

import java.util.*;

/** 持久化约束实体与内存线性约束之间的转换，以及结构分区约束的构造 */
@Component
public class ConstraintBuilder {

    private final ObjectMapper mapper;

    public ConstraintBuilder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public LinConstraint toLin(Constraint c) {
        try {
            Map<String, Integer> coeff = mapper.readValue(
                    c.getCoeffsJson(), new TypeReference<TreeMap<String, Integer>>() {});
            return new LinConstraint(coeff, c.getOperator(), c.getRhs(), c.getConstraintKind());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Constraint toEntity(Long spaceId, Long versionId, Long attemptId, Long deliverableId,
                               String kind, LinConstraint lc) {
        Constraint c = new Constraint();
        c.setSpaceId(spaceId);
        c.setAnalysisVersionId(versionId);
        c.setSourceAttemptId(attemptId);
        c.setSourceDeliverableId(deliverableId);
        c.setConstraintKind(kind);
        c.setOperator(lc.op());
        c.setRhs(lc.rhs());
        try {
            c.setCoeffsJson(mapper.writeValueAsString(new TreeMap<>(lc.coeff())));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return c;
    }

    /**
     * 结构分区约束：某维度各取值单元之和等于 TOTAL（队列分区恒等式）。
     * 这是“总数表”与“分组表”能够相减推断的数学根源，必须显式进入联合约束系统。
     */
    public List<LinConstraint> structuralPartitions(Map<String, List<String>> categoriesByDimension) {
        List<LinConstraint> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : categoriesByDimension.entrySet()) {
            Map<String, Integer> coeff = new TreeMap<>();
            for (String v : e.getValue()) coeff.put(e.getKey() + "=" + v, 1);
            coeff.put("TOTAL", -1);
            result.add(new LinConstraint(coeff, "=", 0, "STRUCTURAL_PARTITION"));
        }
        return result;
    }

    /** 多维度分组元组的分区：所有元组单元之和等于 TOTAL */
    public LinConstraint tuplePartition(List<String> tupleVarKeys) {
        Map<String, Integer> coeff = new TreeMap<>();
        for (String k : tupleVarKeys) coeff.put(k, 1);
        coeff.put("TOTAL", -1);
        return new LinConstraint(coeff, "=", 0, "STRUCTURAL_PARTITION");
    }
}
