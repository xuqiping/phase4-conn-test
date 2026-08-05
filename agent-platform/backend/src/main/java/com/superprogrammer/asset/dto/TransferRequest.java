package com.superprogrammer.asset.dto;

import lombok.Data;

/** 转让所有者请求（FR-002 / L1）。toUserId 成为新 owner，旧 owner 降级 editor。 */
@Data
public class TransferRequest {

    /** 新所有者用户 id（须为平台有效用户）。 */
    private Long toUserId;
}
