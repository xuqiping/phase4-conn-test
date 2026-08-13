// agent-platform/backend/src/main/java/com/superprogrammer/common/security/alert/WebhookNotifier.java
package com.superprogrammer.common.security.alert;

import com.superprogrammer.common.metrics.BizMetrics;
import com.superprogrammer.system.service.SystemSettingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 钉钉机器人 webhook 直推器（11x 加固 · P4-C11）：绕过 Alertmanager 适配器，原生 markdown POST。
 *
 * <p>配置（system_settings，env 可覆盖默认）：
 * {@code security.alert.webhook.url}=机器人 webhook（空=不推）；
 * {@code security.alert.webhook.secret}=加签密钥（空=不加签）。</p>
 *
 * <p>红线：超时 5s、不重试（故障风暴防刷量）、任何异常吞 + alertSendFailed 计数（meta 告警看此指标）。
 * URL/secret 不进日志（只记 HTTP 状态码）。</p>
 */
@Slf4j
@Component
public class WebhookNotifier {

    /** 总闸 + webhook 配置键。 */
    public static final String KEY_ALERT_ENABLED = "security.alert.enabled";
    public static final String KEY_WEBHOOK_URL = "security.alert.webhook.url";
    public static final String KEY_WEBHOOK_SECRET = "security.alert.webhook.secret";

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final SystemSettingService systemSettingService;
    private final BizMetrics bizMetrics;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    public WebhookNotifier(SystemSettingService systemSettingService, BizMetrics bizMetrics) {
        this.systemSettingService = systemSettingService;
        this.bizMetrics = bizMetrics;
    }

    /**
     * 异步 POST 钉钉 markdown 卡片。url 空/总闸关 → 直接跳过；故障吞 + 计数。
     *
     * @param title 卡片标题（钉钉列表页显示）
     * @param markdownText markdown 正文
     */
    public void postMarkdown(String title, String markdownText) {
        try {
            String url = getSetting(KEY_WEBHOOK_URL);
            if (url == null || url.isBlank()) {
                return; // 未配 webhook：静默不推（事件已入库，前端可查）
            }
            String secret = getSetting(KEY_WEBHOOK_SECRET);
            String signedUrl = sign(url, secret);
            String body = "{\"msgtype\":\"markdown\",\"markdown\":{\"title\":\""
                    + escJson(title) + "\",\"text\":\"" + escJson(markdownText) + "\"}}";
            HttpRequest request = HttpRequest.newBuilder(URI.create(signedUrl))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            // 异步发送，不占用调用线程（Worker 池线程不被 webhook 阻塞）
            CompletableFuture<HttpResponse<String>> future =
                    httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            HttpResponse<String> resp = future.get(TIMEOUT.toMillis() + 1000, TimeUnit.MILLISECONDS);
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                bizMetrics.alertSent();
            } else {
                bizMetrics.alertSendFailed();
                log.warn("钉钉告警推送失败(HTTP {},已吞) title={}", resp.statusCode(), title);
            }
        } catch (Exception e) {
            bizMetrics.alertSendFailed();
            log.warn("钉钉告警推送异常(已吞) title={} : {}", title, e.getMessage());
        }
    }

    /** 钉钉加签：timestamp + HmacSHA256(secret, timestamp\nsecret) → urlEncode 拼参。secret 空 → 原样。 */
    private String sign(String url, String secret) {
        if (secret == null || secret.isBlank()) {
            return url;
        }
        try {
            long timestamp = System.currentTimeMillis();
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sign = URLEncoder.encode(
                    Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8))),
                    StandardCharsets.UTF_8);
            String sep = url.contains("?") ? "&" : "?";
            return url + sep + "timestamp=" + timestamp + "&sign=" + sign;
        } catch (Exception e) {
            log.warn("钉钉加签失败(降级不加签) : {}", e.getMessage());
            return url;
        }
    }

    private String getSetting(String key) {
        try {
            String v = systemSettingService.getSettingValue(key);
            if (v != null && !v.isBlank()) {
                return v;
            }
        } catch (Exception ignored) {
            // 读库失败 → 试 env 兜底
        }
        // env 覆盖默认（C13：SECURITY_ALERT_WEBHOOK_URL / SECURITY_ALERT_WEBHOOK_SECRET）
        String envKey = key.toUpperCase().replace('.', '_');
        String env = System.getenv(envKey);
        return env == null || env.isBlank() ? null : env;
    }

    /** JSON 字符串转义（钉钉卡片正文含换行 → \n 字面量）。 */
    private static String escJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "")
                .replaceAll("[\\p{Cntrl}&&[^\\n]]", " ");
    }
}
