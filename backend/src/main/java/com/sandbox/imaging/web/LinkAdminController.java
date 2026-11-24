package com.sandbox.imaging.web;

import com.sandbox.imaging.domain.ProviderProfile;
import com.sandbox.imaging.domain.PurposeAuthorization;
import com.sandbox.imaging.service.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 管理员：提供方登记/可用性、令牌密钥轮换、用途授权恢复、强制到期等链接治理动作。
 */
@RestController
@RequestMapping("/api/admin/link")
public class LinkAdminController {

    private final LinkTokenService tokenService;
    private final LinkGovernanceService governanceService;
    private final LinkSealService sealService;
    private final LinkExpiryService expiryService;

    public LinkAdminController(LinkTokenService tokenService, LinkGovernanceService governanceService,
                               LinkSealService sealService, LinkExpiryService expiryService) {
        this.tokenService = tokenService;
        this.governanceService = governanceService;
        this.sealService = sealService;
        this.expiryService = expiryService;
    }

    @PostMapping("/bootstrap")
    public Map<String, Object> bootstrap() {
        return governanceService.bootstrapDemo();
    }

    @GetMapping("/providers")
    public List<ProviderProfile> providers() {
        return governanceService.listProviders();
    }

    @PostMapping("/providers")
    public ProviderProfile createProvider(@RequestBody Map<String, String> body) {
        return governanceService.createProvider(body.get("providerCode"), body.get("name"),
                body.get("coversHospital"), body.getOrDefault("note", ""));
    }

    @PostMapping("/providers/{providerCode}/status")
    public ProviderProfile setProviderStatus(@PathVariable String providerCode,
                                             @RequestBody Map<String, String> body) {
        return governanceService.setProviderStatus(providerCode, body.get("status"), body.get("note"));
    }

    @PostMapping("/keys/rotate")
    public Map<String, Object> rotateKey(@RequestBody(required = false) Map<String, String> body) {
        return tokenService.rotateKey(body == null ? "定期轮换" : body.getOrDefault("note", "定期轮换"));
    }

    @PostMapping("/purpose-authorizations/{id}/status")
    public Map<String, Object> purposeAuthStatus(@PathVariable Long id,
                                                  @RequestBody Map<String, Object> body) {
        boolean missing = Boolean.TRUE.equals(body.get("missing"));
        PurposeAuthorization pa = governanceService.markPurposeAuthorizationMissing(id, missing);
        Map<String, Object> m = new TreeMap<>();
        m.put("id", pa.getId());
        m.put("code", pa.getCode());
        m.put("status", pa.getStatus());
        return m;
    }

    @PostMapping("/approvals/{approvalId}/purpose-authorization/restore")
    public Map<String, Object> restorePurposeAuthorization(@PathVariable Long approvalId) {
        PurposeAuthorization pa = sealService.restorePurposeAuthorization(approvalId);
        Map<String, Object> m = new TreeMap<>();
        m.put("id", pa.getId());
        m.put("code", pa.getCode());
        m.put("status", pa.getStatus());
        m.put("snapshotHash", pa.getSnapshotHash());
        m.put("purposeScope", pa.getPurposeScope());
        return m;
    }

    @PostMapping("/versions/{id}/force-expire")
    public Map<String, Object> forceExpire(@PathVariable Long id,
                                           @RequestBody(required = false) Map<String, String> body) {
        return expiryService.forceExpire(id, body == null ? "管理员强制到期" : body.getOrDefault("reason", "管理员强制到期"));
    }
}
