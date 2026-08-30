//! core-mcp：MCP 客户端——进程管理 + stdio JSON-RPC。
//! 对应 FR-010（市场安装）/ FR-026（server 管理，AC-029）。
//! S2 = rpc（编解码）+ manager（进程生命周期）。FR-027（对外 MCP Server）归二期。

pub mod manager;
pub mod market;
pub mod rpc;

pub use manager::Manager;

/// crate 名常量，供主程序装配自检（冒烟测试用）。
pub const CRATE_NAME: &str = "core-mcp";

#[cfg(test)]
mod tests {
    use super::CRATE_NAME;

    /// 冒烟测试：crate 可编译、可互引（P01 Step 2 验收）。
    #[test]
    fn smoke_placeholder() {
        assert_eq!(CRATE_NAME, "core-mcp");
    }
}
