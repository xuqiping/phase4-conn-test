# File Keeper HTTP 调试工具

一个简易的 Postman 替代品，用于调试 File Keeper 后台接口。

## 启动方式

### 方式一：直接双击运行

双击 `start.bat`，浏览器会自动（需手动）打开 `http://localhost:3456`

### 方式二：命令行启动

```powershell
cd "C:\AI Projects\http-tool"
node server.js
```

然后浏览器访问：`http://localhost:3456`

## 使用步骤

1. 先确保 File Keeper 后端服务已启动（`http://localhost:8088`）
2. 启动本工具，浏览器打开 `http://localhost:3456`
3. 点击顶部预设按钮【管理员登录】，填入你的邮箱/手机号和密码
4. 点击【发送请求】
5. 从响应中复制 `accessToken`，粘贴到 Headers 里的 `Bearer ` 后面
6. 点击其他预设按钮即可调用各种后台接口

## 文件说明

| 文件 | 说明 |
|---|---|
| `server.js` | Node.js 代理服务器，解决浏览器跨域问题 |
| `index.html` | 调试工具页面 |
| `start.bat` | Windows 一键启动脚本 |

## 注意事项

- 本工具默认代理到 `http://localhost:8088`，可在页面中修改“基础地址”
- 所有请求都通过本工具的 `/api/proxy` 转发，因此能绕过浏览器 CORS 限制
- 按 `Ctrl+C` 可停止服务
