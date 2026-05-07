#![cfg(windows)]

use std::ptr;
use std::sync::mpsc::{self, Sender};
use std::thread;
use std::time::Duration;

use windows::Win32::Devices::Properties;
use windows::Win32::Foundation::PROPERTYKEY;
use windows::Win32::Media::Audio as Audio;
use windows::Win32::System::Com;
use windows::Win32::System::Com::{StructuredStorage, STGM_READ};
use windows::Win32::System::Variant::VT_LPWSTR;
use windows::Win32::UI::Shell::PropertiesSystem::IPropertyStore;

pub type AudioFrame = Vec<f32>;

pub fn list_output_devices() -> Vec<String> {
    unsafe {
        let _ = Com::CoInitializeEx(None, Com::COINIT_MULTITHREADED);

        let enumerator: Audio::IMMDeviceEnumerator = match Com::CoCreateInstance(
            &Audio::MMDeviceEnumerator,
            None,
            Com::CLSCTX_ALL,
        ) {
            Ok(e) => e,
            Err(_) => return Vec::new(),
        };

        let collection = match
            enumerator.EnumAudioEndpoints(Audio::eRender, Audio::DEVICE_STATE_ACTIVE)
        {
            Ok(c) => c,
            Err(_) => return Vec::new(),
        };

        let count = match collection.GetCount() {
            Ok(c) => c,
            Err(_) => return Vec::new(),
        };

        let mut devices = Vec::new();
        for i in 0..count {
            if let Ok(device) = collection.Item(i) {
                if let Ok(props) = device.OpenPropertyStore(STGM_READ) {
                    if let Some(name) = get_device_name(&props) {
                        devices.push(format!("[系统音频] {}", name));
                    }
                }
            }
        }
        devices
    }
}

unsafe fn get_device_name(props: &IPropertyStore) -> Option<String> {
    let key = &Properties::DEVPKEY_Device_FriendlyName as *const _ as *const PROPERTYKEY;
    let mut value = props.GetValue(key).ok()?;
    let variant = &value.Anonymous.Anonymous;

    if variant.vt.0 != VT_LPWSTR.0 {
        return None;
    }

    let ptr = *(&variant.Anonymous as *const _ as *const *const u16);
    if ptr.is_null() {
        return None;
    }

    const MAX_LEN: usize = 32768;
    let mut len = 0;
    while len < MAX_LEN && *ptr.add(len) != 0 {
        len += 1;
    }
    if len >= MAX_LEN {
        return None;
    }

    let slice = std::slice::from_raw_parts(ptr, len);
    let result = String::from_utf16_lossy(slice);

    StructuredStorage::PropVariantClear(&mut value).ok();
    Some(result)
}

pub struct LoopbackCapture {
    thread: Option<thread::JoinHandle<()>>,
    stop_tx: mpsc::Sender<()>,
}

impl LoopbackCapture {
    pub fn start(
        device_index: Option<usize>,
        sender: Sender<AudioFrame>,
    ) -> Result<Self, Box<dyn std::error::Error>> {
        unsafe {
            let _ = Com::CoInitializeEx(None, Com::COINIT_MULTITHREADED);

            let enumerator: Audio::IMMDeviceEnumerator = Com::CoCreateInstance(
                &Audio::MMDeviceEnumerator,
                None,
                Com::CLSCTX_ALL,
            )?;

            let device = if let Some(idx) = device_index {
                let collection = enumerator.EnumAudioEndpoints(
                    Audio::eRender,
                    Audio::DEVICE_STATE_ACTIVE,
                )?;
                collection.Item(idx as _)?
            } else {
                enumerator.GetDefaultAudioEndpoint(Audio::eRender, Audio::eConsole)?
            };

            let client: Audio::IAudioClient = device.Activate(Com::CLSCTX_ALL, None)?;

            let format_ptr = client.GetMixFormat()?;

            client.Initialize(
                Audio::AUDCLNT_SHAREMODE_SHARED,
                Audio::AUDCLNT_STREAMFLAGS_LOOPBACK,
                10000000, // 100ms buffer in 100ns units
                0,
                &*format_ptr,
                None,
            )?;

            let capture_client: Audio::IAudioCaptureClient = client.GetService()?;
            client.Start()?;

            let format = &*format_ptr;
            let channels = format.nChannels;
            let bits_per_sample = format.wBitsPerSample;

            let (stop_tx, stop_rx) = mpsc::channel();

            // Convert COM interfaces to usize for thread safety.
            // SAFETY: We use mem::forget on the original interfaces to avoid double-release,
            // and reconstruct them from raw pointers inside the thread.
            let client_addr: usize = std::mem::transmute_copy(&client);
            let capture_addr: usize = std::mem::transmute_copy(&capture_client);
            std::mem::forget(client);
            std::mem::forget(capture_client);

            // Cast pointer to usize so it can be sent across threads
            let format_addr = format_ptr as usize;

            let thread = thread::spawn(move || {
                // SAFETY: usize values came from valid COM interfaces above.
                let client: Audio::IAudioClient = std::mem::transmute(client_addr);
                let capture_client: Audio::IAudioCaptureClient = std::mem::transmute(capture_addr);

                loop {
                    if stop_rx.try_recv().is_ok() {
                        break;
                    }

                    let packet_size = capture_client.GetNextPacketSize().unwrap_or(0);
                    if packet_size > 0 {
                        let mut data = ptr::null_mut();
                        let mut frames = 0;
                        let mut flags = 0;

                        if capture_client
                            .GetBuffer(&mut data, &mut frames, &mut flags, None, None)
                            .is_ok()
                        {
                            let total_samples = frames as usize * channels as usize;
                            let frame = if bits_per_sample == 32 {
                                let samples = std::slice::from_raw_parts(
                                    data as *const f32,
                                    total_samples,
                                );
                                samples.to_vec()
                            } else if bits_per_sample == 16 {
                                let samples = std::slice::from_raw_parts(
                                    data as *const i16,
                                    total_samples,
                                );
                                samples
                                    .iter()
                                    .map(|s| *s as f32 / i16::MAX as f32)
                                    .collect()
                            } else {
                                Vec::new()
                            };

                            let _ = sender.send(frame);
                            capture_client.ReleaseBuffer(frames).ok();
                        }
                    }

                    thread::sleep(Duration::from_millis(1));
                }

                client.Stop().ok();
                Com::CoTaskMemFree(Some(format_addr as *mut _));
            });

            Ok(Self {
                thread: Some(thread),
                stop_tx,
            })
        }
    }

    pub fn stop(&mut self) {
        let _ = self.stop_tx.send(());
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

impl Drop for LoopbackCapture {
    fn drop(&mut self) {
        self.stop();
    }
}
