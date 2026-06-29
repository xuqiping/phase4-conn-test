-- V39: Excel 多Sheet导入 — knowledge_documents 加解析选项 + 解析告警列
-- parse_options: JSON 存 sheet 选择等。{ "selectedSheets": ["销售表","库存表"] }  null/空=默认行为（导全部 sheet）。
--   与 l1_metadata（String 存 JSON）模式一致，不引入 JSONB/TypeHandler，写入侧 Service 序列化、读出侧 try-catch 容错。
-- parse_warning: 非致命告警（与 parse_error 并列）。sheet 行数截断/宽表降级/cell 截断累积 → 前端黄色徽章。
ALTER TABLE knowledge_documents ADD COLUMN parse_options TEXT;
ALTER TABLE knowledge_documents ADD COLUMN parse_warning TEXT;
COMMENT ON COLUMN knowledge_documents.parse_options IS '解析选项 JSON（Excel sheet 选择等）。空=默认行为。';
COMMENT ON COLUMN knowledge_documents.parse_warning IS '非致命解析告警（截断/降级），前端黄色徽章；与 parse_error（致命 FAILED）并列。';
