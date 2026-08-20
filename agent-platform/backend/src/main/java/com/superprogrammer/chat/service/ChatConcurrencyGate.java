package com.superprogrammer.chat.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 9x#11 聊天并发闸：同一用户同一会话同一时刻只允许一条生成中请求。
 * <p>
 * 前端已有消息队列（FIFO 续发）做正常串行；本闸兜底多标签页/脚本并发直发——
 * 并发流会互踩消息顺序与计费幂等（历史上 interleave 无排序保证）。
 * 闸按 (userId, sessionId) 粒度：不同会话之间允许并行。
 * <p>
 * 本地 ConcurrentHashMap 实现（单实例部署前提；多实例需换 Redis 锁，届时连同
 * InflightGateService 一起评估）。tryAcquire 失败应立即向客户端回明确错误事件，
 * 不得静默排队——排队语义只在前端存在。
 */
@Component
public class ChatConcurrencyGate {

    private final ConcurrentHashMap<String, Boolean> inflight = new ConcurrentHashMap<>();

    private static String key(Long userId, Long sessionId) {
        return userId + ":" + (sessionId == null ? "new" : sessionId);
    }

    /** 尝试占位；false = 已有生成中请求。 */
    public boolean tryAcquire(Long userId, Long sessionId) {
        return inflight.putIfAbsent(key(userId, sessionId), Boolean.TRUE) == null;
    }

    /** 释放占位（必须在生成线程 finally 中调用）。 */
    public void release(Long userId, Long sessionId) {
        inflight.remove(key(userId, sessionId));
    }
}
