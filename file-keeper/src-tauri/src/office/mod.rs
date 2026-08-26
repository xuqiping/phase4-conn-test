// Chunk 1 intentionally exposes only a domain layer. Tauri commands are added in Chunk 7.
#![cfg_attr(not(test), allow(dead_code))]

pub mod credentials;
pub mod db;
pub mod migrations;
pub mod output;
pub mod path_policy;
pub mod protocol;
pub mod recovery;
pub mod repository;
pub mod risk;
pub mod scanner;
pub mod state_machine;
pub mod types;
pub mod worker;

#[cfg(test)]
#[path = "tests/state_machine_tests.rs"]
mod state_machine_tests;
