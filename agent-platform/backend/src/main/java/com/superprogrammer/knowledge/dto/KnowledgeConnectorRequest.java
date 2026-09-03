package com.superprogrammer.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * C6 连接器创建/更新（WP6 Step1）。config=类型专属明文结构（服务端校验后 AES-GCM 加密落库，
 * 任何回显接口不返回明文——凭证只写不读）：
 * <ul>
 *   <li>URL_SITE：seedUrl（http(s) 起始，必填）</li>
 *   <li>S3：endpoint/bucket 必填 + accessKey/secretKey 必填 + prefix 可选</li>
 *   <li>WEBDAV：baseUrl 必填 + username 必填 + password 可选</li>
 * </ul>
 */
@Data
public class KnowledgeConnectorRequest {

    @NotBlank
    private String name;

    /** URL_SITE / S3 / WEBDAV（update 时 null=不动）。 */
    private String type;

    /** 类型专属配置（明文，仅落库前存在；update 时 null=保留原密文）。 */
    private Map<String, Object> config;

    /** Spring cron（秒 分 时 日 月 周）；null/空=默认每天 04:00。 */
    private String scheduleCron;

    /** 源删处理：false/缺省=ISOLATED；true=治理删除（update null=不动）。 */
    private Boolean syncOnSourceDelete;
}
