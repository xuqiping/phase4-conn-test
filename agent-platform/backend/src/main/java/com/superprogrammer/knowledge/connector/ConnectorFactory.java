package com.superprogrammer.knowledge.connector;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.security.SsrfGuard;
import com.superprogrammer.knowledge.entity.KnowledgeConnector;
import com.superprogrammer.knowledge.service.KnowledgeConnectorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Predicate;

/**
 * C6 连接器工厂（WP6 Step3）：类型 → SPI 实例。密文解密 + 生产 SSRF 装配都在这一处。
 *
 * <p>urlGuard=生产 {@link SsrfGuard}（loopback/内网/链路本地全拒）；S3 的 endpoint 在建客户端前
 * 先 validate（SDK 路径不走 SafeHttpFetch，endpoint 是唯一出口）。已知限制：内网自建 MinIO/
 * Nextcloud 会被拒——若未来需要内网源，加 allow-private 白名单开关（规格未要求，不做）。
 */
@Component
@RequiredArgsConstructor
public class ConnectorFactory {

    private final KnowledgeConnectorService connectorService;

    public KnowledgeConnectorSpi build(KnowledgeConnector connector) {
        Map<String, Object> config = connectorService.decryptConfig(connector);
        FetchLimiter limiter = new FetchLimiter();
        Predicate<String> urlGuard = u -> {
            SsrfGuard.validate(u);
            return true;
        };
        return switch (connector.getType() == null ? "" : connector.getType()) {
            case KnowledgeConnector.TYPE_URL_SITE -> new UrlSiteConnector(config, urlGuard, limiter);
            case KnowledgeConnector.TYPE_WEBDAV -> new WebDavConnector(config, urlGuard, limiter);
            case KnowledgeConnector.TYPE_S3 -> {
                Object endpoint = config.get("endpoint");
                if (endpoint instanceof String ep) {
                    SsrfGuard.validate(ep.trim());
                }
                yield new S3Connector(config, limiter);
            }
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "未知连接器类型: " + connector.getType());
        };
    }
}
