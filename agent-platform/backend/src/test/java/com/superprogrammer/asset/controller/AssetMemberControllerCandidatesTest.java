package com.superprogrammer.asset.controller;

import com.superprogrammer.asset.service.AssetMemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AssetMemberControllerCandidatesTest {

    private final AssetMemberService service = mock(AssetMemberService.class);
    private final AssetMemberController controller = new AssetMemberController(service);

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void delegatesCandidateSearchWithoutUserManageApi() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(10L, null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        controller.searchCandidates(1L, "alice");

        verify(service).searchCandidates(1L, 10L, false, "alice");
    }
}
