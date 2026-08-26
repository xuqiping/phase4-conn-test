package com.superprogrammer.common.audit;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字典完整性测试（人工测试遗留问题修复II B2 · 8x-2）。
 *
 * <p>KNOWN_CODES = 当前代码<b>实际会写入</b> audit_logs 的全部 module:action 码
 * （来源：@AuditLog 注解 + 服务层 fromMdc/recordTask/auditAuth 手工行全量 grep）。
 * 逐对断言字典有中文标签且不等于原码——新增审计码忘了补字典，本测试即红。
 *
 * <p>B4-B6 每补一批注解，同步：① 字典 ACTION_LABEL；② 本类 KNOWN_CODES。
 */
class AuditLabelDictionaryCompletenessTest {

    /** module → 该模块全部在用 action 码（硬编码清单，与代码 emit 点同步维护）。 */
    private static final Map<String, String[]> KNOWN_CODES = Map.ofEntries(
            Map.entry("auth", new String[]{
                    "register", "login", "login_mfa", "login_locked", "ip_banned",
                    "dingtalk_register", "dingtalk_login", "refresh", "logout", "session_kicked",
                    "account_deleted", "profile_updated",
                    "email_verify", "resend_email", "send_register_code",
                    "sms_code_send", "sms_login", "wechat_login",
                    "password_forgot", "password_reset",
                    "mfa_bind", "mfa_bind_confirm", "mfa_unbind",
                    "credential_bind", "credential_unbind", "password_change"}),
            Map.entry("user", new String[]{
                    "update_status", "assign_roles", "update_remark",
                    // B4（8x-3 P1）：DepartmentController
                    "dept_create", "dept_update", "dept_delete",
                    "dept_member_add", "dept_member_remove"}),
            Map.entry("role", new String[]{"update_permissions"}),
            Map.entry("agent", new String[]{
                    "publish",
                    // B4（8x-3 P1）：AgentController
                    "agent_create", "agent_update", "agent_delete", "agent_copy", "agent_sync",
                    "permission_set", "kb_binding_set", "rag_enabled_set",
                    "skill_set", "skill_update", "skill_delete"}),
            // B4（8x-3 P1）：WorkflowController + ExecutionController（新模块 workflow）
            Map.entry("workflow", new String[]{
                    "workflow_create", "workflow_update", "workflow_delete",
                    "workflow_duplicate", "workflow_import",
                    "kb_binding_set", "rag_enabled_set",
                    "execution_retry", "execution_resume", "execution_approve",
                    "execution_reject", "execution_input_submit"}),
            Map.entry("kb", new String[]{
                    "kb_delete", "kb_update", "kb_grant", "kb_revoke",
                    "document_delete", "document_upload", "document_metadata_update",
                    "document_unquarantine", "document_version_create",
                    "document_version_activate", "document_version_revoke",
                    "rag_eval_dataset_create", "rag_eval_cases_import", "rag_eval_cases_export",
                    "rag_eval_run_start", "rag_feedback_submit", "rag_feedback_approve",
                    "rag_feedback_reject", "rag_index_rebuild", "rag_index_rebuild_cancel",
                    "rag_index_rollback", "rag_index_switch", "rag_rollout_update",
                    "rag_rollout_rollback", "rag_trace_view", "rag_trace_reverse_lookup",
                    "ranking_config_update", "ranking_default_update"}),
            Map.entry("system", new String[]{
                    "update_auth_settings", "update_auth_channels", "update_billing_settings",
                    "update_llm_model_defaults", "update_rag_memory_settings",
                    "update_rag_recall_settings", "update_web_search_settings",
                    "upload_file", "mail_channel_test", "web_search_test"}),
            Map.entry("billing", new String[]{
                    "admin_recharge", "pricing_create", "pricing_update", "pricing_delete",
                    "pricing_export", "pricing_import", "pricing_template_download",
                    "ratio_create", "ratio_update", "ratio_delete",
                    "payment_channel_config_save",
                    "payment_order_create", "payment_order_cancel", "payment_order_paid",
                    "payment_order_failed", "payment_idem_conflict",
                    "payment_notify_amount_mismatch", "payment_notify_terminal_order",
                    "inflight_rejected", "reconcile_diff", "group_reconcile_diff"}),
            Map.entry("asset", new String[]{
                    "asset_upload", "asset_copy", "save_to_library",
                    "public_pool_publish", "public_pool_unpublish",
                    "public_access_request", "public_access_decision", "public_access_revoke"}),
            Map.entry("memory", new String[]{
                    "user_grant_create", "user_grant_apply", "user_grant_approve",
                    "user_grant_reject", "user_grant_revoke", "pool_toggle",
                    "link_request", "link_approve", "link_reject", "link_revoke",
                    "link_approve_revoke", "link_reject_revoke", "link_withdraw_revoke"}),
            Map.entry("media", new String[]{
                    "video_submit", "image_submit", "edit_submit",
                    "video_gen_success", "video_gen_fail",
                    "image_gen_success", "image_gen_fail",
                    "video_edit_success", "video_edit_fail",
                    "download_video", "download_image", "download_edit_video",
                    "reverse_analyze", "reverse_localize"}),
            Map.entry("llm", new String[]{
                    "provider_export", "provider_import",
                    // B4（8x-3 P1）
                    "provider_create", "provider_update", "provider_delete",
                    "provider_test", "provider_reload",
                    "user_provider_save", "user_provider_delete", "user_provider_test"}),
            Map.entry("chat", new String[]{"send_message", "chat_completed", "upload_attachment"}),
            Map.entry("canvas", new String[]{"canvas_upload"}),
            Map.entry("security", new String[]{
                    "ip_block", "ip_unblock", "rule_config_update",
                    "event_ack", "event_batch_delete"}),
            Map.entry("feedback", new String[]{
                    "suggestion_submit", "suggestion_review", "suggestion_message",
                    "question_submit", "question_answer", "question_close", "question_message",
                    "help_article_create", "help_article_update",
                    "help_article_publish", "help_article_delete"}),
            Map.entry("project-group", new String[]{
                    "group_create", "group_rename", "group_delete",
                    "member_invite", "member_remove", "member_role", "member_quota",
                    "member_reset_used", "member_kinds", "member_visibility",
                    "visibility_update", "invite_accept", "invite_decline", "invite_cancel",
                    "pool_publish", "pool_unpublish", "pool_apply", "pool_apply_cancel",
                    "pool_decide", "wallet_allocate", "wallet_reclaim"}),
            Map.entry("audit", new String[]{"chain_broken"}));

