-- ============================================================
-- V61: 项目资产库 · 默认媒体类型 key 英文 → 中文
-- 背景：默认五类 media_type key 原 PROMPT/SCRIPT/IMAGE/VIDEO/AUDIO（英文），与已中文化的叙事角色
--      （人物/道具/场景/风格/通用）不一致；后端 Asset.MEDIA_* 常量值同步改中文（提示词/剧本/图片/视频/音频）。
-- 范围：仅刷「默认五类英文 key」→ 中文。自定义 key（如「地图」）不动；media_category（TEXT/IMAGE/VIDEO/AUDIO）
--      是系统处理类别枚举，英文值不变（前端下拉 label 显中文、value 存英文）。
-- 影响列：
--   1. assets.media_type —— 默认五类的存量值
--   2. asset_projects.media_types JSONB —— 受控词汇 [{key,category}] 的 key 字段
-- 不动：assets.media_category、asset_versions.content/gen_meta（不存 mediaType）、asset_role_links。
-- 回滚：反向 CASE 中文→英文（如需）；Flyway 不自动 down，按需手写。
-- ============================================================

-- 1. assets.media_type 默认五类英文 → 中文（自定义 key 原样保留）
UPDATE assets SET media_type = CASE media_type
    WHEN 'PROMPT' THEN '提示词'
    WHEN 'SCRIPT' THEN '剧本'
    WHEN 'IMAGE'  THEN '图片'
    WHEN 'VIDEO'  THEN '视频'
    WHEN 'AUDIO'  THEN '音频'
    ELSE media_type
END
WHERE media_type IN ('PROMPT','SCRIPT','IMAGE','VIDEO','AUDIO');

-- 2. asset_projects.media_types JSONB 受控词汇 key 英文 → 中文（保 category、保序、保自定义项）
UPDATE asset_projects p
SET media_types = COALESCE((
    SELECT jsonb_agg(
             CASE WHEN m.new_key IS NOT NULL
                  THEN (elem - 'key') || jsonb_build_object('key', m.new_key)
                  ELSE elem END
             ORDER BY ord
           )
    FROM jsonb_array_elements(p.media_types) WITH ORDINALITY AS arr(elem, ord)
    LEFT JOIN (VALUES
        ('PROMPT','提示词'),
        ('SCRIPT','剧本'),
        ('IMAGE','图片'),
        ('VIDEO','视频'),
        ('AUDIO','音频')
    ) AS m(old_key, new_key) ON arr.elem->>'key' = m.old_key
), p.media_types);
