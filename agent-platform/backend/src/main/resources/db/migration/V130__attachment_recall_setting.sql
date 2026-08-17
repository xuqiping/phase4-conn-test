-- 5x 四轮 U8（C5 附件定向召回）开关 seed（system_settings，幂等）。
-- 语义：消息带附件时，READY 附件开头分块直接注入 prompt（免向量阈值——本轮亲手上传，
-- 相关性先验成立）+ 卡片必出（attached=true 置顶 + 「本附件」徽标）；非 READY 仅出卡带状态标。
-- ragOn=false 时整条召回链跳过，附件注入同样跳过（拍板④：不豁免记忆模式开关）。
-- false=关（回旧行为：附件内容不进 prompt，仅 metadata 回显），热关不发版。
INSERT INTO system_settings (setting_key, setting_value, description) VALUES
    ('rag.memory.attachment-recall.enabled', 'true',
     '附件定向召回开关：消息带附件时 READY 附件开头分块注入 prompt + 卡片置顶带「本附件」徽标；false=不注入（标签召回链不受影响）')
ON CONFLICT (setting_key) DO NOTHING;
