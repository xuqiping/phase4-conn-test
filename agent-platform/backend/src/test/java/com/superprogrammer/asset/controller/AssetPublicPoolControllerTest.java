package com.superprogrammer.asset.controller;

import com.superprogrammer.asset.dto.PublicProjectSummaryVO;
import com.superprogrammer.asset.dto.PublicPublishRequest;
import com.superprogrammer.asset.service.AssetPublicPoolService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetPublicPoolControllerTest {

    private final AssetPublicPoolService service = mock(AssetPublicPoolService.class);
    private final AssetPublicPoolController controller = new AssetPublicPoolController(service);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void delegatesListPublishAndUnpublishWithCurrentIdentity() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(10L, null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        PublicPublishRequest request = new PublicPublishRequest();
        request.setAccessMode("APPROVAL_REQUIRED");
        when(service.listPublic(10L, false, null)).thenReturn(List.of(PublicProjectSummaryVO.builder().id(1L).build()));

        controller.list(null);
        controller.publish(1L, request);
        controller.unpublish(1L);

        verify(service).listPublic(10L, false, null);
        verify(service).publish(1L, 10L, false, request);
        verify(service).unpublish(1L, 10L, false);
    }

    @Test
    void delegatesOfficialFlagToListPublic() {
        // 修复XI B1（XI-2）：GET /public-pool?official=true 透传服务层（官方库=仅管理员发布项目）
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(10L, null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        when(service.listPublic(10L, false, true)).thenReturn(List.of());

        controller.list(true);

        verify(service).listPublic(10L, false, true);
    }

    @Test
    void delegatesAdminSnapshotFlagFromRole() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        controller.publish(2L, null);

        verify(service).publish(2L, 1L, true, null);
    }
}
