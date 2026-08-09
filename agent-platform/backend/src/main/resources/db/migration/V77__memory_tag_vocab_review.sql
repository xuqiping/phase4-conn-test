-- V77: 个人记忆标签「大类词表」改造 —— 数据层。
-- 背景：
--   ① 标签过细不归一（旅游攻略/旅行计划并存、个人信息/个人信息补充并存、seeddance 拆 3 个）。
--      根因 MemoryGenerator 让 LLM 自由发挥 topic，无大类约束 → 同概念开多细标签。
--      改：topic 必须落大类词表（base vocab 可热调），同概念→同 topic→UNIQUE(user,subject,topic) 路径①自动合并。
--   ② 不落词表的内容：建标签时打 needs_review + 发非阻塞通知，用户在标签库裁决（接受为新大类/改名/补别名）。
--   ③ 孤儿标签（embedding 404 期间生成的 NULL 锚点）随回填工具一次性重映射（见 MemoryTagRepairService）。
--
-- 变更：
--   ① memory_tags 加 needs_review：词表外内容标记待裁决，owner 处理后清。
--   ② memory_notifications.type CHECK 加 TAG_NEEDS_REVIEW（+ LINK_REQUEST/LINK_RESULT 与前端 union 对齐，避免误收紧）。
--   ③ system_settings seed memory.tag.vocab：13 个大类 base vocab（可热调；有效词表 = 此 ∪ 用户已批准 topic）。

-- ① memory_tags.needs_review
ALTER TABLE memory_tags ADD COLUMN IF NOT EXISTS needs_review BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN memory_tags.needs_review IS '大类词表外内容标记待用户裁决；owner 改名/补别名/接受后清。默认 false';

-- ② memory_notifications.type CHECK 重建（加 TAG_NEEDS_REVIEW；补 LINK_* 与前端 union 对齐）
ALTER TABLE memory_notifications DROP CONSTRAINT IF EXISTS memory_notifications_type_check;
ALTER TABLE memory_notifications
    ADD CONSTRAINT memory_notifications_type_check
    CHECK (type IN ('SUMMARY_AFFECTED_BY_RECALL',
                    'PROJECT_DELETED_AFFECTED',
                    'LINK_REQUEST',
                    'LINK_RESULT',
                    'TAG_NEEDS_REVIEW'));
COMMENT ON COLUMN memory_notifications.type IS '通知类型：SUMMARY_AFFECTED_BY_RECALL/PROJECT_DELETED_AFFECTED/LINK_REQUEST/LINK_RESULT/TAG_NEEDS_REVIEW（词表外新标签待裁决）';

-- ③ base vocab 大类词表（可热调，不重启）
INSERT INTO system_settings (setting_key, setting_value, description) VALUES
    ('memory.tag.vocab',
     '["个人信息","工作职业","学习教育","兴趣爱好","生活日常","旅行出行","社交人际","健康医疗","财务理财","技术技能","创作内容","教学方法","其他"]',
     '个人记忆标签大类词表（base vocab，可热调）。有效词表 = 此 ∪ 该用户 needs_review=false 的存量 topic。技术技能=编程/工具/AI/大模型/seeddance；创作内容=视频/写作/设计；教学方法=教别人（区别学习教育=自己学）；其他=哨兵触发 needs_review')
ON CONFLICT (setting_key) DO NOTHING;
