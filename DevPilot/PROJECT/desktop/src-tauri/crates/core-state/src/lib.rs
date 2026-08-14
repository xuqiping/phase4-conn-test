//! core-state：阶段状态机（YAML 驱动）+ 项目/任务/轮次持久化。
//! 对应 FR-029/046/047/048。当前为 Step 2 骨架占位——Step 4 落 SQLite，Step 6 落状态机引擎。

/// crate 名常量，供主程序装配自检（冒烟测试用）。
pub const CRATE_NAME: &str = "core-state";

#[cfg(test)]
mod tests {
    use super::CRATE_NAME;

    /// 冒烟测试：crate 可编译、可互引（P01 Step 2 验收）。
    #[test]
    fn smoke_placeholder() {
        assert_eq!(CRATE_NAME, "core-state");
    }
}
