# C盘清理工具 使用说明

当 C 盘爆满（甚至程序无法运行）时，运行此脚本快速释放空间并生成占用报告。

---

## 运行命令

打开 PowerShell（开始菜单右键 →「Windows PowerShell」或「终端」），粘贴下面这行回车：

```powershell
powershell -ExecutionPolicy Bypass -File "d:\AI Projects\AI-Projects\C盘清理工具.ps1"
```

> 也可以直接用：`& "d:\AI Projects\AI-Projects\C盘清理工具.ps1"`

---

## 脚本分两个阶段

### 阶段1：紧急释放（自动执行，只删安全内容）
立即腾出空间，**不会动你的文档、项目、程序**。清理项：
- 用户临时文件 `%TEMP%`
- Claude 临时文件 `AppData\Local\Temp\claude`
- Windows 临时文件 `C:\Windows\Temp`
- Windows 更新下载缓存、错误报告（WER）
- 缩略图缓存、字体缓存
- 清空回收站

### 阶段2：扫描占用大户
扫描 C 盘顶层目录、用户目录、AppData、ProgramData，列出最大的几十个目录。
报告保存到：**`d:\AI Projects\disk_report.txt`**

---

## 下一步

把 `disk_report.txt` 的内容（或前几名大目录）贴给 Claude，他会逐个判断：
- ✅ 哪些能删
- ⚠️ 哪些要谨慎
- ❌ 哪些绝不能碰

并给出针对性的清理命令。

---

## 注意事项

- 脚本为**全英文**，避免 Windows PowerShell 5.1 的中文编码乱码问题。
- 扫描阶段约需 1～2 分钟，请耐心等待。
- 若脚本位置有变动，请同步修改命令中的路径。
- 阶段1只清理公认安全的内容；深度清理（系统组件、应用缓存等）请根据报告确认后再操作。
