use serde::{Serialize, Deserialize};
use std::sync::mpsc::Receiver;
use std::sync::{Arc, Mutex};

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct RecognitionResult {
    pub text: String,
    pub is_final: bool,
    pub partial: String,
}

#[derive(Debug, Clone)]
pub struct SpeechRecognizerConfig {
    pub model_dir: String,
    pub sample_rate: u32,
}

pub struct SpeechRecognizer {
    config: SpeechRecognizerConfig,
}

impl SpeechRecognizer {
    pub fn new(config: SpeechRecognizerConfig) -> Result<Self, Box<dyn std::error::Error>> {
        let required = ["encoder-epoch-99-avg-1.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.onnx", "tokens.txt"];
        for file in &required {
            let path = std::path::Path::new(&config.model_dir).join(file);
            if !path.exists() {
                return Err(format!("model file not found: {}", path.display()).into());
            }
        }
        Ok(Self { config })
    }

    pub fn process_stream(
        &self,
        receiver: Arc<Mutex<Receiver<Vec<f32>>>>,
        mut send_result: impl FnMut(RecognitionResult) + Send + 'static,
    ) {
        let model_dir = self.config.model_dir.clone();
        let sample_rate = self.config.sample_rate;

        std::thread::spawn(move || {
            let mut recognizer = match create_offline_recognizer(&model_dir, sample_rate) {
                Ok(r) => {
                    log::info!("Offline recognizer loaded successfully");
                    Some(r)
                }
                Err(e) => {
                    log::error!("Failed to load recognizer: {}. Falling back to mock.", e);
                    None
                }
            };

            const CHUNK_SECONDS: usize = 2;
            let chunk_samples = (sample_rate as usize) * CHUNK_SECONDS;
            let mut buffer: Vec<f32> = Vec::with_capacity(chunk_samples * 2);
            let mut partial_count = 0;

            loop {
                let Ok(frame) = receiver.lock().unwrap().recv() else { break };
                buffer.extend_from_slice(&frame);

                partial_count += frame.len();
                if partial_count >= (sample_rate as usize) / 2 {
                    partial_count = 0;
                    send_result(RecognitionResult {
                        text: String::new(),
                        is_final: false,
                        partial: "正在识别…".to_string(),
                    });
                }

                if buffer.len() >= chunk_samples {
                    if let Some(ref mut rec) = recognizer {
                        let text = rec.transcribe(sample_rate, &buffer);
                        if !text.is_empty() {
                            send_result(RecognitionResult {
                                text: text.trim().to_string(),
                                is_final: true,
                                partial: String::new(),
                            });
                        }
                    } else {
                        send_result(RecognitionResult {
                            text: "[mock] 你好".to_string(),
                            is_final: true,
                            partial: String::new(),
                        });
                    }
                    buffer.clear();
                }
            }

            if !buffer.is_empty() {
                if let Some(ref mut rec) = recognizer {
                    let text = rec.transcribe(sample_rate, &buffer);
                    if !text.is_empty() {
                        send_result(RecognitionResult {
                            text: text.trim().to_string(),
                            is_final: true,
                            partial: String::new(),
                        });
                    }
                }
            }
        });
    }
}

struct OfflineRecognizer {
    recognizer: *const sherpa_rs_sys::SherpaOnnxOfflineRecognizer,
}

impl OfflineRecognizer {
    fn transcribe(&mut self, sample_rate: u32, samples: &[f32]) -> String {
        unsafe {
            let stream = sherpa_rs_sys::SherpaOnnxCreateOfflineStream(self.recognizer);
            if stream.is_null() {
                return String::new();
            }
            sherpa_rs_sys::SherpaOnnxAcceptWaveformOffline(
                stream,
                sample_rate as i32,
                samples.as_ptr(),
                samples.len() as i32,
            );
            sherpa_rs_sys::SherpaOnnxDecodeOfflineStream(self.recognizer, stream);
            let result_ptr = sherpa_rs_sys::SherpaOnnxGetOfflineStreamResult(stream);
            let text = if result_ptr.is_null() {
                String::new()
            } else {
                let raw_result = result_ptr.read();
                cstr_to_string(raw_result.text)
            };

            if !result_ptr.is_null() {
                sherpa_rs_sys::SherpaOnnxDestroyOfflineRecognizerResult(result_ptr);
            }
            sherpa_rs_sys::SherpaOnnxDestroyOfflineStream(stream);
            text
        }
    }
}

