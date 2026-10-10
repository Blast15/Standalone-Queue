# Security Model

StandaloneQueue uses a bounded queue, in-flight login tracking, UUID rate limiting, IP admission windows, per-IP queue quotas, and a hard cap on tracked IP states.

Normal admissions obey `online + in-flight <= effective max players`. `queue.bypass` intentionally permits trusted players to exceed that configured capacity. Active leases reserve available capacity. Normal grants subtract online players, in-flight logins and unconsumed leases. A lease is exchanged for one in-flight slot at admission; capacity shrink revokes the newest leases first on the next pre-login/cleanup/quit transition. A slow handshake retains its in-flight slot until login failure/cancellation/close; timeout alone cannot create a free slot. Accepted login retains capacity through delayed join. Real Paper transport and login-plugin compatibility still require the phase 2 staging matrix.

On offline/crack servers, usernames and offline UUIDs are not strong identities. Bots can rotate names, so IP and network controls remain important. The plugin cannot authenticate cracked account ownership or absorb a volumetric DDoS by itself.

Pre-login rights require online-mode authenticated UUIDs. Optional LuckPerms resolves every attempt without a local rights cache. Other servers use a main-thread OP snapshot with a strict 2-second maximum age. No username lookup grants authority. Offline mode falls back to ordinary queue rights; see [configuration](Configuration.md) for supported providers and contexts.
