package com.superprogrammer.asset.controller;

import com.superprogrammer.asset.dto.PublicAccessDecisionRequest;
import com.superprogrammer.asset.service.AssetPublicAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AssetPublicAccessControllerTest {

    private final AssetPublicAccessService service = mock(AssetPublicAccessService.class);
    private final AssetPublicAccessController controller = new AssetPublicAccessController(service);

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void delegatesApplicantAndOwnerOperations() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(10L, null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        PublicAccessDecisionRequest decision = new PublicAccessDecisionRequest();
        decision.setDecision("APPROVED");

        controller.request(1L);
        controller.myStatus(1L);
        controller.list(1L);
        controller.decide(1L, 7L, decision);
        controller.revoke(1L, 7L);

        verify(service).request(1L, 10L);
        verify(service).myStatus(1L, 10L);
        verify(service).listForOwner(1L, 10L, false);
        verify(service).decide(1L, 7L, 10L, false, "APPROVED");
        verify(service).revoke(1L, 7L, 10L, false);
    }
}
