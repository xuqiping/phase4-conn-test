
pub mod recognizer;
pub use recognizer::{SpeechRecognizer, SpeechRecognizerConfig};
/// Reused by the session stop path to normalize audio.wav to 16kHz mono
/// (same rate the recognizer timestamps against — keeps三路对齐).
pub(crate) use recognizer::resample_to_mono_16khz;
