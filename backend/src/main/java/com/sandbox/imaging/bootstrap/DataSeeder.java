package com.sandbox.imaging.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandbox.imaging.domain.*;
import com.sandbox.imaging.dto.ApprovalSpec;
import com.sandbox.imaging.repo.*;
import com.sandbox.imaging.service.ApprovalService;
import com.sandbox.imaging.service.AnalysisVersionService;
import com.sandbox.imaging.service.LinkGovernanceService;
import com.sandbox.imaging.service.LinkSealService;
import com.sandbox.imaging.service.LinkTokenService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 虚构演示数据（无真实患者、无原始影像）：
 * - 21 名脱敏患者：医院 A 14 名（P001..P014）、医院 B 7 名（P101..P107）
 * - 批准 APV-S1 队列（上半年影像）：A14 + B1 = 15，公开总数 15 => 相减推断演示
 * - 批准 APV-S2 队列（下半年影像）：A8 + B7 = 15，无小单元 => 正常可用演示
 * - APV-EXPIRED：已到期批准，演示到期拒新读
 * - feat-v1 / feat-v2 两版结构化特征：演示算法升级只新增版本、不改写旧结论
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private final AppUserRepository userRepo;
    private final ResearchSpaceRepository spaceRepo;
    private final WaterlineRepository waterlineRepo;
    private final PatientRepository patientRepo;
    private final VisitRepository visitRepo;
    private final ImagingRecordRepository imagingRepo;
    private final ApprovalRepository approvalRepo;
    private final AnalysisVersionRepository versionRepo;
    private final ApprovalService approvalService;
    private final AnalysisVersionService versionService;
    private final LinkGovernanceService linkGovernanceService;
    private final LinkSealService linkSealService;
    private final LinkTokenService linkTokenService;
    private final PasswordEncoder encoder;
    private final ObjectMapper mapper;

    public DataSeeder(AppUserRepository userRepo, ResearchSpaceRepository spaceRepo,
                      WaterlineRepository waterlineRepo, PatientRepository patientRepo,
                      VisitRepository visitRepo, ImagingRecordRepository imagingRepo,
                      ApprovalRepository approvalRepo, AnalysisVersionRepository versionRepo,
                      ApprovalService approvalService, AnalysisVersionService versionService,
                      LinkGovernanceService linkGovernanceService,
                      LinkSealService linkSealService, LinkTokenService linkTokenService,
                      PasswordEncoder encoder, ObjectMapper mapper) {
        this.userRepo = userRepo;
        this.spaceRepo = spaceRepo;
        this.waterlineRepo = waterlineRepo;
        this.patientRepo = patientRepo;
        this.visitRepo = visitRepo;
        this.imagingRepo = imagingRepo;
        this.approvalRepo = approvalRepo;
        this.versionRepo = versionRepo;
        this.approvalService = approvalService;
        this.versionService = versionService;
        this.linkGovernanceService = linkGovernanceService;
        this.linkSealService = linkSealService;
        this.linkTokenService = linkTokenService;
        this.encoder = encoder;
        this.mapper = mapper;
    }

    static long day(int y, int m, int d) {
        return LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepo.findByUsername("admin").isPresent()) return;

        userRepo.save(new AppUser("admin", encoder.encode("admin123"), "平台管理员", "ADMIN"));
        userRepo.save(new AppUser("analyst", encoder.encode("analyst123"), "影像分析师", "ANALYST"));

        ResearchSpace space = new ResearchSpace("SPACE-IMG", "区域影像中心受控分析沙箱");
        space.setMinPublishUnit(5);
        space.setQuarterlyBudget(10);
        space = spaceRepo.save(space);

        Waterline wlQ3 = new Waterline("WL-2026Q3", "2026 年第三季度末数据水线", day(2026, 9, 30));
        Waterline wlQ4 = new Waterline("WL-2026Q4", "2026 年第四季度 11 月水线", day(2026, 11, 20));
        waterlineRepo.save(wlQ3);
        waterlineRepo.save(wlQ4);

        // 21 名虚构患者
        String[] ageBands = {"18-39", "40-59", "60-74", "75+"};
        String[] sexes = {"F", "M"};
        for (int i = 1; i <= 14; i++) {
            String key = String.format("P%03d", i);
            patientRepo.save(new Patient(key, "A", ageBands[i % 4], sexes[i % 2]));
        }
        for (int i = 1; i <= 7; i++) {
            String key = String.format("P%03d", 100 + i);
            patientRepo.save(new Patient(key, "B", ageBands[(i + 1) % 4], sexes[(i + 1) % 2]));
        }

        List<Patient> patients = patientRepo.findAll();
        // 期间一（2026-03 ~ 2026-05）：A14 + B1 => 队列规模 15
        seedPeriod(patients, 2026, 3, 5, p ->
                p.getHospital().equals("A") || p.getPatientKey().equals("P101"), "feat-v1", 1);
        // 期间二（2026-07 ~ 2026-09）：A8（P001..P008）+ B7 => 队列规模 15
        seedPeriod(patients, 2026, 7, 9, p ->
                (p.getHospital().equals("A") && Integer.parseInt(p.getPatientKey().substring(1)) <= 8)
                        || p.getHospital().equals("B"), "feat-v1", 2);
        // 算法升级：对期间一重新抽取 feat-v2 特征（数值不同），旧 feat-v1 行保留不改写
        seedPeriod(patients, 2026, 3, 5, p ->
                p.getHospital().equals("A") || p.getPatientKey().equals("P101"), "feat-v2", 3);

        long enroll1From = day(2026, 1, 1), enroll1To = day(2026, 6, 30);
        long enroll2From = day(2026, 7, 1), enroll2To = day(2026, 9, 30);
        long now = System.currentTimeMillis();

        ApprovalSpec s1 = baseSpec("中心医院联合影像研究", enroll1From, enroll1To);
        s1.publicTotals = List.of(pt(Map.of(), 15));
        Approval a1 = approvalService.compile(space.getId(), "APV-S1",
                "批准一：上半年队列（A14/B1）联合推断演示", s1);
        approvalService.activate(a1.getId());

        ApprovalSpec s2 = baseSpec("中心医院联合影像研究", enroll2From, enroll2To);
        s2.publicTotals = List.of(pt(Map.of(), 15));
        Approval a2 = approvalService.compile(space.getId(), "APV-S2",
                "批准二：下半年队列（A8/B7）安全披露演示", s2);
        approvalService.activate(a2.getId());

        ApprovalSpec expired = baseSpec("历史回顾研究", day(2025, 1, 1), day(2025, 12, 31));
        expired.enrollmentFrom = day(2025, 1, 1);
        expired.enrollmentTo = day(2025, 12, 31);
        expired.validFrom = day(2025, 1, 1);
        expired.validTo = day(2025, 12, 31);
        expired.publicTotals = List.of();
        Approval ae = approvalService.compile(space.getId(), "APV-EXPIRED",
                "已到期批准（到期拒新读演示）", expired);
        approvalService.activate(ae.getId());

        seedLinkDemo(a2);
    }

    /**
     * 链接版本封存演示：两条路线各封一个版本，均绑定 APV-S2（A8/B7，可安全披露）与 WL-2026Q4。
     * - LNK（托管封条）供“密钥轮换后快速重放”；
     * - LNK（提供方重建）供“最小留存映射 + 提供方可用重建”。
     * 不在此处轮换密钥或重建，留给界面一键演示以便观察完整过程。
     */
    private void seedLinkDemo(Approval a2) {
        if (!linkTokenService.listKeys().isEmpty()) return;
        linkGovernanceService.bootstrapDemo();
        AnalysisVersion v = versionService.create(a2.getId(), a2.getPurpose(), "WL-2026Q4", "feat-v1");

        // 托管封条路线：预置为“重放频率高”，托管面较大但重放快
        linkSealService.seal(a2.getId(), v.getId(), "PRV-A", "ESCROW_SEAL",
                "HIGH", "MEDIUM", 60 * 24 * 30, "tk-rules-v1", null, null);
        // 提供方重建路线：预置为“重放频率低 + 提供方可用”，留存映射最少
        linkSealService.seal(a2.getId(), v.getId(), "PRV-B", "PROVIDER_REBUILD",
                "LOW", "LOW", 60 * 24 * 30, "tk-rules-v1", null, null);
    }

    private void seedPeriod(List<Patient> patients, int year, int monthFrom, int monthTo,
                            java.util.function.Predicate<Patient> filter, String algo, int salt) {
        int visitSeq = 0;
        for (Patient p : patients) {
            if (!filter.test(p)) continue;
            long date = day(year, monthFrom, 10 + (Math.abs(p.getPatientKey().hashCode()) % 18));
            Visit v = new Visit(p, "V-" + algo + "-" + p.getPatientKey() + "-" + salt, date, 180);
            v = visitRepo.save(v);
            int lesions = 1 + Math.abs((p.getPatientKey().hashCode() + salt) % 5);
            double maxMm = Math.round((6 + Math.abs((p.getPatientKey().hashCode() / 7) + salt) % 25
                    + (p.getHospital().equals("B") ? 2 : 0)) * 10.0) / 10.0;
            double density = Math.round((0.2 + (Math.abs(p.getPatientKey().hashCode()) % 70) / 100.0
                    + (algo.equals("feat-v2") ? 0.05 : 0)) * 100.0) / 100.0;
            Map<String, Object> feats = Map.of(
                    "lesion_count", algo.equals("feat-v2") ? lesions + 1 : lesions,
                    "max_mm", algo.equals("feat-v2") ? Math.round((maxMm + 1.5) * 10.0) / 10.0 : maxMm,
                    "density_score", Math.min(0.99, density));
            try {
                imagingRepo.save(new ImagingRecord(p, v, date, "CT", "CHEST", algo,
                        mapper.writeValueAsString(feats)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            visitSeq++;
        }
    }

    private ApprovalSpec baseSpec(String purpose, long enrollFrom, long enrollTo) {
        ApprovalSpec s = new ApprovalSpec();
        s.center = "区域影像中心";
        s.purpose = purpose;
        s.fields = List.of("hospital", "ageBand", "sex", "modality", "bodyPart",
                "lesion_count", "max_mm", "density_score");
        s.dimensions = List.of("hospital", "ageBand", "sex", "modality");
        s.enrollmentFrom = enrollFrom;
        s.enrollmentTo = enrollTo;
        s.allowedFilters = Map.of(
                "hospital", List.of("A", "B"),
                "modality", List.of("CT"));
        s.modalities = List.of("CT");
        s.modelUsage = "描述性统计与风险因素分层，不用于个体诊断";
        s.validFrom = day(2026, 1, 1);
        s.validTo = day(2027, 12, 31);
        return s;
    }

    private ApprovalSpec.PublicTotal pt(Map<String, String> dims, int value) {
        ApprovalSpec.PublicTotal t = new ApprovalSpec.PublicTotal();
        t.dimensions = dims;
        t.value = value;
        return t;
    }
}
