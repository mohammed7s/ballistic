//! `uniffi-bindgen`: generates the shells' bindings from a built libballistic_core
//! (library mode). Commands in core/README.md §15 and apps/assistant/scripts/build_core.sh.

fn main() {
    uniffi::uniffi_bindgen_main()
}
