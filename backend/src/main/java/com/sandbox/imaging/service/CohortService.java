package com.sandbox.imaging.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandbox.imaging.common.ApiException;
import com.sandbox.imaging.common.CanonicalJson;
import com.sandbox.imaging.domain.*;
import com.sandbox.imaging.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 队列编译器：批准编译规格 + 水线 => 确定性队列。
 * 成员 = 水线截止前、入组期间内、满足批准队列定义（allowedFilters/modalities）的脱敏患者键集合。
 * 同批准同水线重复编译得到同一 cohort_hash；复现器据此重建已发布结果。
 */
@Service
public class CohortService {

    private final CohortRepository cohortRepo;
    private final PatientRepository patientRepo;
    private final ImagingRecordRepository imagingRepo;
    private final ObjectMapper mapper;

    public CohortService(CohortRepository cohortRepo, PatientRepository patientRepo,
                         ImagingRecordRepository imagingRepo, ObjectMapper mapper) {
        this.cohortRepo = cohortRepo;
        this.patientRepo = patientRepo;
        this.imagingRepo = imagingRepo;
        this.mapper = mapper;
    }

    @Transactional
    public Cohort compileCohort(Approval approval, Waterline waterline) {
        Optional<Cohort> existing =
                cohortRepo.findByApprovalIdAndWaterlineId(approval.getId(), waterline.getId());
        if (existing.isPresent()) {
            return existing.get();
        }
        List<String> members = resolveMembers(approval, waterline);
        TreeMap<String, Object> spec = new TreeMap<>();
        spec.put("approvalSpecHash", approval.getSpecHash());
        spec.put("waterline", waterline.getLabel());
        spec.put("waterlineCutoff", waterline.getCutoffAt());
        spec.put("memberKeys", members);
        String specJson = CanonicalJson.write(mapper, spec);
        String hash = CanonicalJson.sha256(specJson);

        Cohort c = new Cohort();
        c.setApproval(approval);
        c.setWaterline(waterline);
        c.setCohortHash(hash);
        c.setCohortSpecJson(specJson);
        c.setMemberCount(members.size());
        try {
            c.setMemberKeysJson(mapper.writeValueAsString(members));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return cohortRepo.save(c);
    }

    /** 按当时批准与水线重新解析成员（复现用），不写库 */
    @Transactional(readOnly = true)
    public List<String> resolveMembers(Approval approval, Waterline waterline) {
        Map<String, Object> compiled = readCompiled(approval);
        long from = ((Number) compiled.get("enrollmentFrom")).longValue();
        long to = ((Number) compiled.get("enrollmentTo")).longValue();
        @SuppressWarnings("unchecked")
        Map<String, List<String>> allowedFilters =
                (Map<String, List<String>>) compiled.getOrDefault("allowedFilters", Map.of());
        @SuppressWarnings("unchecked")
        List<String> modalities = (List<String>) compiled.getOrDefault("modalities", List.of());

        List<Patient> all = patientRepo.findAll();
        List<String> keys = new ArrayList<>();
        for (Patient p : all) {
            if (!matchesPatientFilters(p, allowedFilters)) continue;
            // 该患者是否存在满足期间/水线/模态的影像事实
            boolean ok = false;
            for (ImagingRecord r : imagingRepo.findByPatientIdIn(List.of(p.getId()))) {
                if (r.getImagingDate() > waterline.getCutoffAt()) continue;
                if (r.getImagingDate() < from || r.getImagingDate() > to) continue;
                if (!modalities.isEmpty() && !modalities.contains(r.getModality())) continue;
                ok = true;
                break;
            }
            if (ok) keys.add(p.getPatientKey());
        }
        Collections.sort(keys);
        return keys;
    }

    private boolean matchesPatientFilters(Patient p, Map<String, List<String>> allowedFilters) {
        for (Map.Entry<String, List<String>> e : allowedFilters.entrySet()) {
            String actual = switch (e.getKey()) {
                case "hospital" -> p.getHospital();
                case "ageBand" -> p.getAgeBand();
                case "sex" -> p.getSex();
                default -> null;
            };
            if (actual != null && !e.getValue().contains(actual)) return false;
        }
        return true;
    }

    @Transactional(readOnly = true)
    public Cohort mustGet(Long id) {
        return cohortRepo.findById(id).orElseThrow(() -> ApiException.notFound("队列不存在: " + id));
    }

    @Transactional(readOnly = true)
    public List<Cohort> listByApproval(Long approvalId) {
        return cohortRepo.findByApprovalIdOrderByIdDesc(approvalId);
    }

    public Map<String, Object> readCompiled(Approval approval) {
        try {
            return mapper.readValue(approval.getCompiledJson(),
                    new TypeReference<TreeMap<String, Object>>() {});
        } catch (Exception e) {
            throw ApiException.badRequest("批准编译规格解析失败: " + e.getMessage());
        }
    }

    public List<String> memberKeys(Cohort cohort) {
        try {
            return mapper.readValue(cohort.getMemberKeysJson(), new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
