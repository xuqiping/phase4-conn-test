package com.superprogrammer.auth.dingtalk.service;

import com.superprogrammer.auth.dingtalk.config.DingTalkProperties;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DingTalkService {

    private final DingTalkProperties properties;
    private final WebClient.Builder webClientBuilder;

    /** 线上地址；测试用 setter 覆盖指向 MockWebServer */
    @Setter
    private String apiBase = "https://api.dingtalk.com";

    public record DingTalkUserInfo(String unionId, String openId, String nick, String avatar) {}

    /**
     * 用 authCode 换用户 accessToken，再拉用户基本信息。
     */
    public DingTalkUserInfo exchangeUser(String authCode) {
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "钉钉免登未开启");
        }
        String userAccessToken = fetchUserAccessToken(authCode);
        return fetchUserInfo(userAccessToken);
    }

    @SuppressWarnings("unchecked")
    private String fetchUserAccessToken(String authCode) {
        Map<String, Object> body = Map.of(
                "clientId", properties.getAppKey(),
                "clientSecret", properties.getAppSecret(),
                "code", authCode,
                "grantType", "authorization_code"
        );
        Map<String, Object> resp = webClientBuilder.build().post()
                .uri(apiBase + "/v1.0/oauth2/userAccessToken")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                        r -> r.bodyToMono(String.class)
                                .flatMap(t -> Mono.error(new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉换 token 失败: " + t))))
                .bodyToMono(Map.class)
                .block();
        String token = resp == null ? null : (String) resp.get("accessToken");
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉 userAccessToken 为空");
        }
        return token;
    }

    @SuppressWarnings("unchecked")
    private DingTalkUserInfo fetchUserInfo(String userAccessToken) {
        Map<String, Object> resp = webClientBuilder.build().get()
                .uri(apiBase + "/v1.0/contact/users/me")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header("x-acs-dingtalk-access-token", userAccessToken)
                .retrieve()
                .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                        r -> r.bodyToMono(String.class)
                                .flatMap(t -> Mono.error(new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉拉取用户信息失败: " + t))))
                .bodyToMono(Map.class)
                .block();
        if (resp == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉用户信息为空");
        }
        String unionId = (String) resp.get("unionId");
        if (unionId == null || unionId.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉 unionId 为空");
        }
        return new DingTalkUserInfo(
                unionId,
                (String) resp.get("openId"),
                (String) resp.get("nick"),
                (String) resp.get("avatarUrl")
        );
    }
}
