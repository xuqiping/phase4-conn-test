-- 联网搜索开关（CHAT 模式）：会话级持久化，仿 rag_enabled。
-- 用户在 ChatView 开 🌐联网 → request.webSearchEnabled 非-null 时写本列；null=不改继承。
-- 解析优先级：session 列 > 全局默认(关)。仅 CHAT 模式生效（Agent/Workflow 留扩展点）。
ALTER TABLE chat_sessions
    ADD COLUMN IF NOT EXISTS web_search_enabled BOOLEAN;
