#[path = "../src/commands/window.rs"]
mod window;

use window::{clamp_floating_ball_position, ScreenRect};

#[test]
fn keeps_a_position_inside_a_negative_coordinate_monitor() {
    let monitors = [
        ScreenRect { x: -1920, y: 0, width: 1920, height: 1080 },
        ScreenRect { x: 0, y: 0, width: 2560, height: 1440 },
    ];

    assert_eq!(
        clamp_floating_ball_position(-1900, 100, 64, &monitors),
        (-1900, 100)
    );
}

#[test]
fn clamps_an_offscreen_position_to_a_visible_monitor() {
    let monitors = [ScreenRect { x: 0, y: 0, width: 1920, height: 1080 }];

    assert_eq!(
        clamp_floating_ball_position(4000, -500, 64, &monitors),
        (1856, 0)
    );
}

#[test]
fn uses_a_safe_origin_when_monitor_information_is_unavailable() {
    assert_eq!(clamp_floating_ball_position(100, 200, 64, &[]), (0, 0));
}

#[test]
fn safely_clamps_extreme_persisted_coordinates() {
    let monitors = [ScreenRect { x: 100, y: 0, width: 1920, height: 1080 }];

    assert_eq!(
        clamp_floating_ball_position(i32::MIN, i32::MAX, 64, &monitors),
        (100, 1016)
    );
}
