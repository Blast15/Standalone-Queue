# Security Model

StandaloneQueue uses a bounded queue, in-flight login tracking, UUID rate limiting, IP admission windows, per-IP queue quotas, and a hard cap on tracked IP states.

Normal admissions obey `online + in-flight <= effective max players`. `queue.bypass` intentionally permits trusted players to exceed that configured capacity. Reservations are reconciled whenever capacity changes and do not themselves consume a physical slot until pre-login admission succeeds.

On offline/crack servers, usernames and offline UUIDs are not strong identities. Bots can rotate names, so IP and network controls remain important. The plugin cannot authenticate cracked account ownership or absorb a volumetric DDoS by itself.
