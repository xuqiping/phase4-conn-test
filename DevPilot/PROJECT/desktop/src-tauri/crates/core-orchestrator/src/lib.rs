//! core-orchestrator：chunk→任务编排、subagent 分派、产物生成器（PRD/plan/AGENTS.md）。
//! 对应 FR-003/030~035。当前为 Step 2 骨架占位——P05 填实现。

/// crate 名常量，供主程序装配自检（冒烟测试用）。
pub const CRATE_NAME: &str = "core-orchestrator";

#[cfg(test)]
mod tests {
    use super::CRATE_NAME;

    /// 冒烟测试：crate 可编译、可互引（P01 Step 2 验收）。
    #[test]
    fn smoke_placeholder() {
        assert_eq!(CRATE_NAME, "core-orchestrator");
    }
}
