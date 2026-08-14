# Blueprint 01 — Bootstrap del proyecto

## Resultado esperado

Repositorio que compila una UI compartida en:
- Android;
- Desktop JVM;
- iOS framework/app host.

## Gradle

Crear:
- `settings.gradle.kts`;
- version catalog;
- convention plugins si ayudan;
- modules del ADR-001/003;
- Kotlin hierarchy explícita y fácil de leer.

## commonMain mínimo

Debe demostrar:
- `AppRoot`;
- navegación;
- `PlatformCapabilities` inyectadas;
- `VeilTheme`;
- `VeilAdaptiveScaffold`.

## Apps host

### Android
Solo:
- `Application`;
- `MainActivity`;
- wiring de platform capabilities;
- manifest/resources.

### iOS
Solo:
- bridge que produce `ComposeUIViewController`;
- implementations de capabilities;
- entitlements/Info.plist.

### Desktop
Solo:
- `main()`;
- window;
- platform capabilities;
- app dirs.

## Test

`commonTest`:
- puede construir `AppEnvironment` con fakes;
- no requiere Android Context ni Foundation.

## Acceptance

- Ningún `Platform.isAndroid` dentro de feature UI.
- Ningún import AdaptiveKt fuera de ui-design.
- `./gradlew build` no intenta resolver librerías iOS-only para JVM.
