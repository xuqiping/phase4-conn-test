-- 5x 四轮 U3：文件记忆卡片向量门控阈值 seed（system_settings，幂等）。
-- 语义：文件卡片原仅按「tag_ids ∩ 选中标签」重叠命中即展示（无相关性判断）→ 无关文件刷屏
-- （叠加 LLM 选标签降级用全集时更炸）。现要求文件至少一个分块与 query 的 cosine 距离
-- ≤ 该阈值才出卡+注入（越小越严；0~2，默认 0.5 与深读 MAX_DISTANCE 同源）。
-- embed 失败 / 无分块（弱记忆文件）→ 零卡片（宁缺勿噪，已接受记档）。
INSERT INTO system_settings (setting_key, setting_value, description) VALUES
    ('memory.recall.file-card-max-distance', '0.5',
     '文件记忆卡片向量门控阈值：文件至少一个分块与提问的 cosine 距离 ≤ 该值才展示/注入（0~2，越小越严）；调大放宽、调小更严')
ON CONFLICT (setting_key) DO NOTHING;
