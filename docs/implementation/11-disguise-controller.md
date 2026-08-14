# Blueprint 11 — DisguiseController

## Common model

```kotlin
data class DisguiseProfile(
    val id: String,
    val label: String,
    val surface: DisguiseSurface,
    val iconKey: String?
)
```

`label` describes local profile; platform decides what launcher label can actually change.

## Android actual

- predefined activity aliases in manifest;
- exactly one launcher alias active;
- switch carefully to avoid “no launcher” window;
- handle OEM caching.

## iOS actual

- map profile to alternate icon if supported;
- bundle display name remains design-time neutral;
- surface changes in shared UI.

## Desktop actual

- surface always shared;
- shortcut/icon change may require installer/OS integration;
- return `PartiallyApplied` rather than lie.

## Acceptance

- unsupported profile filtered;
- switching does not lock user out;
- review build path documented.
