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
            Map.entry("file", "文件"),
            // 人工测试遗留问题修复II B2（8x-2）：存量已有写入、字典漏翻的 4 模块
            Map.entry("security", "安全管理"),
            Map.entry("feedback", "公告建议台"),
            Map.entry("project-group", "项目组"),
            Map.entry("audit", "审计链"),
            // B4（8x-3 P1）：Workflow/Execution 写操作新模块
            Map.entry("workflow", "工作流")
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
                    Map.entry("session_kicked", "会话踢出"),
                    // 认证系统增强（多通道）
                    Map.entry("email_send", "发验证邮件"),
                    Map.entry("email_verify", "邮箱验证"),
                    Map.entry("resend_email", "重发验证邮件"),
                    Map.entry("sms_code_send", "发短信验证码"),
                    Map.entry("sms_login", "手机验证码登录"),
                    Map.entry("wechat_login", "微信扫码登录"),
                    Map.entry("password_forgot", "发起找回密码"),
                    Map.entry("password_reset", "重置密码"),
                    Map.entry("geo_login_alert", "异地登录提醒"),
                    Map.entry("credential_bind", "绑定凭证"),
                    Map.entry("credential_unbind", "解绑凭证"),
                    Map.entry("password_change", "修改密码"),
                    // 人工测试遗留问题修复II B1（8x-1 P0 手工行新码）
                    Map.entry("send_register_code", "发注册验证码"),
                    Map.entry("mfa_bind", "绑定TOTP"),
                    Map.entry("mfa_bind_confirm", "确认绑定TOTP"),
                    Map.entry("mfa_unbind", "解绑TOTP"),
                    // B2（8x-2）：AuthService.auditAuth 存量码补翻
                    Map.entry("login_mfa", "MFA两步登录"),
                    Map.entry("account_deleted", "注销账号"),
                    Map.entry("profile_updated", "更新个人资料"))),
            Map.entry("user", Map.ofEntries(
                    Map.entry("update_status", "修改用户状态"),
                    Map.entry("assign_roles", "分配角色"),
                    Map.entry("update_remark", "修改备注"),
                    // B4（8x-3 P1）：DepartmentController
                    Map.entry("dept_create", "创建部门"),
                    Map.entry("dept_update", "修改部门"),
                    Map.entry("dept_delete", "删除部门"),
                    Map.entry("dept_member_add", "添加部门成员"),
                    Map.entry("dept_member_remove", "移除部门成员"))),
            Map.entry("role", Map.ofEntries(
                    Map.entry("update_permissions", "修改角色权限"))),
            Map.entry("agent", Map.ofEntries(
                    Map.entry("publish", "发布智能体"),
                    // B4（8x-3 P1）：AgentController
                    Map.entry("agent_create", "新建智能体"),
                    Map.entry("agent_update", "修改智能体"),
                    Map.entry("agent_delete", "删除智能体"),
                    Map.entry("agent_copy", "复制智能体"),
                    Map.entry("agent_sync", "同步智能体"),
                    Map.entry("permission_set", "设置智能体权限"),
                    Map.entry("kb_binding_set", "设置知识库绑定"),
                    Map.entry("rag_enabled_set", "设置记忆模式开关"),
                    Map.entry("skill_set", "配置智能体能力"),
                    Map.entry("skill_update", "修改智能体能力"),
                    Map.entry("skill_delete", "删除智能体能力"))),
            // B4（8x-3 P1）：WorkflowController + ExecutionController
            Map.entry("workflow", Map.ofEntries(
                    Map.entry("workflow_create", "新建工作流"),
                    Map.entry("workflow_update", "修改工作流"),
                    Map.entry("workflow_delete", "删除工作流"),
                    Map.entry("workflow_duplicate", "复制工作流"),
                    Map.entry("workflow_import", "导入工作流"),
                    Map.entry("kb_binding_set", "设置知识库绑定"),
                    Map.entry("rag_enabled_set", "设置记忆模式开关"),
                    Map.entry("execution_retry", "重试执行"),
                    Map.entry("execution_resume", "恢复执行"),
                    Map.entry("execution_approve", "审批通过"),
                    Map.entry("execution_reject", "审批驳回"),
                    Map.entry("execution_input_submit", "提交执行输入"))),
            Map.entry("kb", Map.ofEntries(
                    Map.entry("kb_delete", "删除知识库"),
                    Map.entry("document_delete", "删除文档"),
                    Map.entry("document_upload", "上传文档"),
                    // B2（8x-2）：KB/RAG 控制器存量注解码补翻
                    Map.entry("kb_update", "修改知识库"),
                    Map.entry("kb_grant", "授权知识库"),
                    Map.entry("kb_revoke", "回收知识库授权"),
                    Map.entry("document_metadata_update", "修改文档元数据"),
                    Map.entry("document_unquarantine", "解除文档隔离"),
                    Map.entry("document_version_create", "新建文档版本"),
                    Map.entry("document_version_activate", "激活文档版本"),
                    Map.entry("document_version_revoke", "撤销文档版本"),
                    Map.entry("rag_eval_dataset_create", "新建评测数据集"),
                    Map.entry("rag_eval_cases_import", "导入评测用例"),
                    Map.entry("rag_eval_cases_export", "导出评测用例"),
                    Map.entry("rag_eval_run_start", "发起评测运行"),
                    Map.entry("rag_feedback_submit", "提交RAG反馈"),
                    Map.entry("rag_feedback_approve", "通过RAG反馈"),
                    Map.entry("rag_feedback_reject", "拒绝RAG反馈"),
                    Map.entry("rag_index_rebuild", "重建索引"),
                    Map.entry("rag_index_rebuild_cancel", "取消重建索引"),
                    Map.entry("rag_index_rollback", "回滚索引"),
                    Map.entry("rag_index_switch", "切换索引"),
                    Map.entry("rag_rollout_update", "更新灰度发布"),
                    Map.entry("rag_rollout_rollback", "回滚灰度发布"),
                    Map.entry("rag_trace_view", "查看调用链"),
                    Map.entry("rag_trace_reverse_lookup", "反查调用链"),
                    Map.entry("ranking_config_update", "修改排序配置"),
                    Map.entry("ranking_default_update", "修改默认排序"))),
            Map.entry("system", Map.ofEntries(
                    Map.entry("update_auth_settings", "修改认证设置"),
                    Map.entry("update_billing_settings", "修改计费设置"),
                    Map.entry("update_rag_memory_settings", "修改 RAG/记忆设置"),
                    Map.entry("update_rag_recall_settings", "修改召回设置"),
                    Map.entry("update_web_search_settings", "修改联网搜索设置"),
                    Map.entry("upload_file", "上传文件"),
                    // B2（8x-2）：系统设置存量码补翻
                    Map.entry("mail_channel_test", "测试邮件通道"),
                    Map.entry("update_auth_channels", "修改登录通道"),
                    Map.entry("update_llm_model_defaults", "修改模型默认配置"),
                    // B4（8x-3 P1）
                    Map.entry("web_search_test", "测试联网搜索"))),
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
                    Map.entry("reconcile_diff", "对账差异"),
                    // B2（8x-2）：价表/支付/组池对账存量码补翻
                    Map.entry("pricing_delete", "删除计价规则"),
                    Map.entry("payment_channel_config_save", "保存支付通道配置"),
                    Map.entry("group_reconcile_diff", "组池对账差异"),
                    Map.entry("payment_order_create", "创建支付订单"),
                    Map.entry("payment_order_cancel", "取消支付订单"),
                    Map.entry("payment_order_paid", "支付成功"),
                    Map.entry("payment_order_failed", "支付失败"),
                    Map.entry("payment_idem_conflict", "支付幂等冲突"),
                    Map.entry("payment_notify_amount_mismatch", "回调金额不符"),
                    Map.entry("payment_notify_terminal_order", "回调终态订单"))),
            Map.entry("asset", Map.ofEntries(
                    Map.entry("public_pool_publish", "发布到公共池"),
                    Map.entry("public_pool_unpublish", "撤出公共池"),
                    Map.entry("public_access_request", "申请公开访问"),
                    Map.entry("public_access_decision", "审批公开访问"),
                    Map.entry("public_access_revoke", "撤销公开访问"),
                    Map.entry("asset_upload", "上传资产"),
                    Map.entry("save_to_library", "存入资产库"),
                    // B2（8x-2）：资产复制补翻
                    Map.entry("asset_copy", "复制资产"))),
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
                    Map.entry("download_edit_video", "下载剪辑视频"),
                    // B2（8x-2）：视频反推补翻
                    Map.entry("reverse_analyze", "视频反推分析"),
                    Map.entry("reverse_localize", "视频反推本地化"))),
            Map.entry("llm", Map.ofEntries(
                    Map.entry("provider_export", "导出供应商配置"),
                    Map.entry("provider_import", "导入供应商配置"),
                    // B4（8x-3 P1）：LlmController + UserLlmController
                    Map.entry("provider_create", "新建供应商"),
                    Map.entry("provider_update", "修改供应商"),
                    Map.entry("provider_delete", "删除供应商"),
                    Map.entry("provider_test", "测试供应商连通"),
                    Map.entry("provider_reload", "重载供应商配置"),
                    Map.entry("user_provider_save", "保存用户级密钥"),
                    Map.entry("user_provider_delete", "删除用户级密钥"),
                    Map.entry("user_provider_test", "测试用户级密钥"))),
            Map.entry("chat", Map.ofEntries(
                    Map.entry("send_message", "发送消息"),
                    Map.entry("chat_completed", "对话完成"),
                    Map.entry("upload_attachment", "上传附件"))),
            Map.entry("canvas", Map.ofEntries(
                    Map.entry("canvas_upload", "画布上传"))),
            // ===== B2（8x-2）：存量已有写入、字典整体缺失的 4 模块 =====
            Map.entry("security", Map.ofEntries(
                    Map.entry("ip_block", "封禁IP"),
                    Map.entry("ip_unblock", "解禁IP"),
                    Map.entry("rule_config_update", "修改防护规则"),
                    Map.entry("event_ack", "确认安全事件"),
                    Map.entry("event_batch_delete", "批量删除安全事件"))),
            Map.entry("feedback", Map.ofEntries(
                    Map.entry("suggestion_submit", "提交建议"),
                    Map.entry("suggestion_review", "审核建议"),
                    Map.entry("suggestion_message", "建议追问留言"),
                    Map.entry("question_submit", "提交提问"),
                    Map.entry("question_answer", "回答提问"),
                    Map.entry("question_close", "关闭提问"),
                    Map.entry("question_message", "提问追问留言"),
                    Map.entry("help_article_create", "新建帮助文章"),
                    Map.entry("help_article_update", "修改帮助文章"),
                    Map.entry("help_article_publish", "发布帮助文章"),
                    Map.entry("help_article_delete", "删除帮助文章"))),
            Map.entry("project-group", Map.ofEntries(
                    Map.entry("group_create", "创建项目组"),
                    Map.entry("group_rename", "重命名项目组"),
                    Map.entry("group_delete", "删除项目组"),
                    Map.entry("member_invite", "邀请成员"),
                    Map.entry("member_remove", "移除成员"),
                    Map.entry("member_role", "修改成员角色"),
                    Map.entry("member_quota", "调整成员配额"),
                    Map.entry("member_reset_used", "重置成员用量"),
                    Map.entry("member_kinds", "修改成员类别"),
                    Map.entry("member_visibility", "修改成员可见性"),
                    Map.entry("visibility_update", "修改组可见性"),
                    Map.entry("invite_accept", "接受邀请"),
                    Map.entry("invite_decline", "拒绝邀请"),
                    Map.entry("invite_cancel", "撤销邀请"),
                    Map.entry("pool_publish", "发布组池"),
                    Map.entry("pool_unpublish", "撤下组池"),
                    Map.entry("pool_apply", "申请划拨"),
                    Map.entry("pool_apply_cancel", "撤销划拨申请"),
                    Map.entry("pool_decide", "审批划拨"),
                    Map.entry("wallet_allocate", "组钱包下拨"),
                    Map.entry("wallet_reclaim", "组钱包回收"))),
            Map.entry("audit", Map.ofEntries(
                    Map.entry("chain_broken", "哈希链校验断裂")))
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
