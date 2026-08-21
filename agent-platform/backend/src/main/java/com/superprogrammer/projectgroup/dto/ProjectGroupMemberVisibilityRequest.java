package com.superprogrammer.projectgroup.dto;

import lombok.Data;

import java.util.Map;

/**
 * 成员级可见性覆盖请求（17x#2，V139）：overrides=稀疏覆盖 map。
 * key∈CHAT/EMBED/RERANK/IMAGE/VIDEO，value∈OWN/ALL；null=不动；空 map=清空回落组级。
 * 判定优先级：成员覆盖 > 组模块覆盖 > 组默认。覆盖写在产出归属人行上。
 */
@Data
public class ProjectGroupMemberVisibilityRequest {

    /** 稀疏覆盖 map；null=不动；空 map=清空。 */
    private Map<String, String> overrides;
}
