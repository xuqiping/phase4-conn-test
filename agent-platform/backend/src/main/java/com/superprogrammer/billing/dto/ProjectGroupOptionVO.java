package com.superprogrammer.billing.dto;

/**
 * 计划5 Step8：账单页「项目组」筛选项（id+name）。
 * 供 BillingAdminView/MyWalletView 下拉用，与项目组管理端 VO 分离（只读轻量，不带余额等）。
 */
public record ProjectGroupOptionVO(Long id, String name) {
}
