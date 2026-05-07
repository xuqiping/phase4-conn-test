pub mod capture;
#[cfg(windows)]
pub mod loopback;

pub use capture::{AudioCapture, AudioCaptureConfig};
#[cfg(windows)]
pub use loopback::{list_output_devices, LoopbackCapture};
