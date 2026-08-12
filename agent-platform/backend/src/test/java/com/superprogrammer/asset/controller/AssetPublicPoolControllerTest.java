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
        when(service.listPublic(10L, false)).thenReturn(List.of(PublicProjectSummaryVO.builder().id(1L).build()));

        controller.list();
        controller.publish(1L, request);
        controller.unpublish(1L);

        verify(service).listPublic(10L, false);
        verify(service).publish(1L, 10L, false, request);
        verify(service).unpublish(1L, 10L, false);
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
