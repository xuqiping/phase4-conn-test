//! DevPilot 客户端内核入口。
//! P01 Step 2：8 个 workspace crates 骨架已挂（core-state / core-orchestrator /
//! core-sandbox / core-exec / core-meter / core-skills / core-mcp / core-cli），
//! commands/events 在 Step 7 接入。

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .setup(|_app| Ok(()))
        .run(tauri::generate_context!())
        .expect("DevPilot 客户端启动失败");
}

#[cfg(test)]
mod tests {
    /// 冒烟测试：8 个 crate 全部可被主程序互引（P01 Step 2 验收，FR-029 底座）。
    #[test]
    fn workspace_crates_linkable() {
        let names = [
            core_state::CRATE_NAME,
            core_orchestrator::CRATE_NAME,
            core_sandbox::CRATE_NAME,
            core_exec::CRATE_NAME,
            core_meter::CRATE_NAME,
            core_skills::CRATE_NAME,
            core_mcp::CRATE_NAME,
            core_cli::CRATE_NAME,
        ];
        assert_eq!(names.len(), 8);
        assert!(names.iter().all(|n| n.starts_with("core-")));
    }
}