unsafe impl Send for OfflineRecognizer {}
unsafe impl Sync for OfflineRecognizer {}

impl Drop for OfflineRecognizer {
    fn drop(&mut self) {
        unsafe {
            sherpa_rs_sys::SherpaOnnxDestroyOfflineRecognizer(self.recognizer);
        }
    }
}

fn create_offline_recognizer(model_dir: &str, sample_rate: u32) -> Result<OfflineRecognizer, Box<dyn std::error::Error>> {
    use std::ffi::CString;

    let encoder = CString::new(format!("{}/encoder-epoch-99-avg-1.onnx", model_dir))?;
    let decoder = CString::new(format!("{}/decoder-epoch-99-avg-1.onnx", model_dir))?;
    let joiner = CString::new(format!("{}/joiner-epoch-99-avg-1.onnx", model_dir))?;
    let tokens = CString::new(format!("{}/tokens.txt", model_dir))?;
    let provider = CString::new("cpu")?;
    let decoding_method = CString::new("greedy_search")?;

    unsafe {
        let transducer_config = sherpa_rs_sys::SherpaOnnxOfflineTransducerModelConfig {
            encoder: encoder.as_ptr(),
            decoder: decoder.as_ptr(),
            joiner: joiner.as_ptr(),
        };

        let model_config = sherpa_rs_sys::SherpaOnnxOfflineModelConfig {
            transducer: transducer_config,
            paraformer: std::mem::zeroed(),
            nemo_ctc: std::mem::zeroed(),
            whisper: std::mem::zeroed(),
            tdnn: std::mem::zeroed(),
            tokens: tokens.as_ptr(),
            num_threads: 4,
            debug: 0,
            provider: provider.as_ptr(),
            model_type: std::ptr::null(),
            modeling_unit: std::ptr::null(),
            bpe_vocab: std::ptr::null(),
            telespeech_ctc: std::ptr::null(),
            sense_voice: std::mem::zeroed(),
            moonshine: std::mem::zeroed(),
            fire_red_asr: std::mem::zeroed(),
            dolphin: std::mem::zeroed(),
            zipformer_ctc: std::mem::zeroed(),
            canary: std::mem::zeroed(),
        };

        let recognizer_config = sherpa_rs_sys::SherpaOnnxOfflineRecognizerConfig {
            feat_config: sherpa_rs_sys::SherpaOnnxFeatureConfig {
                sample_rate: sample_rate as i32,
                feature_dim: 80,
            },
            model_config,
            lm_config: std::mem::zeroed(),
            decoding_method: decoding_method.as_ptr(),
            max_active_paths: 4,
            hotwords_file: std::ptr::null(),
            hotwords_score: 0.0,
            rule_fsts: std::ptr::null(),
            rule_fars: std::ptr::null(),
            blank_penalty: 0.0,
            hr: std::mem::zeroed(),
        };

        let recognizer = sherpa_rs_sys::SherpaOnnxCreateOfflineRecognizer(&recognizer_config);
        if recognizer.is_null() {
            return Err("Failed to create offline recognizer".into());
        }
        Ok(OfflineRecognizer { recognizer })
    }
}

unsafe fn cstr_to_string(ptr: *const std::os::raw::c_char) -> String {
    if ptr.is_null() {
        String::new()
    } else {
        std::ffi::CStr::from_ptr(ptr).to_string_lossy().into_owned()
    }
}
