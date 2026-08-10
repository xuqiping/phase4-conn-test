package com.superprogrammer.asset.controller;

import com.superprogrammer.asset.dto.AssetCopyRequest;
import com.superprogrammer.asset.service.AssetScriptService;
import com.superprogrammer.asset.service.AssetService;
import com.superprogrammer.asset.service.AssetVersionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AssetControllerCopyTest {

    private final AssetService assetService = mock(AssetService.class);
    private final AssetController controller = new AssetController(
            assetService, mock(AssetVersionService.class), mock(AssetScriptService.class));

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void delegatesCopyWithCurrentIdentity() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(30L, null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        AssetCopyRequest request = new AssetCopyRequest();
        request.setTargetProjectId(2L);

        controller.copy(100L, request);

        verify(assetService).copyCurrent(100L, 30L, false, request);
    }
}