    @Test
    void everyEmittedModule_hasChineseLabel() {
        for (String module : KNOWN_CODES.keySet()) {
            String label = AuditLabelDictionary.moduleLabel(module);
            assertNotEquals(module, label, "模块缺中文标签: " + module);
            assertTrue(label != null && !label.isBlank(), "模块标签为空: " + module);
        }
    }

    @Test
    void everyEmittedAction_hasChineseLabel() {
        int total = 0;
        for (Map.Entry<String, String[]> e : KNOWN_CODES.entrySet()) {
            for (String action : e.getValue()) {
                String label = AuditLabelDictionary.actionLabel(e.getKey(), action);
                assertNotEquals(action, label,
                        String.format("动作缺中文标签: %s.%s", e.getKey(), action));
                assertTrue(label != null && !label.isBlank(),
                        String.format("动作标签为空: %s.%s", e.getKey(), action));
                total++;
            }
        }
        // 防呆：清单意外清空（B4 后 18 模块 / 205 码；B5-B6 只增不减）
        assertTrue(total >= 205, "KNOWN_CODES 总数异常: " + total);
        assertTrue(KNOWN_CODES.size() >= 18, "模块数异常: " + KNOWN_CODES.size());
    }

    @Test
    void b2_newModules_labels() {
        assertEquals("安全管理", AuditLabelDictionary.moduleLabel("security"));
        assertEquals("公告建议台", AuditLabelDictionary.moduleLabel("feedback"));
        assertEquals("项目组", AuditLabelDictionary.moduleLabel("project-group"));
        assertEquals("审计链", AuditLabelDictionary.moduleLabel("audit"));
        assertEquals("哈希链校验断裂", AuditLabelDictionary.actionLabel("audit", "chain_broken"));
    }
}
