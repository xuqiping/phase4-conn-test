// Mock 搜索供应商（dev/test 默认，零成本）：query 前缀标记控制行为——
// `[dead-main]` 主家挂（验降级）、`[dead-all]` 全挂（验 502）。
import { Injectable } from "@nestjs/common";
import { SearchProvider, SearchUpstreamError } from "../search.types";

@Injectable()
export class MockPrimarySearch implements SearchProvider {
  readonly name = "mock-primary";
  enabled() { return process.env.SEARCH_MOCK !== "false"; }
  async search(query: string) {
    if (query.startsWith("[dead-main]") || query.startsWith("[dead-all]")) {
      throw new SearchUpstreamError();
    }
    return [{
      title: `Mock主家：${query}`,
      url: "https://example.com/primary",
      snippet: "来自主供应商的结果",
    }];
  }
}

@Injectable()
export class MockBackupSearch implements SearchProvider {
  readonly name = "mock-backup";
  enabled() { return process.env.SEARCH_MOCK !== "false"; }
  async search(query: string) {
    if (query.startsWith("[dead-all]")) throw new SearchUpstreamError();
    return [{
      title: `Mock备家：${query}`,
      url: "https://example.com/backup",
      snippet: "来自备用供应商的结果（降级命中）",
    }];
  }
}
