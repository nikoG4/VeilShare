# Blueprint 12 — Lifecycle coordinator

## Events common

```kotlin
sealed interface AppLifecycleEvent {
    data object Foreground
    data object Background
    data object ScreenLocked
    data object MemoryPressure
    data object Terminating
}
```

Not all platforms emit all events.

## Coordinator

Policy:
- background timestamp;
- auto-lock timeout;
- immediate lock option;
- pause transfers;
- cover UI.

## Android

Map ProcessLifecycle/Activity/window events as appropriate.

## iOS

Map scene/application lifecycle.
Assume suspension can occur with little execution time.

## Desktop

Window focus loss is not same as OS lock.
Support timeout and manual lock; OS lock integration optional by platform.

## Acceptance

Simulate rapid foreground/background, rotation, multi-window where applicable.
