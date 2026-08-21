package com.superprogrammer.projectgroup.dto;

import lombok.Data;

import java.util.List;

/**
 * 成员功能开关请求（17x#2，V139）：allowedKinds=可用模块白名单。
 * null=不限；空数组=全禁；元素∈CHAT/EMBED/RERANK/IMAGE/VIDEO。
 */
@Data
public class ProjectGroupMemberKindsRequest {

    /** 可用模块白名单；null=不限。 */
    private List<String> allowedKinds;
}
