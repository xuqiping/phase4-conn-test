ALTER TABLE llm_providers
    ADD COLUMN IF NOT EXISTS protocol VARCHAR(32) NOT NULL DEFAULT 'OPENAI_COMPATIBLE';

UPDATE llm_providers
SET protocol = 'ANTHROPIC'
WHERE name = 'claude'
   OR api_endpoint LIKE '%/anthropic%';
