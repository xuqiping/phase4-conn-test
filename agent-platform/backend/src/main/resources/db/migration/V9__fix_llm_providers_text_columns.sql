-- V9: 将 JSONB 列改为 TEXT，避免 MyBatis-Plus String 写入类型不匹配
ALTER TABLE llm_providers ALTER COLUMN models TYPE TEXT USING models::TEXT;
ALTER TABLE llm_providers ALTER COLUMN config TYPE TEXT USING config::TEXT;

-- user_llm_providers 同理
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'user_llm_providers' AND column_name = 'models'
               AND data_type = 'jsonb') THEN
        ALTER TABLE user_llm_providers ALTER COLUMN models TYPE TEXT USING models::TEXT;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'user_llm_providers' AND column_name = 'config'
               AND data_type = 'jsonb') THEN
        ALTER TABLE user_llm_providers ALTER COLUMN config TYPE TEXT USING config::TEXT;
    END IF;
END $$;
