package com.superprogrammer.common.audit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * audit_logs Mapper（日志系统 LOG-FR-09）。append-only：仅 insert/select（V78 REVOKE 后 DB 层强制）。
 * detailJson 为 JSONB 列，String↔jsonb 转换由实体字段上的 JsonbStringTypeHandler 承担。
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogEntity> {

    /**
     * 安全体系 S2 D1：审计链写入串行化——事务级咨询锁包住「读末行 hash → 插新行」，
     * 防两线程同读同一 prev_hash 分叉双链。须在事务内调用（xact 锁随事务结束释放）。
     */
    @Select("SELECT pg_advisory_xact_lock(#{key})")
    String advisoryLock(@Param("key") long key);

    /** 安全体系 S2 D1：取当前链末行 record_hash；无行或末行为存量链外行 → null（新行 prev=GENESIS）。 */
    @Select("SELECT record_hash FROM audit_logs ORDER BY id DESC LIMIT 1")
    String selectLastRecordHash();
}
