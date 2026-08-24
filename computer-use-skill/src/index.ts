/**
 * computer-use-skill · MCP stdio Server 入口
 * FR-017：以 stdio transport 提供 MCP 协议服务。
 * 工具注册在后续 Step 8 接入（tools/index.ts）。
 */
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";

const server = new McpServer(
  { name: "computer-use-skill", version: "0.1.0" },
  { capabilities: { tools: {} } }
);

import { registerTools } from "./tools/index.js";
registerTools(server);

async function main(): Promise<void> {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  // 审计日志走 stderr（stdout 是 MCP 通道，绝不能污染）
  console.error("[computer-use-skill] stdio server started");
}

main().catch((err: unknown) => {
  console.error("[computer-use-skill] fatal:", err);
  process.exit(1);
});
