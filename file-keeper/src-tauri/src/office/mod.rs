// Chunk 1 intentionally exposes only a domain layer. Tauri commands are added in Chunk 7.
#![cfg_attr(not(test), allow(dead_code))]

pub mod state_machine;
pub mod types;

#[cfg(test)]
#[path = "tests/state_machine_tests.rs"]
mod state_machine_tests;
