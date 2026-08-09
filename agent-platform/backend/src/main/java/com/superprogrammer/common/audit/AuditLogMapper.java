package com.superprogrammer.common.audit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * audit_logs Mapper（日志系统 LOG-FR-09）。append-only：仅 insert/select（V78 REVOKE 后 DB 层强制）。
 * detailJson 为 JSONB 列，String↔jsonb 转换由实体字段上的 JsonbStringTypeHandler 承担。
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogEntity> {
}
