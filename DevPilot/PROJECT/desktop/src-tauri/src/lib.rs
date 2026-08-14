//! DevPilot 客户端内核入口。
//! P01 Step 1：仅起窗。Step 2 起按 architecture §3 挂 workspace crates
//!（core-state / core-orchestrator / core-sandbox / core-exec / core-meter /
//!  core-skills / core-mcp / core-cli），commands/events 在 Step 7 接入。

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .setup(|_app| Ok(()))
        .run(tauri::generate_context!())
        .expect("DevPilot 客户端启动失败");
}
