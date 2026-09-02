-- 修复XI XI-3/C1：叙事角色受控词汇两级化——存量一级 shape（string 元素数组）
--   变换为对象数组 [{"key":<s>,"children":[]}]，为「人物→老人/青年/孩童」两级词汇铺底。
-- 幂等：仅变换「数组且含 string 元素」的行（已两级的对象形状行跳过）；坏 JSON 行不动（读侧双容错回落默认）。
-- 逆变换 repair 口径：UPDATE ... SET narrative_roles = (SELECT jsonb_agg(jsonb_typeof(elem)='object' ->> 'key' ...) 见 spec §5。
UPDATE asset_projects
SET narrative_roles = (
    SELECT jsonb_agg(
               CASE WHEN jsonb_typeof(elem) = 'string'
                    THEN jsonb_build_object('key', elem, 'children', '[]'::jsonb)
                    ELSE elem
               END
               ORDER BY ord)
    FROM jsonb_array_elements(narrative_roles) WITH ORDINALITY AS t(elem, ord)
)
WHERE jsonb_typeof(narrative_roles) = 'array'
  AND EXISTS (SELECT 1
              FROM jsonb_array_elements(narrative_roles) e
              WHERE jsonb_typeof(e) = 'string');

-- 新行默认同两级形状（裸 SQL insert 兜底；应用层 create 走 serializeRoles 两级序列化）
ALTER TABLE asset_projects
    ALTER COLUMN narrative_roles SET DEFAULT
    '[{"key":"人物","children":[]},{"key":"道具","children":[]},{"key":"场景","children":[]},{"key":"风格","children":[]},{"key":"通用","children":[]}]'::jsonb;

COMMENT ON COLUMN asset_projects.narrative_roles IS
    '叙事角色两级受控词汇 JSON 数组 [{key,children}]（修复XI V169 两级化；存量 string 数组已迁移，读侧双容错），默认五桶；owner/editor 维护（防标签腐烂）';
