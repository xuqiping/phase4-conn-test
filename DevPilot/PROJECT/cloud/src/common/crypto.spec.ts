// AES-GCM 加解密（Step2 安全清单：上游 Key 加密落盘）。
import { decryptSecret, encryptSecret } from "./crypto";

describe("crypto AES-256-GCM（P02 Step2）", () => {
  beforeAll(() => {
    process.env.API_IDENTITY_ENC_KEY = Buffer.alloc(32, 7).toString("base64");
  });

  it("加密→解密还原；密文不包含明文", () => {
    const enc = encryptSecret("sk-ant-api03-SECRET");
    expect(enc).not.toContain("sk-ant");
    expect(decryptSecret(enc)).toBe("sk-ant-api03-SECRET");
  });

  it("密文被篡改 → 解密报错（GCM 完整性）", () => {
    const enc = encryptSecret("sk-ant-api03-SECRET");
    const buf = Buffer.from(enc, "base64");
    buf[30] ^= 0xff;
    expect(() => decryptSecret(buf.toString("base64"))).toThrow();
  });

  it("每次加密 IV 随机（同明文两次密文不同）", () => {
    expect(encryptSecret("same")).not.toBe(encryptSecret("same"));
  });
});
