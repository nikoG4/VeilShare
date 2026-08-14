# iOS host

This folder is reserved for the minimal Xcode host in Phase 0. The shared `:shared:app` KMP module already declares `iosX64`, `iosArm64`, and `iosSimulatorArm64` targets; the host must create the `ComposeUIViewController` bridge once generated on macOS/Xcode.

Validation is intentionally `UNVERIFIED` on this Windows host. Do not add iOS business logic here: Keychain, picker, lifecycle, biometrics, and disguise adapters implement `core-platform` contracts only.
