-- P02 Step2：上游模型凭证（服务端持有；api_key AES-GCM 加密落盘，密钥走环境变量）
-- 客户端永远拿不到这些 key（AGENTS 铁律：客户端不持有上游模型 Key）

CREATE TABLE api_identities (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  provider          VARCHAR(30) NOT NULL UNIQUE, -- anthropic / openai / bocha ...
  api_key_encrypted TEXT NOT NULL,               -- AES-GCM 密文（base64: iv+cipher+tag）
  priority          INT NOT NULL DEFAULT 0,
  status            SMALLINT NOT NULL DEFAULT 1  -- 1启用 0停用
);
