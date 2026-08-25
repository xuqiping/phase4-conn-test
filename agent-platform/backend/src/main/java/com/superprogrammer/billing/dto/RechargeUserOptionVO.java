// agent-platform/backend/src/main/java/com/superprogrammer/billing/dto/RechargeUserOptionVO.java
package com.superprogrammer.billing.dto;

/**
 * 7x：admin 充值页用户下拉选项（账号 + 昵称/姓名）。
 * name 可空（未设置时前端回落只显 username）。
 */
public record RechargeUserOptionVO(Long userId, String username, String name) {
}
