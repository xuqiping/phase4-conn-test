use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{Device, Stream};
use std::sync::mpsc::{self, Receiver};
use std::sync::{Arc, Mutex};

#[derive(Debug, Clone)]
pub struct AudioFrame {
    pub samples: Vec<f32>,
    pub sample_rate: u32,
    pub channels: u16,
}

#[derive(Debug, Clone, Default)]
pub struct AudioCaptureConfig {
    pub sample_rate: u32,
    pub channels: u16,
}

enum CaptureBackend {
    Cpal(Device),
    #[cfg(windows)]
    Loopback(crate::audio::LoopbackCapture),
}

pub struct AudioCapture {
    config: AudioCaptureConfig,
    backend: Option<CaptureBackend>,
    stream: Option<Stream>,
    receiver: Arc<Mutex<Receiver<AudioFrame>>>,
    sender: mpsc::Sender<AudioFrame>,
    all_samples: Arc<Mutex<Vec<f32>>>,
}

impl AudioCapture {
    pub fn new(config: AudioCaptureConfig) -> Self {
        let (sender, receiver) = mpsc::channel();
        Self {
            config,
            backend: None,
            stream: None,
            receiver: Arc::new(Mutex::new(receiver)),
            sender,
            all_samples: Arc::new(Mutex::new(Vec::new())),
        }
    }

    pub fn take_samples(&self) -> Vec<f32> {
        std::mem::take(&mut self.all_samples.lock().unwrap())
    }

    pub fn list_devices() -> Vec<String> {
        let mut devices = Vec::new();

        // Input devices (microphones)
        let host = cpal::default_host();
        if let Ok(input_devs) = host.input_devices() {
            for d in input_devs {
                if let Ok(desc) = d.description() {
                    devices.push(desc.name().to_string());
                }
            }
        }

        // Output devices (system audio / loopback)
        #[cfg(windows)]
        {
            let output_devs = crate::audio::list_output_devices();
            devices.extend(output_devs);
        }

        devices
    }

    pub fn select_default_device(&mut self) -> Result<(), Box<dyn std::error::Error>> {
        let host = cpal::default_host();
        self.backend = Some(CaptureBackend::Cpal(
            host.default_input_device()
                .ok_or_else(|| "no default input device found".to_string())?,
        ));
        Ok(())
    }

    pub fn select_device_by_name(
        &mut self,
        name: &str,
    ) -> Result<(), Box<dyn std::error::Error>> {
        #[cfg(windows)]
        if name.starts_with("[系统音频] ") {
            let output_name = &name["[系统音频] ".len()..];
            let output_devices = crate::audio::list_output_devices();
            if let Some(idx) = output_devices.iter().position(|d| {
                d.strip_prefix("[系统音频] ").map_or(false, |n| n == output_name)
            }) {
                let (tx, rx) = mpsc::channel();
                let loopback = crate::audio::LoopbackCapture::start(Some(idx), tx)?;
                self.backend = Some(CaptureBackend::Loopback(loopback));

                // Forward loopback data to our main channel
                let sender = self.sender.clone();
                let all_samples = self.all_samples.clone();
                std::thread::spawn(move || {
                    while let Ok(frame) = rx.recv() {
                        all_samples.lock().unwrap().extend_from_slice(&frame.samples);
                        let _ = sender.send(frame);
                    }
                });
                return Ok(());
            }
        }

        let host = cpal::default_host();
        let device = host
            .input_devices()?
            .find(|d| {
                d.description()
                    .ok()
                    .map(|desc| desc.name() == name)
                    .unwrap_or(false)
            })
            .ok_or_else(|| format!("device not found: {}", name))?;
        self.backend = Some(CaptureBackend::Cpal(device));
        Ok(())
    }

    pub fn get_receiver(&self) -> Option<Arc<Mutex<Receiver<AudioFrame>>>> {
        Some(self.receiver.clone())
    }

    pub fn start(&mut self) -> Result<(), Box<dyn std::error::Error>> {
        match self.backend {
            Some(CaptureBackend::Cpal(ref device)) => {
                let config = device.default_input_config()?;
                let sender = self.sender.clone();
                let all_samples = self.all_samples.clone();
                let sample_rate = config.sample_rate();
                let channels = config.channels();

                let err_fn = move |err| {
                    log::error!("an error occurred on audio stream: {}", err);
                };

                let stream = match config.sample_format() {
                    cpal::SampleFormat::F32 => {
                        let s = sender.clone();
                        let samples = all_samples.clone();
                        device.build_input_stream(
                            &config.into(),
                            move |data: &[f32], _| {
                                let frame = AudioFrame {
                                    samples: data.iter().copied().collect(),
                                    sample_rate,
                                    channels,
                                };
                                samples.lock().unwrap().extend_from_slice(&frame.samples);
                                let _ = s.send(frame);
                            },
                            err_fn,
                            None,
                        )
                    }
                    cpal::SampleFormat::I16 => {
                        let s = sender.clone();
                        let samples = all_samples.clone();
                        device.build_input_stream(
                            &config.into(),
                            move |data: &[i16], _| {
                                let frame = AudioFrame {
                                    samples: data.iter().map(|&x| x as f32 / i16::MAX as f32).collect(),
                                    sample_rate,
                                    channels,
                                };
                                samples.lock().unwrap().extend_from_slice(&frame.samples);
                                let _ = s.send(frame);
                            },
                            err_fn,
                            None,
                        )
                    }
                    _ => {
                        return Err("unsupported sample format".into());
                    }
                }?;

                stream.play()?;
                self.stream = Some(stream);
                log::info!("Audio capture started (cpal) at {} Hz, {} ch", sample_rate, channels);
                Ok(())
            }
            #[cfg(windows)]
            Some(CaptureBackend::Loopback(_)) => {
                // Loopback already started in select_device_by_name
                log::info!("Audio capture started (loopback)");
                Ok(())
            }
            None => Err("no device selected".into()),
        }
    }

    pub fn stop(&mut self) {
        if let Some(stream) = self.stream.take() {
            drop(stream);
            log::info!("Audio capture stopped (cpal)");
        }
        if let Some(CaptureBackend::Loopback(mut cap)) = self.backend.take() {
            cap.stop();
            log::info!("Audio capture stopped (loopback)");
        }
    }
}

impl Drop for AudioCapture {
    fn drop(&mut self) {
        self.stop();
    }
}
