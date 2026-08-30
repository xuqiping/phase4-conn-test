-- V167：帮助文章《视频生成（SeedDance）》参考图上限文案 8→30MB 对齐修复VI（2x#5）。
-- 背景：修复VI（commit 9e68287d）已把附件图实际上限 8→30MB（后端 KIND_MAX_BYTES +
-- 前端 mediaLimits 单源），但 V150 种子的帮助文章文案仍写「≤8MB/张」，误导用户。
-- 已执行脚本不可改 → 本迁移对存量库做 replace；新库 V150 种入后紧跟本修正，终态一致。
UPDATE help_articles
SET content_md = replace(content_md, '≤8MB/张', '≤30MB/张')
WHERE slug = 'video-gen'
  AND content_md LIKE '%≤8MB/张%';
