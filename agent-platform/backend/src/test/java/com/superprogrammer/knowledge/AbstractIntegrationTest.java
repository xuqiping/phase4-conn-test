package com.superprogrammer.knowledge;

import com.superprogrammer.common.config.TestSecurityConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * RAG/知识库集成测基类（真实 PG16 + pgvector 独立库 agent_platform_it + Redis）。
 *
 * <p>运行：{@code mvn test -Dgroups=integration}（须先手动 {@code CREATE DATABASE agent_platform_it} + Redis 开）。
 * 默认 {@code mvn test} 经 surefire {@code excludedGroups=integration} 跳过本组。
 *
 * <p>fixture：halfvec 行须用 {@code ::halfvec} 字面量（MyBatis-Plus 无 halfvec TypeHandler），
 * 字面量由 {@code HalfVecUtil.toHalfVec(float[2048])} 生成。每测用 {@code @Sql} 清理（见 sql/rag/cleanup-rag.sql），
 * 不用 {@code @Transactional} 回滚（须验跨 tx 可见性 + HNSW 命中 + FOR UPDATE SKIP LOCKED 不在回滚 tx 死锁）。
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@Import(TestSecurityConfig.class)
public abstract class AbstractIntegrationTest {
}
