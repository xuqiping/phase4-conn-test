# Office 兼容样本说明

> 本目录是 Office Worker 兼容样本的登记入口。自动测试优先在运行时生成无敏感内容的 synthetic fixture（合成样本），避免提交大型二进制文件。

## 1. 样本分类

| 类别 | 最小样本 | 重点断言 |
|---|---|---|
| Excel | `.xlsx/.xls/.xlsm`，公式、外链、VBA、签名、密码 | 路由、宏部件、公式/外链、源哈希 |
| Word | `.docx/.doc/.docm`，页眉页脚、脚注、文本框、修订 | 路由、Story Range、排除范围、源哈希 |
| PowerPoint | `.pptx/.ppt/.pptm`，多母版、字体、外链、VBA | 路由、母版/链接/宏部件、源哈希 |
| 通用异常 | 损坏 ZIP、不支持扩展、中文/Emoji/长路径、只读/占用 | 稳定错误码、不修改源文件 |

## 2. 生成与来源原则

- 自动测试样本由测试代码临时生成，只放最小 OOXML ZIP entries 和关系文件，不放真实文档正文。
- 真实 Office 样本必须由项目成员专门新建，内容使用虚构姓名、虚构公司和随机数字；不得从客户、财务、人事或个人文件复制。
- 禁止提交账号、密码、Token、密钥、个人信息、商业机密或可识别的文档元数据。
- 第三方公开样本必须记录许可证、下载地址、获取日期和原始 SHA-256；许可证不允许再分发时只登记，不入库。
- 大型或加密样本放在受控测试存储，不进入 Git；密码只进测试机凭据保险库。

## 3. 真实 Office 复验要求

自动 ZIP 扫描只能证明路由器行为，不能证明 Office 保存保真。检查点 0 前至少需要：

1. 在两代受支持 Microsoft Office x64 环境分别打开、另存并重新打开样本。
2. 对 `.xlsm/.pptm` 比较 VBA 工程、模块、引用和签名状态，并确认宏未执行。
3. 对 Excel 公式/外链、Word Story Range、PPT 母版/主题/字体/外链进行人工视觉与结构复核。
4. 每次处理前后记录源文件 SHA-256，必须完全一致；输出文件另记 SHA-256。

## 4. 版本与哈希记录格式

每个真实样本在受控清单中记录：

```text
sample_id: excel-xlsm-vba-basic-001
source: generated-in-house | public-url
license: internal-synthetic | license-name
created_or_downloaded_at: YYYY-MM-DD
sha256: 64-lowercase-hex
size_bytes: integer
office_app: Excel | Word | PowerPoint
office_version: 16.0.xxxxx.xxxxx
office_generation: 2019 | 2021 | Microsoft 365
platform: Windows 10/11 x64
expected_route: SAFE_OOXML | HIGH_FIDELITY_REQUIRED | BLOCKED
expected_invariants: comma-separated assertions
notes: no sensitive data; password location if applicable (never the password)
```

## 术语表

| 术语 | 大白话 |
|---|---|
| synthetic fixture | 测试程序临时拼出的假文件，只保留验证所需结构 |
| invariant | 处理前后必须保持不变的东西，例如源文件哈希 |
