use serde::{Serialize, Deserialize};
use std::sync::mpsc::Receiver;
use std::sync::{Arc, Mutex};

use crate::audio::capture::AudioFrame;

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct RecognitionResult {
    pub text: String,
    pub is_final: bool,
    pub partial: String,
    /// ms relative to audio-stream start (≈ session t0 once Step 4 injects the
    /// shared session clock). 0 for mock/no-clock path. AC-102: err < 300ms.
    pub start_ms: i64,
    pub end_ms: i64,
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
        receiver: Arc<Mutex<Receiver<AudioFrame>>>,
        mut send_result: impl FnMut(RecognitionResult) + Send + 'static,
    ) {
        let model_dir = self.config.model_dir.clone();
        let target_sample_rate = self.config.sample_rate;

        std::thread::spawn(move || {
            let recognizer = match create_online_recognizer(&model_dir, target_sample_rate) {
                Ok(r) => {
                    log::info!("Online recognizer loaded successfully");
                    Some(r)
                }
                Err(e) => {
                    log::error!("Failed to load recognizer: {}. Falling back to mock.", e);
                    None
                }
            };

            let mut stream: Option<*const sherpa_rs_sys::SherpaOnnxOnlineStream> = None;
            if let Some(ref rec) = recognizer {
                unsafe {
                    let s = sherpa_rs_sys::SherpaOnnxCreateOnlineStream(rec.recognizer);
                    if !s.is_null() {
                        stream = Some(s);
                    }
                }
            }

            let mut last_text = String::new();
            let mut accumulated_final_text = String::new();

            // ---- Sentence-level timestamp bookkeeping (AC-102) ----
            // Count mono samples fed to the recognizer; convert to ms via the
            // target sample rate. start_ms/end_ms are relative to the audio
            // stream's start, which equals session t0 once Step 4 wires the
            // shared clock into recording start.
            let mut samples_processed: i64 = 0;
            let mut sentence_start_sample: i64 = 0;
            let to_ms = |samples: i64| -> i64 {
                samples * 1000 / target_sample_rate as i64
            };

            loop {
                let Ok(frame) = receiver.lock().unwrap().recv() else { break };
                let samples = resample_to_mono_16khz(&frame.samples, frame.sample_rate, frame.channels);
                if samples.is_empty() {
                    continue;
                }

                if let (Some(ref rec), Some(s)) = (&recognizer, stream) {
                    unsafe {
                        sherpa_rs_sys::SherpaOnnxOnlineStreamAcceptWaveform(
                            s,
                            target_sample_rate as i32,
                            samples.as_ptr(),
                            samples.len() as i32,
                        );
                        samples_processed += samples.len() as i64;

                        // Decode while ready
                        while sherpa_rs_sys::SherpaOnnxIsOnlineStreamReady(rec.recognizer, s) == 1 {
                            sherpa_rs_sys::SherpaOnnxDecodeOnlineStream(rec.recognizer, s);
                        }

                        // Get result
                        let result_ptr = sherpa_rs_sys::SherpaOnnxGetOnlineStreamResult(rec.recognizer, s);
                        let current_text = if result_ptr.is_null() {
                            String::new()
                        } else {
                            let raw = result_ptr.read();
                            let text = cstr_to_string(raw.text);
                            sherpa_rs_sys::SherpaOnnxDestroyOnlineRecognizerResult(result_ptr);
                            text
                        };

                        // Send partial update if text changed
                        if current_text != last_text {
                            last_text = current_text.clone();
                            send_result(RecognitionResult {
                                text: String::new(),
                                is_final: false,
                                partial: current_text.clone(),
                                start_ms: to_ms(sentence_start_sample),
                                end_ms: to_ms(samples_processed),
                            });
                        }

                        // Check endpoint
                        if sherpa_rs_sys::SherpaOnnxOnlineStreamIsEndpoint(rec.recognizer, s) == 1 {
                            if !current_text.is_empty() {
                                accumulated_final_text.push_str(&current_text);
                                send_result(RecognitionResult {
                                    text: current_text,
                                    is_final: true,
                                    partial: String::new(),
                                    start_ms: to_ms(sentence_start_sample),
                                    end_ms: to_ms(samples_processed),
                                });
                            }
                            // Reset stream for next utterance
                            sherpa_rs_sys::SherpaOnnxOnlineStreamReset(rec.recognizer, s);
                            last_text.clear();
                            // Next sentence begins at the current sample position.
                            sentence_start_sample = samples_processed;
                        }
                    }
                } else {
                    // Mock fallback
                    send_result(RecognitionResult {
                        text: "[mock] 你好".to_string(),
                        is_final: true,
                        partial: String::new(),
                        start_ms: 0,
                        end_ms: 0,
                    });
                }
            }

            // Cleanup
            if let Some(s) = stream {
                unsafe {
                    sherpa_rs_sys::SherpaOnnxDestroyOnlineStream(s);
                    // Note: recognizer is dropped automatically
                }
            }
        });
    }
}

