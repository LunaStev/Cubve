## Highlights

- Show `???` for the multiplayer server-list player count and omit player names and UUIDs from modern status responses.
- Hide real counts in legacy pings and keep server-list privacy enforced after plugin ping listeners.
- Preserve the actual `max-players` connection limit, operator diagnostics, MOTD customizations and ping cancellation.
- Verify status packet serialization with regression tests and check modern/legacy responses against a running server.
