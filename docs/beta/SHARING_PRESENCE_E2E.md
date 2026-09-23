# Sharing presence E2E validation

This branch validates the Android sharing client against the production Railway signaling endpoint after the presence/session registry synchronization fix.

The production probe intentionally uses two independent WebSocket clients: one registers a routing code and the other performs the lookup. This mirrors the two-device/emulator flow that previously returned `NOT_FOUND` despite a confirmed receiver registration.

Server-side diagnostics log privacy-safe reference-code fingerprints for REGISTER, LOOKUP, UNREGISTER and connection cleanup so client and Railway traces can be correlated without logging the full routing code.
