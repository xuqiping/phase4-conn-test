package com.superprogrammer.runtime.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "runtime.gateway")
public class RuntimeGatewayProperties {

    private String mode = "sidecar";
    private String sidecarBaseUrl = "http://localhost:8090";
    private String javaCallbackBaseUrl = "http://localhost:8080";
}
