# Local UI baseline checkpoint

Date: 2026-08-14

- Common presentation state machine and neutral dual-credential unlock: PASS.
- Production Desktop composition, picker, browser, import/open/delete/change-code: PASS by compile plus persistent facade E2E.
- Installable Android host, SAF streaming, FileProvider open, `FLAG_SECURE`, lifecycle lock: PASS by build and API 35 instrumented smoke.
- Authenticated-catalog refresh across UI transaction boundaries: PASS.
- Frozen core regression: PASS on Desktop and Android API 35.
- Networking, signaling feature work, iOS: intentionally untouched.

See `docs/ui/LOCAL_UI_BASELINE.md` for architecture and limitations.
