package com.superprogrammer.common.audit;

import java.util.Map;

/**
 * 审计模块/动作的中文标签字典（日志系统问题修复 #2 显示层）。
 *
 * <p>设计：纯静态工具类（无状态、无依赖）——code→中文 是纯函数，无需 Spring 管理，
 * {@link AuditLogVO#from} / Controller 可直接静态调用。
 *
 * <p><b>不动 DB</b>：{@code audit_logs.module/action} 仍存英文码（hash 链 + V90 REVOKE 禁 UPDATE），
 * 中文只在本字典做显示层翻译。未命中的码<b>原样回落</b>（不空、不抛）。
 *
 * <p>新增 action 码时：① 业务侧记码值；② 本字典 {@link #ACTION_LABEL} 补对应中文；
 * ③ {@code AuditLabelDictionaryTest} 补断言。
 */
public final class AuditLabelDictionary {

    private AuditLabelDictionary() {}

    /** 模块码 → 中文名（模块下拉 + 列表「模块」列共用，单一源）。 */
    private static final Map<String, String> MODULE_LABEL = Map.ofEntries(
            Map.entry("auth", "认证"),
            Map.entry("user", "用户"),
            Map.entry("role", "角色权限"),
            Map.entry("agent", "智能体"),
            Map.entry("kb", "知识库"),
            Map.entry("system", "系统设置"),
            Map.entry("billing", "积分计费"),
            Map.entry("asset", "资产库"),
            Map.entry("memory", "记忆"),
            Map.entry("media", "媒体生成"),
            Map.entry("llm", "模型供应商"),
            Map.entry("chat", "智能对话"),
            Map.entry("canvas", "无限画布"),
            Map.entry("file", "文件")
    );

    /** 动作码 → 中文名，按模块分组（key=module, value=action 码表）。未命中回落原码。 */
    private static final Map<String, Map<String, String>> ACTION_LABEL = Map.ofEntries(
            Map.entry("auth", Map.ofEntries(
                    Map.entry("register", "注册"),
                    Map.entry("login", "登录"),
                    Map.entry("login_locked", "登录锁定"),
                    Map.entry("ip_banned", "IP 封禁"),
                    Map.entry("dingtalk_register", "钉钉注册"),
                    Map.entry("dingtalk_login", "钉钉登录"),
                    Map.entry("refresh", "刷新令牌"),
                    Map.entry("logout", "登出"),
                    Map.entry("session_kicked", "会话踢出"))),
            Map.entry("user", Map.ofEntries(
                    Map.entry("update_status", "修改用户状态"),
                    Map.entry("assign_roles", "分配角色"))),
            Map.entry("role", Map.ofEntries(
                    Map.entry("update_permissions", "修改角色权限"))),
            Map.entry("agent", Map.ofEntries(
                    Map.entry("publish", "发布智能体"))),
            Map.entry("kb", Map.ofEntries(
                    Map.entry("kb_delete", "删除知识库"),
                    Map.entry("document_delete", "删除文档"),
                    Map.entry("document_upload", "上传文档"))),
            Map.entry("system", Map.ofEntries(
                    Map.entry("update_auth_settings", "修改认证设置"),
                    Map.entry("update_billing_settings", "修改计费设置"),
                    Map.entry("update_rag_memory_settings", "修改 RAG/记忆设置"),
                    Map.entry("update_rag_recall_settings", "修改召回设置"),
                    Map.entry("update_web_search_settings", "修改联网搜索设置"),
                    Map.entry("upload_file", "上传文件"))),
            Map.entry("billing", Map.ofEntries(
                    Map.entry("admin_recharge", "管理员充值"),
                    Map.entry("pricing_create", "新建计价规则"),
                    Map.entry("pricing_update", "修改计价规则"),
                    Map.entry("pricing_export", "导出价表"),
                    Map.entry("pricing_template_download", "下载价表模板"),
                    Map.entry("pricing_import", "导入价表"),
                    Map.entry("ratio_create", "新建积分阶梯"),
                    Map.entry("ratio_update", "修改积分阶梯"),
                    Map.entry("ratio_delete", "删除积分阶梯"),
                    Map.entry("idempotency_conflict", "幂等冲突"),
                    Map.entry("inflight_rejected", "并发拦截"),
                    Map.entry("reconcile_diff", "对账差异"))),
            Map.entry("asset", Map.ofEntries(
                    Map.entry("public_pool_publish", "发布到公共池"),
                    Map.entry("public_pool_unpublish", "撤出公共池"),
                    Map.entry("public_access_request", "申请公开访问"),
                    Map.entry("public_access_decision", "审批公开访问"),
                    Map.entry("public_access_revoke", "撤销公开访问"),
                    Map.entry("asset_upload", "上传资产"),
                    Map.entry("save_to_library", "存入资产库"))),
            Map.entry("memory", Map.ofEntries(
                    Map.entry("user_grant_create", "发起用户授权"),
                    Map.entry("user_grant_apply", "申请用户授权"),
                    Map.entry("user_grant_approve", "通过用户授权"),
                    Map.entry("user_grant_reject", "拒绝用户授权"),
                    Map.entry("user_grant_revoke", "撤销用户授权"),
                    Map.entry("pool_toggle", "切换公共池"),
                    Map.entry("link_request", "发起项目关联"),
                    Map.entry("link_approve", "通过项目关联"),
                    Map.entry("link_reject", "拒绝项目关联"),
                    Map.entry("link_revoke", "撤销项目关联"),
                    Map.entry("link_approve_revoke", "通过撤销关联"),
                    Map.entry("link_reject_revoke", "拒绝撤销关联"),
                    Map.entry("link_withdraw_revoke", "撤回撤销申请"))),
            Map.entry("media", Map.ofEntries(
                    Map.entry("video_submit", "提交视频生成"),
                    Map.entry("image_submit", "提交图片生成"),
                    Map.entry("edit_submit", "提交视频剪辑"),
                    Map.entry("video_gen_success", "视频生成成功"),
                    Map.entry("video_gen_fail", "视频生成失败"),
                    Map.entry("image_gen_success", "图片生成成功"),
                    Map.entry("image_gen_fail", "图片生成失败"),
                    Map.entry("video_edit_success", "视频剪辑成功"),
                    Map.entry("video_edit_fail", "视频剪辑失败"),
                    Map.entry("download_video", "下载视频"),
                    Map.entry("download_image", "下载图片"),
                    Map.entry("download_edit_video", "下载剪辑视频"))),
            Map.entry("llm", Map.ofEntries(
                    Map.entry("provider_export", "导出供应商配置"),
                    Map.entry("provider_import", "导入供应商配置"))),
            Map.entry("chat", Map.ofEntries(
                    Map.entry("send_message", "发送消息"),
                    Map.entry("chat_completed", "对话完成"),
                    Map.entry("upload_attachment", "上传附件"))),
            Map.entry("canvas", Map.ofEntries(
                    Map.entry("canvas_upload", "画布上传")))
    );

    /** 模块码 → 中文；未知码原样回落。 */
    public static String moduleLabel(String module) {
        if (module == null) {
            return null;
        }
        return MODULE_LABEL.getOrDefault(module, module);
    }

    /** (module, action) → 中文；未知 action 原样回落。 */
    public static String actionLabel(String module, String action) {
        if (action == null) {
            return null;
        }
        Map<String, String> moduleActions = module == null ? null : ACTION_LABEL.get(module);
        if (moduleActions == null) {
            return action;
        }
        return moduleActions.getOrDefault(action, action);
    }
}
