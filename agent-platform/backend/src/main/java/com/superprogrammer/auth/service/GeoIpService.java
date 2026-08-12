// agent-platform/backend/src/main/java/com/superprogrammer/auth/service/GeoIpService.java
package com.superprogrammer.auth.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * IP 归属地查询服务（ip2region 离线库，Apache 2.0）。
 *
 * <p>用途：异地登录提醒——登录成功时比对当前 IP 省份与上次登录省份，跨省发提醒邮件。
 *
 * <p>性能：xdb 文件（~11MB）启动时一次性加载到内存常驻（{@code Searcher.newWithBuffer}），
 * 查询走内存 < 1ms，避免每次查询都读文件造成毛刺（沉淀约束：ip2region 加载坑）。
 *
 * <p>降级：xdb 文件缺失/加载失败 → 查询返"未知"，不阻断登录主流程（异地提醒是增强功能，非核心）。
 */
@Slf4j
@Service
public class GeoIpService {

    private org.lionsoul.ip2region.xdb.Searcher searcher;

    /**
     * 启动时加载 xdb 到内存常驻。文件缺失/加载失败不抛异常（降级返"未知"）。
     */
    @PostConstruct
    public void init() {
        try (InputStream is = new ClassPathResource("ip2region.xdb").getInputStream()) {
            byte[] bytes = is.readAllBytes();
            searcher = org.lionsoul.ip2region.xdb.Searcher.newWithBuffer(bytes);
            log.info("ip2region 离线库加载成功，内存常驻（{} 字节）", bytes.length);
        } catch (Exception e) {
            log.warn("ip2region 离线库加载失败，异地登录提醒将降级（查询返‘未知’）: {}", e.toString());
            searcher = null;
        }
    }

    /**
     * 查 IP 归属地的省份。
     *
     * @param ip IPv4 地址
     * @return 省份名（如"上海"）；查不到/库未加载返"未知"
     */
    public String getProvince(String ip) {
        if (searcher == null || ip == null || ip.isBlank()) {
            return "未知";
        }
        // 内网/本地地址直接返"本地"
        if (ip.equals("127.0.0.1") || ip.equals("0:0:0:0:0:0:0:1") || ip.startsWith("192.168.")
                || ip.startsWith("10.") || ip.startsWith("172.")) {
            return "本地";
        }
        try {
            String region = searcher.search(ip);
            // ip2region 返回格式：国家|区域|省份|城市|ISP（如"中国|0|上海|上海市|联通"）
            if (region == null || region.isBlank()) {
                return "未知";
            }
            String[] parts = region.split("\\|");
            if (parts.length >= 3 && !parts[2].equals("0")) {
                return parts[2]; // 省份
            }
            return "未知";
        } catch (Exception e) {
            log.warn("IP 归属地查询失败 ip={} : {}", ip, e.toString());
            return "未知";
        }
    }

    /** 是否库已加载可用。 */
    public boolean isAvailable() {
        return searcher != null;
    }
}
