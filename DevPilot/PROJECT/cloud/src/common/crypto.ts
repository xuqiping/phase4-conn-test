// 上游 Key 加密（security §3：api_key AES-GCM 加密落盘，密钥走环境变量）。
// 密文格式 base64(iv[12] + tag[16] + cipher)：被篡改时解密直接抛错（GCM 自带完整性）。
import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto";

function key(): Buffer {
  const raw = process.env.API_IDENTITY_ENC_KEY;
  if (!raw) throw new Error("缺少 API_IDENTITY_ENC_KEY 环境变量");
  const buf = Buffer.from(raw, "base64");
  if (buf.length !== 32) throw new Error("API_IDENTITY_ENC_KEY 必须是 32 字节 base64");
  return buf;
}

export function encryptSecret(plain: string): string {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key(), iv);
  const enc = Buffer.concat([cipher.update(plain, "utf8"), cipher.final()]);
  return Buffer.concat([iv, cipher.getAuthTag(), enc]).toString("base64");
}

export function decryptSecret(payload: string): string {
  const buf = Buffer.from(payload, "base64");
  const iv = buf.subarray(0, 12);
  const tag = buf.subarray(12, 28);
  const enc = buf.subarray(28);
  const decipher = createDecipheriv("aes-256-gcm", key(), iv);
  decipher.setAuthTag(tag);
  return Buffer.concat([decipher.update(enc), decipher.final()]).toString("utf8");
}
