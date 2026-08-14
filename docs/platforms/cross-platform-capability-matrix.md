# Matriz de capacidades por plataforma

| Capacidad | commonMain | Android | iOS | Desktop |
|---|---:|---|---|---|
| Model/domain | ✅ | — | — | — |
| Compose UI | ✅ | host | host | host |
| Adaptive layout | ✅ | AdaptiveKt | AdaptiveKt | AdaptiveKt |
| Vault format | ✅ | — | — | — |
| Transfer protocol | ✅ | — | — | — |
| Contacts | ✅ | — | — | — |
| Crypto orchestration | ✅ | provider | provider | provider |
| Argon2id API | ✅ | actual/provider | actual/provider | actual/provider |
| Secure random | prefer common provider | native/provider | native/provider | native/provider |
| Secret store | contract | Keystore | Keychain/SE | per-OS |
| File picker | contract | Photo Picker/SAF | Photos/Files | chooser |
| Original delete | policy | URI/media APIs | Photos/provider | filesystem |
| Biometrics | policy | BiometricPrompt | LocalAuth | optional |
| App icon/disguise | model | activity alias | alt icon | shortcut/icon |
| Dynamic app label | model | possible launcher alias | no diseño dependiente | packaging |
| Screenshot privacy | policy | strong API | best effort/detection | best effort |
| P2P transport | contract | WebRTC adapter | WebRTC adapter | adapter TBD |
| Background P2P | state machine | more feasible | constrained | feasible |
| Drag/drop | common capability | optional | optional | strong |
| Notifications | content policy common | native | native | native |

La tabla no significa que commonMain “implemente” APIs del SO; significa que commonMain define la intención y el flujo.
