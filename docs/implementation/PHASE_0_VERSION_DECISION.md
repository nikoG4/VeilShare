# Phase 0 dependency decision — 2026-08-09

- Kotlin `2.1.21` and Compose Multiplatform `1.8.2` are used for the foundation. Although newer releases exist, this pair is the compatibility pair declared by the required AdaptiveKt alpha release; the wrapper isolates the later migration.
- AdaptiveKt `0.1.0-alpha01` is used only by `:shared:ui-design`; its upstream README confirms Maven Central coordinates and alpha status.
- No crypto dependency is selected. ADR-006 and P1 remain pending.
- Android Gradle Plugin `8.7.3` is compatible with local Gradle 8.13. No Android SDK is installed locally.
