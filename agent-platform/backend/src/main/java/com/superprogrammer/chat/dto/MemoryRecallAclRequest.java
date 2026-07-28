package com.superprogrammer.chat.dto;

import lombok.Data;

import java.util.List;

/**
 * 计划12 · I2 · ACL 授权配置提交（总体设计 §3.6 + §6 向量 14/15）。
 * <p>
 * {@code PUT /api/chat/memory/projects/{pid}/recall-acl}：全量替换某 reader 在该项目的可读 target 集
 * （先删该 reader 全部旧授权行 → 插新集，{@code created_by}=当前操作人审计）。
 * 单 reader 一次提交，前端按需循环调多次。仅 owner / {@code recall_admin=true} admin 可调（controller 判 403）。
 * <p>
 * <b>target 集校验</b>：targetUserIds 须均为项目在册成员（含 DEPARTED，保交接）；非成员 target 由 service 滤掉
 * （防配错：授权读一个不在项目的人无意义）。空集 = 撤销该 reader 全部授权（合法，清权）。
 */
@Data
public class MemoryRecallAclRequest {
    /** 被授权的读者（项目成员 user_id）。 */
    private Long readerUserId;
    /** 该 reader 可读的全部 target 作者集（全量替换）；null/空 = 清权。须为项目在册成员。 */
    private List<Long> targetUserIds;
}
