package com.superprogrammer.auth.security;

import com.superprogrammer.auth.service.AuthService;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SecurityConfigAsyncDispatchTest.ProbeController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, SecurityConfigAsyncDispatchTest.ProbeController.class})
class SecurityConfigAsyncDispatchTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private AuthService authService;

    @Test
    void asyncDispatchWithoutJwtIsAllowedToCompleteCommittedStreams() throws Exception {
        mockMvc.perform(get("/async-dispatch-probe")
                        .with(request -> {
                            request.setDispatcherType(DispatcherType.ASYNC);
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @RestController
    static class ProbeController {
        @GetMapping("/async-dispatch-probe")
        String probe() {
            return "ok";
        }
    }
}