struct OnlineRecognizer {
    recognizer: *const sherpa_rs_sys::SherpaOnnxOnlineRecognizer,
}

unsafe impl Send for OnlineRecognizer {}
unsafe impl Sync for OnlineRecognizer {}

impl Drop for OnlineRecognizer {
    fn drop(&mut self) {
        unsafe {
            sherpa_rs_sys::SherpaOnnxDestroyOnlineRecognizer(self.recognizer);
        }
    }
}

fn create_online_recognizer(model_dir: &str, sample_rate: u32) -> Result<OnlineRecognizer, Box<dyn std::error::Error>> {
    use std::ffi::CString;

    let encoder = CString::new(format!("{}/encoder-epoch-99-avg-1.onnx", model_dir))?;
    let decoder = CString::new(format!("{}/decoder-epoch-99-avg-1.onnx", model_dir))?;
    let joiner = CString::new(format!("{}/joiner-epoch-99-avg-1.onnx", model_dir))?;
    let tokens = CString::new(format!("{}/tokens.txt", model_dir))?;
    let provider = CString::new("cpu")?;
    let decoding_method = CString::new("greedy_search")?;

    unsafe {
        let transducer_config = sherpa_rs_sys::SherpaOnnxOnlineTransducerModelConfig {
            encoder: encoder.as_ptr(),
            decoder: decoder.as_ptr(),
            joiner: joiner.as_ptr(),
        };

        let model_config = sherpa_rs_sys::SherpaOnnxOnlineModelConfig {
            transducer: transducer_config,
            paraformer: std::mem::zeroed(),
            zipformer2_ctc: std::mem::zeroed(),
            tokens: tokens.as_ptr(),
            num_threads: 4,
            provider: provider.as_ptr(),
            debug: 0,
            model_type: std::ptr::null(),
            modeling_unit: std::ptr::null(),
            bpe_vocab: std::ptr::null(),
            tokens_buf: std::ptr::null(),
            tokens_buf_size: 0,
            nemo_ctc: std::mem::zeroed(),
        };

        let recognizer_config = sherpa_rs_sys::SherpaOnnxOnlineRecognizerConfig {
            feat_config: sherpa_rs_sys::SherpaOnnxFeatureConfig {
                sample_rate: sample_rate as i32,
                feature_dim: 80,
            },
            model_config,
            decoding_method: decoding_method.as_ptr(),
            max_active_paths: 4,
            enable_endpoint: 1,
            rule1_min_trailing_silence: 2.4,
            rule2_min_trailing_silence: 1.2,
            rule3_min_utterance_length: 20.0,
            hotwords_file: std::ptr::null(),
            hotwords_score: 0.0,
            ctc_fst_decoder_config: std::mem::zeroed(),
            rule_fsts: std::ptr::null(),
            rule_fars: std::ptr::null(),
            blank_penalty: 0.0,
            hotwords_buf: std::ptr::null(),
            hotwords_buf_size: 0,
            hr: std::mem::zeroed(),
        };

        let recognizer = sherpa_rs_sys::SherpaOnnxCreateOnlineRecognizer(&recognizer_config);
        if recognizer.is_null() {
            return Err("Failed to create online recognizer".into());
        }
        Ok(OnlineRecognizer { recognizer })
    }
}

pub(crate) fn resample_to_mono_16khz(input: &[f32], input_rate: u32, channels: u16) -> Vec<f32> {
    if channels == 0 || input.is_empty() {
        return Vec::new();
    }

    // Downmix to mono
    let mono: Vec<f32> = if channels == 1 {
        input.to_vec()
    } else {
        let ch = channels as usize;
        input.chunks_exact(ch)
            .map(|chunk| chunk.iter().sum::<f32>() / ch as f32)
            .collect()
    };

    if input_rate == 16000 {
        return mono;
    }

    // Linear interpolation resampling to 16kHz
    let ratio = 16000.0 / input_rate as f64;
    let output_len = (mono.len() as f64 * ratio) as usize;
    if output_len == 0 {
        return Vec::new();
    }

    let mut output = Vec::with_capacity(output_len);
    for i in 0..output_len {
        let src_idx = i as f64 / ratio;
        let src_floor = src_idx.floor() as usize;
        let src_ceil = (src_floor + 1).min(mono.len() - 1);
        let frac = src_idx - src_floor as f64;
        let sample = mono[src_floor] * (1.0 - frac as f32) + mono[src_ceil] * frac as f32;
        output.push(sample);
    }
    output
}

unsafe fn cstr_to_string(ptr: *const std::os::raw::c_char) -> String {
    if ptr.is_null() {
        String::new()
    } else {
        std::ffi::CStr::from_ptr(ptr).to_string_lossy().into_owned()
    }
}
