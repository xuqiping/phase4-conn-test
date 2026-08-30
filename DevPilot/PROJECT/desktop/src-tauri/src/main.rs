// 桌面入口：仅转发到 lib（Tauri 2 约定，移动端复用同一入口）
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    devpilot_lib::run();
}
