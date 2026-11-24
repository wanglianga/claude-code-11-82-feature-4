package com.sandbox.imaging.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandbox.imaging.domain.ImagingRecord;
import com.sandbox.imaging.domain.Patient;
import com.sandbox.imaging.domain.Visit;
import com.sandbox.imaging.dto.CanonicalQuery;
import com.sandbox.imaging.repo.ImagingRecordRepository;
import com.sandbox.imaging.repo.PatientRepository;
import com.sandbox.imaging.repo.VisitRepository;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 查询执行器：在已编译队列成员上按规范化语义计算原始聚合表（抑制前）。
 * 只产出聚合行，绝不返回患者级记录或原始影像。
 */
@Service
public class QueryExecutor {

    private final PatientRepository patientRepo;
    private final ImagingRecordRepository imagingRepo;
    private final VisitRepository visitRepo;
    private final ObjectMapper mapper;

    public QueryExecutor(PatientRepository patientRepo, ImagingRecordRepository imagingRepo,
                         VisitRepository visitRepo, ObjectMapper mapper) {
        this.patientRepo = patientRepo;
        this.imagingRepo = imagingRepo;
        this.visitRepo = visitRepo;
        this.mapper = mapper;
    }

    public RawTable execute(CanonicalQuery cq, List<String> memberKeys, String algorithmVersion) {
        List<Patient> patients = patientRepo.findByPatientKeyIn(memberKeys);
        Map<Long, Patient> patientById = new HashMap<>();
        for (Patient p : patients) patientById.put(p.getId(), p);

        List<Long> patientIds = patients.stream().map(Patient::getId).toList();
        List<ImagingRecord> records = imagingRepo
                .findByPatientIdInAndFeatureVersion(patientIds, algorithmVersion);
        List<Visit> visits = visitRepo.findByPatientIdIn(patientIds);
        Map<Long, List<Visit>> visitsByPatient = new HashMap<>();
        for (Visit v : visits) visitsByPatient.computeIfAbsent(v.getPatient().getId(), k -> new ArrayList<>()).add(v);

        long from = ((Number) cq.timeWindow.get("from")).longValue();
        long to = ((Number) cq.timeWindow.get("to")).longValue();

        // 过滤后的影像记录
        List<ImagingRecord> matched = new ArrayList<>();
        Set<Long> matchedPatients = new HashSet<>();
        for (ImagingRecord r : records) {
            if (r.getImagingDate() < from || r.getImagingDate() > to) continue;
            Patient p = patientById.get(r.getPatient().getId());
            if (!passesFilters(cq, p, r)) continue;
            matched.add(r);
            matchedPatients.add(p.getId());
        }

        // 分组聚合
        Map<String, CellAcc> accs = new TreeMap<>();
        for (ImagingRecord r : matched) {
            Patient p = patientById.get(r.getPatient().getId());
            String key = dimensionKey(cq.groups, p, r);
            CellAcc acc = accs.computeIfAbsent(key, k -> new CellAcc(k));
            acc.patientIds.add(p.getId());
            acc.visitIds.add(r.getVisit() == null ? -r.getId() : r.getVisit().getId());
            Map<String, Object> feats = readFeatures(r);
            accumulate(acc, feats);
        }

        List<RawCell> cells = new ArrayList<>();
        for (CellAcc acc : accs.values()) cells.add(acc.toCell());
        cells.sort(Comparator.comparing(c -> c.dimKey));

        RawTable table = new RawTable();
        table.cells = cells;
        table.totalPatients = matchedPatients.size();
        table.totalVisits = cells.stream().mapToInt(c -> c.visitCount).sum();
        table.cohortSize = memberKeys.size();
        return table;
    }

    private boolean passesFilters(CanonicalQuery cq, Patient p, ImagingRecord r) {
        for (TreeMap<String, Object> f : cq.filters) {
            String field = (String) f.get("field");
            String op = (String) f.get("op");
            Object expected = f.get("value");
            Object actual = extractField(field, p, r);
            if (!matchOp(op, actual, expected)) return false;
        }
        return true;
    }

    private Object extractField(String field, Patient p, ImagingRecord r) {
        return switch (field) {
            case "hospital" -> p.getHospital();
            case "ageBand" -> p.getAgeBand();
            case "sex" -> p.getSex();
            case "modality" -> r.getModality();
            case "bodyPart" -> r.getBodyPart();
            case "lesion_count", "max_mm", "density_score" -> readFeatures(r).get(field);
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private boolean matchOp(String op, Object actual, Object expected) {
        return switch (op) {
            case "eq" -> Objects.equals(actual, expected);
            case "in" -> actual != null && ((List<Object>) expected).contains(actual);
            case "gte" -> actual instanceof Number a && expected instanceof Number e
                    && a.doubleValue() >= e.doubleValue();
            case "lte" -> actual instanceof Number a && expected instanceof Number e
                    && a.doubleValue() <= e.doubleValue();
            default -> false;
        };
    }

    private String dimensionKey(List<String> groups, Patient p, ImagingRecord r) {
        if (groups.isEmpty()) return "TOTAL";
        List<String> parts = new ArrayList<>();
        for (String g : groups) parts.add(g + "=" + extractField(g, p, r));
        return String.join("|", parts);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readFeatures(ImagingRecord r) {
        try {
            return mapper.readValue(r.getFeaturesJson(), new TypeReference<TreeMap<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private void accumulate(CellAcc acc, Map<String, Object> feats) {
        Object mm = feats.get("max_mm");
        if (mm instanceof Number n) { acc.sumMaxMm += n.doubleValue(); acc.maxMmCount++; }
        Object lc = feats.get("lesion_count");
        if (lc instanceof Number n) { acc.sumLesion += n.doubleValue(); acc.lesionCount++; }
    }

    private static class CellAcc {
        final String dimKey;
        final Set<Long> patientIds = new HashSet<>();
        final Set<Long> visitIds = new HashSet<>();
        double sumMaxMm; int maxMmCount;
        double sumLesion; int lesionCount;

        CellAcc(String dimKey) { this.dimKey = dimKey; }

        RawCell toCell() {
            RawCell c = new RawCell();
            c.dimKey = dimKey;
            c.patientCount = patientIds.size();
            c.visitCount = visitIds.size();
            c.avgMaxMm = maxMmCount == 0 ? null : Math.round(sumMaxMm / maxMmCount * 100.0) / 100.0;
            c.avgLesionCount = lesionCount == 0 ? null
                    : Math.round(sumLesion / lesionCount * 100.0) / 100.0;
            return c;
        }
    }

    public static class RawTable {
        public List<RawCell> cells;
        public int totalPatients;
        public int totalVisits;
        public int cohortSize;
    }

    public static class RawCell {
        public String dimKey;
        public int patientCount;
        public int visitCount;
        public Double avgMaxMm;
        public Double avgLesionCount;
    }
}
