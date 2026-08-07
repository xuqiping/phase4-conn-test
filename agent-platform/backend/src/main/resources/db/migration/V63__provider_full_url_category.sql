-- ============================================================
-- V60: llm_providers 全 URL 语义 + category 四分迁移
-- （模型供应商全URL与类型化改造 FR-002 / FR-005）
--
-- 背景：api_endpoint 原存 base URL，各 provider 运行时拼路径
--   （OpenAI 拼 /chat/completions、embed 拼 /embeddings、Ark 拼
--   /contents/generations/tasks），第三方网关路径不标准即失效。
-- 目标：
--   1) category 四分：CHAT(对话) / VIDEO(视频) / IMAGE(生图,预留) / EMBEDDING(向量)
--      废弃 CHAT_EMBEDDING（双用在全 URL 下不可能成立）与 MEDIA（视频/生图混谈）。
--   2) endpoint best-effort 补全为完整请求 URL（运行时零拼接）：
--      CHAT+OPENAI系 → /chat/completions；CHAT+ANTHROPIC → /v1/messages；
--      EMBEDDING → /embeddings；VIDEO → /contents/generations/tasks；
--      已以目标后缀结尾的行不动；IMAGE 预留不补全。
--   3) 迁移前全量备份 llm_providers_bak_v60，可人工回滚。
--
-- 注意：补全是 best-effort，猜错由人兜底——迁移后须逐条 provider
--   点「测试」验证；原 CHAT_EMBEDDING 行若承担 embed，须人工补建
--   EMBEDDING 行（下方 WARN 会逐条列出 name）。
--
-- 回滚（人工执行，Flyway 不自动回滚）：
--   UPDATE llm_providers p SET category = b.category, api_endpoint = b.api_endpoint
--     FROM llm_providers_bak_v60 b WHERE p.id = b.id;
--   DROP TABLE llm_providers_bak_v60;
-- ============================================================

-- 1) 全量备份（含 AES 密文列，库内权限同原表）
CREATE TABLE llm_providers_bak_v60 AS SELECT * FROM llm_providers;

-- 2) category 重映射：MEDIA→VIDEO、CHAT_EMBEDDING→CHAT（逐条 WARN 留痕）
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN SELECT name FROM llm_providers WHERE category = 'CHAT_EMBEDDING' LOOP
        RAISE WARNING 'V60: CHAT_EMBEDDING→CHAT: provider "%" 原本兼顾 embed；若承担 embed 请补建 EMBEDDING 行', r.name;
    END LOOP;
    FOR r IN SELECT name FROM llm_providers WHERE category = 'MEDIA' LOOP
        RAISE WARNING 'V60: MEDIA→VIDEO: provider "%"', r.name;
    END LOOP;
END $$;

UPDATE llm_providers SET category = 'VIDEO' WHERE category = 'MEDIA';
UPDATE llm_providers SET category = 'CHAT'  WHERE category = 'CHAT_EMBEDDING';

-- 3) endpoint best-effort 补全（按新 category + protocol 逐行判定，改动逐条 WARN）
DO $$
DECLARE
    r       RECORD;
    new_ep  TEXT;
BEGIN
    FOR r IN SELECT id, name, category, protocol, api_endpoint
               FROM llm_providers
              WHERE api_endpoint IS NOT NULL AND btrim(api_endpoint) <> ''
    LOOP
        new_ep := rtrim(r.api_endpoint, '/');

        IF r.category = 'VIDEO' THEN
            -- Ark 视频任务端点（/contents/generations/tasks）
            IF new_ep NOT ILIKE '%/contents/generations/tasks' THEN
                new_ep := new_ep || '/contents/generations/tasks';
            END IF;
        ELSIF r.category = 'EMBEDDING' THEN
            IF new_ep NOT ILIKE '%/embeddings' THEN
                new_ep := new_ep || '/embeddings';
            END IF;
        ELSIF r.category = 'CHAT' THEN
            IF upper(coalesce(r.protocol, '')) = 'ANTHROPIC' THEN
                -- Claude /v1/messages；endpoint 已含 /messages 或以 /v1 结尾时避免 /v1/v1
                IF new_ep ILIKE '%/messages' THEN
                    NULL; -- 已是完整路径
                ELSIF new_ep ILIKE '%/v1' THEN
                    new_ep := new_ep || '/messages';
                ELSE
                    new_ep := new_ep || '/v1/messages';
                END IF;
            ELSE
                -- OpenAI 兼容 /chat/completions
                IF new_ep NOT ILIKE '%/chat/completions' AND new_ep NOT ILIKE '%/messages' THEN
                    new_ep := new_ep || '/chat/completions';
                END IF;
            END IF;
        END IF;
        -- IMAGE：预留 category，不做补全

        IF new_ep <> r.api_endpoint THEN
            RAISE WARNING 'V60 endpoint 补全: provider "%" : % → %', r.name, r.api_endpoint, new_ep;
            UPDATE llm_providers SET api_endpoint = new_ep WHERE id = r.id;
        END IF;
    END LOOP;
END $$;
