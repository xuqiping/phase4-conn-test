//! 内核事件流：状态变更后推快照给前端（前端断连重连 = 重拉 get_state 快照，plan 联动点 4）。

use tauri::{AppHandle, Emitter};

use crate::commands::StateDto;

pub const EVT_STATE: &str = "kernel://state";

pub fn emit_state(app: &AppHandle, dto: &StateDto) {
    // 推送失败（如窗口已关）不影响内核状态，静默忽略
    let _ = app.emit(EVT_STATE, dto);
}
