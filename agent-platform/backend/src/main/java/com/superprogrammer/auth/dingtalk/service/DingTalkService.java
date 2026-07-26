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

import java.util.ArrayList;
import java.util.List;
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

    /** 老版 oapi 基址（企业内部应用免登走这套） */
    @Setter
    private String oapiBase = "https://oapi.dingtalk.com";

    /** 企业 access_token 缓存（gettoken 返回，7200s 有效） */
    private volatile String cachedEnterpriseToken;
    private volatile long enterpriseTokenExpireAt;

    public record DingTalkUserInfo(String unionId, String openId, String nick, String avatar,
                                   List<DingTalkDept> depts) {}

    /** 钉钉部门（id + 名称），用于同步到本地 departments */
    public record DingTalkDept(long deptId, String name) {}

    /**
     * 用 authCode 换用户 accessToken，再拉用户基本信息。
     */
    public DingTalkUserInfo exchangeUser(String authCode) {
        log.info("[DingTalk] exchangeUser 开始, enabled={}, appKey={}, authCode长度={}, authCode前缀={}",
                properties.isEnabled(), maskKey(properties.getAppKey()),
                authCode == null ? 0 : authCode.length(),
                authCode == null ? "(null)" : authCode.substring(0, Math.min(6, authCode.length())));
        if (!properties.isEnabled()) {
            log.warn("[DingTalk] 免登未开启 (dingtalk.enabled=false)");
            throw new BusinessException(ErrorCode.BAD_REQUEST, "钉钉免登未开启");
        }
        if (properties.getAppKey() == null || properties.getAppKey().isBlank()
                || properties.getAppSecret() == null || properties.getAppSecret().isBlank()) {
            log.error("[DingTalk] 配置缺失: appKey={}, appSecret={}",
                    maskKey(properties.getAppKey()),
                    properties.getAppSecret() == null ? "(null)" : (properties.getAppSecret().isBlank() ? "(blank)" : "(已配)"));
            throw new BusinessException(ErrorCode.BAD_REQUEST, "钉钉 AppKey/AppSecret 未配置");
        }
        String userAccessToken = fetchUserAccessToken(authCode);
        DingTalkUserInfo info = fetchUserInfo(userAccessToken);
        log.info("[DingTalk] exchangeUser 成功, unionId={}, nick={}", info.unionId(), info.nick());
        return info;
    }

    /** AppKey 掩码：前 6 + 后 4，便于日志核对又不泄露全量 */
    private String maskKey(String key) {
        if (key == null) return "(null)";
        if (key.length() <= 10) return "(len=" + key.length() + ")";
        return key.substring(0, 6) + "..." + key.substring(key.length() - 4) + "(len=" + key.length() + ")";
    }

    @SuppressWarnings("unchecked")
    private String fetchUserAccessToken(String authCode) {
        Map<String, Object> body = Map.of(
                "clientId", properties.getAppKey(),
                "clientSecret", properties.getAppSecret(),
                "code", authCode,
                "grantType", "authorization_code"
        );
        log.info("[DingTalk] 请求 userAccessToken, url={}, clientId={}, code长度={}",
                apiBase + "/v1.0/oauth2/userAccessToken", maskKey(properties.getAppKey()),
                authCode == null ? 0 : authCode.length());
        Map<String, Object> resp = webClientBuilder.build().post()
                .uri(apiBase + "/v1.0/oauth2/userAccessToken")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                        r -> r.bodyToMono(String.class)
                                .flatMap(t -> {
                                    log.error("[DingTalk] 换 token 失败, 钉钉返回: {}", t);
                                    return Mono.error(new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉换 token 失败: " + t));
                                }))
                .bodyToMono(Map.class)
                .block();
        log.info("[DingTalk] userAccessToken 响应字段 = {}", resp == null ? "(null)" : resp.keySet());
        String token = resp == null ? null : (String) resp.get("accessToken");
        if (token == null || token.isBlank()) {
            log.error("[DingTalk] userAccessToken 为空, 完整响应 = {}", resp);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉 userAccessToken 为空");
        }
        return token;
    }

    @SuppressWarnings("unchecked")
    private DingTalkUserInfo fetchUserInfo(String userAccessToken) {
        log.info("[DingTalk] 请求用户信息, url={}, token长度={}",
                apiBase + "/v1.0/contact/users/me",
                userAccessToken == null ? 0 : userAccessToken.length());
        Map<String, Object> resp = webClientBuilder.build().get()
                .uri(apiBase + "/v1.0/contact/users/me")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header("x-acs-dingtalk-access-token", userAccessToken)
                .retrieve()
                .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                        r -> r.bodyToMono(String.class)
                                .flatMap(t -> {
                                    log.error("[DingTalk] 拉取用户信息失败, 钉钉返回: {}", t);
                                    return Mono.error(new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉拉取用户信息失败: " + t));
                                }))
                .bodyToMono(Map.class)
                .block();
        log.info("[DingTalk] 用户信息响应字段 = {}", resp == null ? "(null)" : resp.keySet());
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
                (String) resp.get("avatarUrl"),
                List.of()
        );
    }

    // ============================================================
    // 企业内部应用免登（oapi 老链路）：容器内 JSAPI requestAuthCode 的 code 走这套
    // 链路：appKey/secret → gettoken → 企业 access_token → getuserinfo(code→userid) → user/get(userid→unionId)
    // ============================================================

    public DingTalkUserInfo exchangeUserByOapi(String authCode) {
        log.info("[DingTalk] exchangeUserByOapi 开始, appKey={}, authCode长度={}",
                maskKey(properties.getAppKey()), authCode == null ? 0 : authCode.length());
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "钉钉免登未开启");
        }
        if (properties.getAppKey() == null || properties.getAppKey().isBlank()
                || properties.getAppSecret() == null || properties.getAppSecret().isBlank()) {
            log.error("[DingTalk] oapi 配置缺失: appKey={}, appSecret={}",
                    maskKey(properties.getAppKey()),
                    properties.getAppSecret() == null ? "(null)" : (properties.getAppSecret().isBlank() ? "(blank)" : "(已配)"));
            throw new BusinessException(ErrorCode.BAD_REQUEST, "钉钉 AppKey/AppSecret 未配置");
        }
        String accessToken = getEnterpriseAccessToken();
        String userid = fetchUserIdByAuthCode(accessToken, authCode);
        DingTalkUserInfo info = fetchUserInfoByUserid(accessToken, userid);
        log.info("[DingTalk] exchangeUserByOapi 成功, unionId={}, nick={}", info.unionId(), info.nick());
        return info;
    }

    /** 企业 access_token，带缓存（提前 60s 失效） */
    @SuppressWarnings("unchecked")
    private String getEnterpriseAccessToken() {
        long now = System.currentTimeMillis();
        if (cachedEnterpriseToken != null && now < enterpriseTokenExpireAt - 60_000) {
            log.info("[DingTalk] 复用缓存企业 access_token, 剩余{}ms", enterpriseTokenExpireAt - now);
            return cachedEnterpriseToken;
        }
        String url = oapiBase + "/gettoken?appkey=" + properties.getAppKey()
                + "&appsecret=" + properties.getAppSecret();
        log.info("[DingTalk] 请求企业 access_token, url={}", oapiBase + "/gettoken?appkey=" + maskKey(properties.getAppKey()) + "&appsecret=(masked)");
        Map<String, Object> resp = webClientBuilder.build().get()
                .uri(url)
                .retrieve()
                .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                        r -> r.bodyToMono(String.class)
                                .flatMap(t -> {
                                    log.error("[DingTalk] gettoken HTTP 失败: {}", t);
                                    return Mono.error(new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉 gettoken 失败: " + t));
                                }))
                .bodyToMono(Map.class)
                .block();
        checkOapiErrcode(resp, "gettoken");
        String token = (String) resp.get("access_token");
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉企业 access_token 为空");
        }
        Object expires = resp.get("expires_in");
        long expiresMs = expires instanceof Number n ? n.longValue() * 1000L : 7200_000L;
        cachedEnterpriseToken = token;
        enterpriseTokenExpireAt = now + expiresMs;
        log.info("[DingTalk] gettoken 成功, access_token长度={}, 有效期{}ms", token.length(), expiresMs);
        return token;
    }

    /** 免登 code → userid（/topapi/v2/user/getuserinfo） */
    @SuppressWarnings("unchecked")
    private String fetchUserIdByAuthCode(String accessToken, String authCode) {
        log.info("[DingTalk] 请求 getuserinfo, code长度={}", authCode == null ? 0 : authCode.length());
        Map<String, Object> resp = webClientBuilder.build().post()
                .uri(oapiBase + "/topapi/v2/user/getuserinfo?access_token=" + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", authCode))
                .retrieve()
                .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                        r -> r.bodyToMono(String.class)
                                .flatMap(t -> {
                                    log.error("[DingTalk] getuserinfo HTTP 失败: {}", t);
                                    return Mono.error(new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉 getuserinfo 失败: " + t));
                                }))
                .bodyToMono(Map.class)
                .block();
        checkOapiErrcode(resp, "getuserinfo");
        Object result = resp.get("result");
        if (!(result instanceof Map<?, ?> resultMap)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉 getuserinfo 无 result");
        }
        String userid = (String) resultMap.get("userid");
        if (userid == null || userid.isBlank()) {
            log.error("[DingTalk] getuserinfo userid 为空, result={}", result);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉 userid 为空（authCode 可能过期或属异企业）");
        }
        log.info("[DingTalk] getuserinfo 成功, userid长度={}", userid.length());
        return userid;
    }

    /** userid → unionId/name/avatar（/topapi/v2/user/get） */
    @SuppressWarnings("unchecked")
    private DingTalkUserInfo fetchUserInfoByUserid(String accessToken, String userid) {
        log.info("[DingTalk] 请求 user/get, userid长度={}", userid.length());
        Map<String, Object> resp = webClientBuilder.build().post()
                .uri(oapiBase + "/topapi/v2/user/get?access_token=" + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("userid", userid))
                .retrieve()
                .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                        r -> r.bodyToMono(String.class)
                                .flatMap(t -> {
                                    log.error("[DingTalk] user/get HTTP 失败: {}", t);
                                    return Mono.error(new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉 user/get 失败: " + t));
                                }))
                .bodyToMono(Map.class)
                .block();
        checkOapiErrcode(resp, "user/get");
        Object result = resp.get("result");
        if (!(result instanceof Map<?, ?> resultMap)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉 user/get 无 result");
        }
        String unionId = (String) resultMap.get("unionid");
        if (unionId == null || unionId.isBlank()) {
            log.error("[DingTalk] user/get unionid 为空, result={}", result);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉 unionId 为空");
        }

        // 解析 dept_id_list → 逐个拉部门名，组装 depts 供本地同步
        List<DingTalkDept> depts = new ArrayList<>();
        Object depListObj = resultMap.get("dept_id_list");
        if (depListObj instanceof List<?> depList) {
            for (Object o : depList) {
                if (o instanceof Number n) {
                    long did = n.longValue();
                    String nm = fetchDeptName(accessToken, did);
                    if (nm != null && !nm.isBlank()) {
                        depts.add(new DingTalkDept(did, nm));
                    }
                }
            }
        }
        log.info("[DingTalk] 用户所属部门解析完成, depts={}", depts);

        return new DingTalkUserInfo(
                unionId,
                (String) resultMap.get("openid"),
                (String) resultMap.get("name"),
                (String) resultMap.get("avatar"),
                depts
        );
    }

    /** 钉钉部门详情 → 部门名（/topapi/v2/department/get） */
    @SuppressWarnings("unchecked")
    private String fetchDeptName(String accessToken, long deptId) {
        try {
            Map<String, Object> resp = webClientBuilder.build().post()
                    .uri(oapiBase + "/topapi/v2/department/get?access_token=" + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("dept_id", deptId))
                    .retrieve()
                    .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                            r -> r.bodyToMono(String.class)
                                    .flatMap(t -> Mono.error(new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉 department/get 失败: " + t))))
                    .bodyToMono(Map.class)
                    .block();
            checkOapiErrcode(resp, "department/get");
            Object result = resp.get("result");
            if (result instanceof Map<?, ?> m) {
                return (String) m.get("name");
            }
        } catch (BusinessException e) {
            log.warn("[DingTalk] 拉取部门名失败, deptId={}, 忽略继续: {}", deptId, e.getMessage());
        }
        return null;
    }

    /** oapi 返回 errcode!=0 抛业务异常并打日志 */
    private void checkOapiErrcode(Map<String, Object> resp, String api) {
        if (resp == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉 " + api + " 无响应");
        }
        Object errcode = resp.get("errcode");
        long code = errcode instanceof Number n ? n.longValue() : 0L;
        if (code != 0L) {
            log.error("[DingTalk] {} 业务失败, errcode={}, errmsg={}, 原始响应={}", api, code, resp.get("errmsg"), resp);
            throw new BusinessException(ErrorCode.UNAUTHORIZED,
                    "钉钉 " + api + " 失败: errcode=" + code + ", errmsg=" + resp.get("errmsg"));
        }
    }
}
