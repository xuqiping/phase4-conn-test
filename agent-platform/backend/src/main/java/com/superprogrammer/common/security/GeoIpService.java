// agent-platform/backend/src/main/java/com/superprogrammer/common/security/GeoIpService.java
package com.superprogrammer.common.security;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.lionsoul.ip2region.xdb.Searcher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 离线 IP 归属地（11x 加固 · P2-C7）：ip2region xdb 本地库，零外网调用。
 *
 * <p><b>降级设计</b>：xdb 缺失/损坏/查询异常 → 返回 ""（geo 列留空），绝不阻登录主链；
 * IMPOSSIBLE_TRAVEL 冷规则对空 geo 跳过（数据不足不误报）。</p>
 *
 * <p>加载方式：xdb 整库读入内存（~11MB，v4 快照）→ VectorIndex 内存查询，微秒级。
 * 内网/回环 IP 不入库查询，直接返回 "内网"。</p>
 */
@Slf4j
@Service("securityGeoIpService") // 显式 bean 名：与 auth.service.GeoIpService 同名类区分，避免 bean 名冲突
public class GeoIpService {

    /** xdb 资源路径（v3 绑定配套 v4 格式库）。 */
    private static final String XDB_CLASSPATH = "ip2region_v4.xdb";

    private Searcher searcher;

    @PostConstruct
    void init() {
        try {
            ClassPathResource resource = new ClassPathResource(XDB_CLASSPATH);
            if (!resource.exists()) {
                log.warn("ip2region 库文件 {} 缺失(降级:geo 返回空,异地检测停用)", XDB_CLASSPATH);
                return;
            }
            // Searcher 需文件路径/整库字节：classpath 内读全量字节，newWithBuffer 内存模式（微秒级）
            byte[] buffer;
            try (InputStream in = resource.getInputStream()) {
                buffer = in.readAllBytes();
            }
            searcher = Searcher.newWithBuffer(buffer);
            log.info("ip2region 加载完成 size={}KB", buffer.length / 1024);
        } catch (Exception e) {
            log.warn("ip2region 加载失败(降级:geo 返回空) : {}", e.getMessage());
            searcher = null;
        }
    }

    @PreDestroy
    void destroy() {
        if (searcher != null) {
            try {
                searcher.close();
            } catch (Exception ignored) {
                // 关闭失败无影响
            }
        }
    }

    /**
     * 查归属地（如「中国|广东|深圳」）。内网 IP → "内网"；库不可用/查询失败 → ""。
     */
    public String lookup(String ip) {
        if (ip == null || ip.isBlank()) {
            return "";
        }
        if (isInternal(ip)) {
            return "内网";
        }
        if (searcher == null) {
            return "";
        }
        try {
            String region = searcher.search(ip.trim());
            if (region == null) {
                return "";
            }
            // 原始格式「中国|0|广东省|深圳市|电信」→ 压缩取 国家|省|市（去 0 占位与运营商）
            String[] parts = region.split("\\|");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(4, parts.length); i++) {
                if (!parts[i].isEmpty() && !"0".equals(parts[i])) {
                    if (sb.length() > 0) {
                        sb.append('|');
                    }
                    sb.append(parts[i]);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 内网/回环判定（10/8、172.16/12、192.168/16、127/8、IPv6 本地）。 */
    static boolean isInternal(String ip) {
        String v = ip.trim();
        return v.startsWith("10.") || v.startsWith("192.168.") || v.startsWith("127.")
                || v.startsWith("169.254.") || "0:0:0:0:0:0:0:1".equals(v) || "::1".equals(v)
                || v.startsWith("172.16.") || v.startsWith("172.17.") || v.startsWith("172.18.")
                || v.startsWith("172.19.") || v.startsWith("172.2") || v.startsWith("172.30.")
                || v.startsWith("172.31.") || v.startsWith("fc") || v.startsWith("fd");
    }
}
