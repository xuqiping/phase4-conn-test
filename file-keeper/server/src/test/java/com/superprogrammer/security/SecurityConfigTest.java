package com.superprogrammer.security;

import com.superprogrammer.common.R;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SecurityConfigTest.SecurityProbeController.class)
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Test
    void superAdminBearerTokenCanAccessAdminProbe() throws Exception {
        String token = jwtService.createAccessToken(1L, AuthConstants.ROLE_SUPER_ADMIN, AuthConstants.STATUS_ACTIVE);

        mockMvc.perform(get("/api/admin/security-probe")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("admin"));
    }

    @Test
    void userBearerTokenCannotAccessAdminProbe() throws Exception {
        String token = jwtService.createAccessToken(2L, "user", AuthConstants.STATUS_ACTIVE);

        mockMvc.perform(get("/api/admin/security-probe")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void requestWithoutTokenReceivesUnauthorizedRBody() throws Exception {
        mockMvc.perform(get("/api/protected/security-probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void requestWithBadTokenReceivesUnauthorizedRBody() throws Exception {
        mockMvc.perform(get("/api/protected/security-probe")
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void lowercaseBearerTokenCanAccessProtectedProbe() throws Exception {
        String token = jwtService.createAccessToken(3L, "user", AuthConstants.STATUS_ACTIVE);

        mockMvc.perform(get("/api/protected/security-probe")
                        .header("Authorization", "bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("protected"));
    }

    @Test
    void anonymousApiCanBeAccessedWithoutToken() throws Exception {
        mockMvc.perform(get("/api/anonymous/security-probe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("anonymous"));
    }

    @RestController
    static class SecurityProbeController {

        @GetMapping("/api/admin/security-probe")
        R<String> adminProbe() {
            return R.ok("admin");
        }

        @GetMapping("/api/protected/security-probe")
        R<String> protectedProbe() {
            return R.ok("protected");
        }

        @GetMapping("/api/anonymous/security-probe")
        R<String> anonymousProbe() {
            return R.ok("anonymous");
        }
    }
}
