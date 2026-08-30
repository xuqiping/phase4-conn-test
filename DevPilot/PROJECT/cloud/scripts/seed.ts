// 开发种子：插一行假 Anthropic 供应商（key 走 AES-GCM 加密落盘）。
// TODO(生产)：换真实 key 时用同一 encryptSecret 流程，密钥只放服务器环境变量。
import { join } from "node:path";
import { PGlite } from "@electric-sql/pglite";
import { encryptSecret } from "../src/common/crypto";

const DATA_DIR = join(__dirname, "..", "data", "pglite");

async function main() {
  if (!process.env.API_IDENTITY_ENC_KEY) {
    // 与 .env.example 同源的 dev 密钥
    process.env.API_IDENTITY_ENC_KEY = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=";
  }
  const db = new PGlite(DATA_DIR);
  const enc = encryptSecret("sk-ant-api03-FAKE-KEY-FOR-DEV-ONLY");
  await db.sql`
    INSERT INTO api_identities (provider, api_key_encrypted, priority, status)
    VALUES ('anthropic', ${enc}, 0, 1)
    ON CONFLICT (provider) DO NOTHING`;
  console.log("[seed] anthropic 假供应商就位（key 已加密）");
  await db.close();
}

void main();
