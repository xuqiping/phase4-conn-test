package com.superprogrammer.common.security;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B1 扫描器单测（安全体系 S2，SEC-FR-010）：守卫识别 + 白名单归类。
 */
class PermissionCoverageScannerTest {

    @RestController
    static class DummyController {
        @GetMapping("/api/dummy/bare")
        public R<Void> bare() {
            return R.ok(null);
        }

        @GetMapping("/api/dummy/perm")
        @RequirePermission("dummy:read")
        public R<Void> withPerm() {
            return R.ok(null);
        }

        @GetMapping("/api/dummy/pre")
        @PreAuthorize("hasAuthority('dummy:read')")
        public R<Void> withPreAuthorize() {
            return R.ok(null);
        }
    }

    private HandlerMethod hm(String method) throws NoSuchMethodException {
        return new HandlerMethod(new DummyController(),
                DummyController.class.getMethod(method));
    }

    @Test
    void requirePermissionCountsAsGuarded() throws Exception {
        assertThat(PermissionCoverageScanner.hasGuard(hm("withPerm"))).isTrue();
    }

    @Test
    void preAuthorizeCountsAsGuarded() throws Exception {
        assertThat(PermissionCoverageScanner.hasGuard(hm("withPreAuthorize"))).isTrue();
    }

    @Test
    void bareEndpointIsUnguarded() throws Exception {
        assertThat(PermissionCoverageScanner.hasGuard(hm("bare"))).isFalse();
    }

    @Test
    void publicWhitelistCategorized() {
        assertThat(SecurityEndpointRegistry.categorize("/api/auth/login"))
                .isEqualTo(SecurityEndpointRegistry.Coverage.PUBLIC_WHITELIST);
        assertThat(SecurityEndpointRegistry.categorize("/api/runtime/callbacks/abc/done"))
                .isEqualTo(SecurityEndpointRegistry.Coverage.PUBLIC_WHITELIST);
    }

    @Test
    void reviewedAuthOnlyCategorized() {
        assertThat(SecurityEndpointRegistry.categorize("/api/chat/sessions/1"))
                .isEqualTo(SecurityEndpointRegistry.Coverage.AUTH_ONLY_REVIEWED);
        assertThat(SecurityEndpointRegistry.categorize("/api/files/abc/download"))
                .isEqualTo(SecurityEndpointRegistry.Coverage.AUTH_ONLY_REVIEWED);
    }

    @Test
    void unknownUnguardedPathNeedsReview() {
        assertThat(SecurityEndpointRegistry.categorize("/api/admin/something-new"))
                .isEqualTo(SecurityEndpointRegistry.Coverage.UNGUARDED_REVIEW_NEEDED);
    }
}
