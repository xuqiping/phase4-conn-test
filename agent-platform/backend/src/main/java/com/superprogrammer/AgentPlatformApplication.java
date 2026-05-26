// agent-platform/backend/src/main/java/com/superprogrammer/AgentPlatformApplication.java
package com.superprogrammer;

import com.superprogrammer.auth.security.JwtUtil;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(JwtUtil.class)
public class AgentPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentPlatformApplication.class, args);
    }
}
