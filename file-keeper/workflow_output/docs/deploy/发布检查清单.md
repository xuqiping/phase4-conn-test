# v0.1.0 发布检查清单

## ✅ 已完成

- [x] 性能优化和 bug 修复
- [x] 提交代码到 Git
- [x] 配置 Tauri 打包参数
- [x] 更新 README.md
- [x] 创建 CHANGELOG.md
- [x] 创建发布说明
- [x] 开始构建 MSI 安装包

## ⏳ 进行中

- [ ] 等待 MSI 构建完成（预计 5-10 分钟）

## 📋 待完成

### 构建完成后

1. **测试安装包**
   - [ ] 找到生成的 .msi 文件（通常在 `src-tauri/target/release/bundle/msi/`）
   - [ ] 双击安装测试
   - [ ] 验证应用能正常启动
   - [ ] 测试基本功能（添加文件、搜索、分组等）

2. **创建 Git 标签**
   ```bash
   git tag -a v0.1.0 -m "Release v0.1.0 - First public release"
   git push origin v0.1.0
   git push origin phase5
   ```

3. **发布到 GitHub**（可选）
   - [ ] 在 GitHub 创建 Release
   - [ ] 上传 .msi 安装包
   - [ ] 复制发布说明

4. **分享给用户**
   - [ ] 将 .msi 文件分享给测试用户
   - [ ] 提供安装和使用说明

## 📦 构建产物位置

构建完成后，安装包位于：
```
src-tauri/target/release/bundle/msi/File-Keeper_0.1.0_x64_zh-CN.msi
```

## 🎯 下一步

完成 v0.1.0 发布后，可以开始规划 v0.2.0 的新功能：
- 鼠标拖拽多选
- 快捷键绑定
- 智能推荐
- 分组拖拽排序

## 📝 注意事项

1. **首次安装** - 用户可能需要允许 Windows SmartScreen
2. **无签名** - 安装包未签名，会有安全警告（正常现象）
3. **卸载** - 可通过 Windows 设置 > 应用 > 已安装的应用 卸载
4. **数据位置** - 用户数据存储在 `%APPDATA%\com.filekeeper.app\`

## 🐛 如果构建失败

1. 检查错误日志
2. 确保 Rust 工具链正确安装
3. 确保 WiX Toolset 已安装（Windows MSI 需要）
4. 尝试清理后重新构建：
   ```bash
   cd src-tauri
   cargo clean
   cd ..
   npm run tauri:build
   ```
