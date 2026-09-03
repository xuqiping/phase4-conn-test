use serde::Serialize;
use tauri::{
    menu::{MenuBuilder, MenuItemBuilder},
    AppHandle, Manager, PhysicalPosition, WebviewUrl, WebviewWindowBuilder,
};

const FLOATING_BALL_LABEL: &str = "floating-ball";
const FLOATING_BALL_SIZE: u32 = 64;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ScreenRect {
    pub x: i32,
    pub y: i32,
    pub width: u32,
    pub height: u32,
}

#[derive(Clone, Copy, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FloatingBallPosition {
    pub x: i32,
    pub y: i32,
}

pub fn clamp_floating_ball_position(
    x: i32,
    y: i32,
    ball_size: u32,
    monitors: &[ScreenRect],
) -> (i32, i32) {
    monitors
        .iter()
        .map(|monitor| {
            let max_x = monitor
                .x
                .saturating_add(monitor.width.saturating_sub(ball_size) as i32);
            let max_y = monitor
                .y
                .saturating_add(monitor.height.saturating_sub(ball_size) as i32);
            let clamped_x = x.clamp(monitor.x, max_x.max(monitor.x));
            let clamped_y = y.clamp(monitor.y, max_y.max(monitor.y));
            let delta_x = i64::from(x) - i64::from(clamped_x);
            let delta_y = i64::from(y) - i64::from(clamped_y);
            let distance = delta_x.saturating_mul(delta_x)
                .saturating_add(delta_y.saturating_mul(delta_y));
            (distance, clamped_x, clamped_y)
        })
        .min_by_key(|candidate| candidate.0)
        .map(|(_, clamped_x, clamped_y)| (clamped_x, clamped_y))
        .unwrap_or((0, 0))
}

fn visible_position(app: &AppHandle, requested: Option<(i32, i32)>) -> FloatingBallPosition {
    let monitors = app
        .available_monitors()
        .unwrap_or_default()
        .into_iter()
        .map(|monitor| {
            let area = monitor.work_area();
            ScreenRect {
                x: area.position.x,
                y: area.position.y,
                width: area.size.width,
                height: area.size.height,
            }
        })
        .collect::<Vec<_>>();
    let (requested_x, requested_y) = requested.unwrap_or_else(|| {
        monitors
            .first()
            .map(|monitor| {
                (
                    monitor.x + monitor.width.saturating_sub(FLOATING_BALL_SIZE + 24) as i32,
                    monitor.y + monitor.height.saturating_sub(FLOATING_BALL_SIZE + 80) as i32,
                )
            })
            .unwrap_or((0, 0))
    });
    let (x, y) = clamp_floating_ball_position(
        requested_x,
        requested_y,
        FLOATING_BALL_SIZE,
        &monitors,
    );
    FloatingBallPosition { x, y }
}

#[tauri::command]
pub async fn show_floating_ball(
    app: AppHandle,
    x: Option<i32>,
    y: Option<i32>,
) -> Result<FloatingBallPosition, String> {
    let position = visible_position(&app, x.zip(y));
    let window = if let Some(window) = app.get_webview_window(FLOATING_BALL_LABEL) {
        window
    } else {
        WebviewWindowBuilder::new(
            &app,
            FLOATING_BALL_LABEL,
            WebviewUrl::App("floating-ball.html".into()),
        )
        .title("File Keeper")
        .inner_size(FLOATING_BALL_SIZE as f64, FLOATING_BALL_SIZE as f64)
        .resizable(false)
        .decorations(false)
        .transparent(true)
        .always_on_top(true)
        .skip_taskbar(true)
        .shadow(false)
        .visible(false)
        .on_menu_event(|window, event| match event.id().as_ref() {
            "floating-ball-open" => {
                if let Some(main) = window.app_handle().get_webview_window("main") {
                    let _ = main.show();
                    let _ = main.set_focus();
                }
                let _ = window.hide();
            }
            "floating-ball-tray" => {
                let _ = window.hide();
            }
            "floating-ball-exit" => window.app_handle().exit(0),
            _ => {}
        })
        .build()
        .map_err(|error| format!("floating_ball_create_failed:{error}"))?
    };

    window
        .set_position(PhysicalPosition::new(position.x, position.y))
        .map_err(|error| format!("floating_ball_position_failed:{error}"))?;
    window
        .show()
        .map_err(|error| format!("floating_ball_show_failed:{error}"))?;
    Ok(position)
}

#[tauri::command]
pub async fn show_floating_ball_menu(
    app: AppHandle,
    open: String,
    tray: String,
    exit: String,
) -> Result<(), String> {
    let window = app
        .get_webview_window(FLOATING_BALL_LABEL)
        .ok_or_else(|| "floating_ball_unavailable".to_string())?;
    let open_item = MenuItemBuilder::with_id("floating-ball-open", open)
        .build(&app)
        .map_err(|error| format!("floating_ball_menu_failed:{error}"))?;
    let tray_item = MenuItemBuilder::with_id("floating-ball-tray", tray)
        .build(&app)
        .map_err(|error| format!("floating_ball_menu_failed:{error}"))?;
    let exit_item = MenuItemBuilder::with_id("floating-ball-exit", exit)
        .build(&app)
        .map_err(|error| format!("floating_ball_menu_failed:{error}"))?;
    let menu = MenuBuilder::new(&app)
        .item(&open_item)
        .item(&tray_item)
        .separator()
        .item(&exit_item)
        .build()
        .map_err(|error| format!("floating_ball_menu_failed:{error}"))?;
    window
        .popup_menu(&menu)
        .map_err(|error| format!("floating_ball_menu_failed:{error}"))
}

#[tauri::command]
pub async fn hide_floating_ball(app: AppHandle) -> Result<(), String> {
    if let Some(window) = app.get_webview_window(FLOATING_BALL_LABEL) {
        window
            .hide()
            .map_err(|error| format!("floating_ball_hide_failed:{error}"))?;
    }
    Ok(())
}

#[tauri::command]
pub async fn restore_main_window(app: AppHandle) -> Result<(), String> {
    let main = app
        .get_webview_window("main")
        .ok_or_else(|| "main_window_unavailable".to_string())?;
    main.show()
        .map_err(|error| format!("main_window_show_failed:{error}"))?;
    main.set_focus()
        .map_err(|error| format!("main_window_focus_failed:{error}"))?;
    hide_floating_ball(app).await
}

#[tauri::command]
pub async fn exit_application(app: AppHandle) {
    app.exit(0);
}

pub fn hide_floating_ball_if_present(app: &AppHandle) {
    if let Some(window) = app.get_webview_window(FLOATING_BALL_LABEL) {
        let _ = window.hide();
    }
}
