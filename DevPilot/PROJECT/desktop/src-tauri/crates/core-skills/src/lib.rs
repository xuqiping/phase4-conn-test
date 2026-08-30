//! core-skills：Skills 解析/生成/注册/斜杠调用。
//! 对应 FR-025。S1 = SKILL.md 解析（skill_file）+ 注册表同步（registry）。

pub mod generator;
pub mod registry;
pub mod skill_file;

/// crate 名常量，供主程序装配自检（冒烟测试用）。
pub const CRATE_NAME: &str = "core-skills";

#[cfg(test)]
mod tests {
    use super::CRATE_NAME;

    /// 冒烟测试：crate 可编译、可互引（P01 Step 2 验收）。
    #[test]
    fn smoke_placeholder() {
        assert_eq!(CRATE_NAME, "core-skills");
    }
}
