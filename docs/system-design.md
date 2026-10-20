# System Design Principles (gave)

## 1. Microservices
Each service is an independently deployable app that owns one business area (user, group, expense...).
- **Why:** independent scaling, deploys, and fault isolation.
- **Cost:** network calls, distributed transactions, harder debugging. Avoid a "distributed monolith" (services that must change together).

## 2. Database per service
Each service owns its own database. No shared tables, no cross-service joins.
- Services share data only via APIs or events.
- Keeps services loosely coupled.

## 3. Split by business boundary
Divide services by business capability (users, expenses), not by technical layer (controllers, repositories).

## 4. API Gateway
Single entry point for clients. Handles routing, JWT validation, and cross-cutting concerns (rate limiting, CORS).

## 5. Identity vs. profile separation
`users` = who the person is. `user_identities` = ways to prove it (Google, GitHub...). One user, many identities.
- Never use email as the identity key. Use the provider's stable subject (`sub`).

## 6. Authentication flow (Google OAuth2 + our own JWT)
1. User logs in with Google (OIDC).
2. user-service verifies it, finds or creates the user, issues **our own JWT**.
3. Gateway validates our JWT on every request; other services trust it.
- Account linking is a security decision: auto-link only when the provider says `email_verified = true`.

## 7. Soft delete + anonymization
Financial records reference users, so hard delete would corrupt other people's data.
- Mark user `DELETED`, wipe personal data, keep the row.
- Block deletion while the user has unsettled balances (business rule).
- Satisfies right-to-erasure (GDPR-style) without breaking ledgers.

## 8. Invite-before-signup (placeholder users)
Users added by email before they join get status `INVITED`. On first login, we match the verified email and attach the identity to the existing row so their history stays linked.

## 9. Docker volumes (data persistence)
- Containers are disposable; data written inside them is lost when removed. A named volume keeps data outside the container's lifecycle.
- On macOS, volumes live inside Docker Desktop's Linux VM disk image, not in a normal folder. Inspect with `docker volume ls` / `docker volume inspect`.
- Compose prefixes the volume name with the project name (folder name), e.g. `gave_user-db-data`.
- `down -v` or a Docker Desktop reset deletes it. Use a SQL client or `pg_dump` to view or back up data, not the raw files.
- Bind mounts (host folders) are slower on macOS and have permission issues with Postgres, so named volumes are the default choice.

## 10. Referential integrity across services
- A foreign key cannot cross databases. Other services store the user's UUID as a plain column.
- The DB will not stop a reference to a missing user; the service must validate (API call) or maintain a local copy updated via events.
- Soft-deleting users keeps these references valid.

## 11. Domain-driven design (DDD)
Model the software around the business problem, in the business's own words.
- **Ubiquitous language:** code names match business terms (`settleUp`, `Settlement`), not technical ones.
- **Bounded context:** each part of the business gets its own model; the same word can mean different things (User in user-service vs. participant in expense-service). In microservices a bounded context usually becomes a service with its own database.
- **Entity:** has a persistent identity (`User`). **Value object:** defined by its values, immutable (`Money`, `Email`).
- **Aggregate / root:** a cluster kept consistent together, accessed through one root (`User` + `UserIdentity`; later `Expense` + its shares). Reference roots by id.
- **Repository:** one per aggregate root, hides persistence.
- **Domain service:** logic spanning aggregates that fits no single one.
- We use DDD lightly: feature packages, entities, aggregates, repositories, services as bounded contexts. It pays off where rules are complex (splitting, rounding, debt simplification).

## 12. Login/provisioning flow (three cases)
1. Identity (provider + subject) exists: returning user, log in.
2. No identity but verified email matches an existing user: link the identity; if the user was `INVITED`, activate them (their expenses stay attached).
3. Neither exists: create the user and identity in one transaction.
- Always require `email_verified` before linking or creating; reject deleted accounts.
- Keep the security layer thin: it translates the provider token into a plain profile object and calls the service (separation of authentication plumbing from business logic).
- Concurrency safety net: unique constraints in the database, since an application-level check alone can race.

## 13. OAuth2 / OpenID Connect login (authorization code flow)
1. Browser asks our app to log in with Google.
2. We redirect to Google with `client_id`, `scope`, random `state`, `redirect_uri`.
3. User logs in and approves at Google.
4. Google redirects back to the `redirect_uri` with a one-time `code`.
5. Our server exchanges the code plus `client_secret` directly with Google for tokens, including an ID token (signed JWT with `sub`, `email`, `email_verified`, `name`, `picture`). The secret never passes through the browser.
- OAuth2 = authorization; OpenID Connect (OIDC) = authentication layer on top (adds the ID token). Scope `openid` enables it; `profile` and `email` request claims.
- The redirect URI must match one registered in Google Cloud Console exactly (prevents code theft). Local: `http://localhost:8081/login/oauth2/code/google`.
- `state` is checked on return to protect against login CSRF.
- Secrets (`client-secret`) come from environment variables / a secret manager, never from committed files. Testing-mode apps only allow listed test users.

## 14. OIDC vocabulary: code, tokens, claims, principal
- **Authorization code:** short-lived one-time value Google gives the browser after login. Useless alone: it can only be exchanged for tokens by someone with our `client_secret`, so it can safely travel in a URL; tokens are fetched server-to-server and never touch the browser.
- **Tokens:** *ID token* (proves who the user is; OIDC) and *access token* (calls Google APIs on the user's behalf; unused by us).
- **ID token = JWT:** `header.payload.signature` (Base64). Header: algorithm and key id. Payload: claims. Signature: Google's digital signature.
- **Claims:** statements in the payload. About the token: `iss` (issuer), `aud` (audience = our client id), `exp`, `iat`, `nonce`. About the user: `sub` (stable id), `email`, `email_verified`, `name`, `picture`, `locale` (from scopes `profile`, `email`).
- **Validation before trust:** signature verified with Google's public keys (JWKS); `iss`, `aud`, `exp`, `nonce` checked; `state` on the callback matched (login CSRF protection).
- **Two identities:** Google's `sub` and our own `users.id` UUID; `user_identities` is the bridge between them.
- Spring session (`JSESSIONID`) is stateful; for microservices we will issue our own stateless JWT instead.

## 15. Login sequence (as built)
1. `GET /oauth2/authorization/google` -> redirect filter builds the Google URL (client_id, scope, state, nonce, redirect_uri) and stores state/nonce in the session.
2. User authenticates at Google -> redirected to `/login/oauth2/code/google?code=...&state=...`.
3. `OAuth2LoginAuthenticationFilter` exchanges the code (plus secret) at Google's token endpoint, validates the ID token, builds the `Authentication` with an `OidcUser` principal.
4. Our `OAuth2LoginSuccessHandler` maps claims to `ProviderProfile`, calls `UserProvisioningService.provision`, then redirects.

## 16. Walk-through: a brand-new user logs in (example: riya@gmail.com, sub 1098234)
1. `GET /oauth2/authorization/google` -> `OAuth2AuthorizationRequestRedirectFilter` generates state + nonce, stores them in the session, redirects to Google. Our authorization rules never run for this request.
2. Google authenticates her and redirects to `/login/oauth2/code/google?code=...&state=...`.
3. `OAuth2LoginAuthenticationFilter`: checks state; exchanges code + client secret for tokens; validates the ID token (signature, iss, aud, exp, nonce); builds an `OAuth2AuthenticationToken` whose principal is an `OidcUser`; calls our success handler (registered by `.successHandler(...)` in `SecurityConfig`).
4. Handler: casts the principal to `OidcUser`, builds `ProviderProfile`, calls `provision(profile)`.
5. `provision` (transaction starts at the service method): find identity by (GOOGLE, sub) -> empty; email verified -> yes; find user by email -> empty; create `User` + `addIdentity` + `save`; at commit: `insert into users`, then `insert into user_identities` (cascade, user_id set).
6. Handler logs the user id and redirects to `/api/health`; the next request passes `AuthorizationFilter` (`permitAll`) and reaches the controller.
- Returning user: step 5 finds the identity (the `@EntityGraph` loads the user in the same query) and returns it; no inserts.
- The transaction boundary is the service method, not the handler; after commit the `User` is detached, so only use already-loaded fields (e.g. `getId()`) in the handler.

## 17. OAuth2 login: every URL and who calls whom
- **Front channel** (through the user's browser, redirects): only harmless data (`client_id`, `state`, one-time `code`). **Back channel** (our server -> Google directly): secrets (`client_secret`, tokens).
- **Google never calls our server.** It redirects the *browser* to our callback; the browser makes the request. That is why `localhost` redirect URIs work.

| # | From -> To | URL | Notes |
|---|---|---|---|
| 1 | Browser -> app | `GET /oauth2/authorization/google` | starts login; `google` = registration id from yml |
| 2/3 | App -> browser -> Google | `https://accounts.google.com/o/oauth2/v2/auth?response_type=code&client_id&scope=openid profile email&redirect_uri&state&nonce&code_challenge&code_challenge_method=S256` | user logs in and consents at Google |
| 4/5 | Google -> browser -> app | `http://localhost:8081/login/oauth2/code/google?code=...&state=...` | `redirect_uri` must exactly match the one registered in Google Console; `state` checked against session |
| 6 | App -> Google (back channel) | `POST https://www.googleapis.com/oauth2/v4/token` | form: grant_type=authorization_code, code, redirect_uri, client_id, client_secret, code_verifier; returns access_token + id_token |
| 7 | App -> Google | `GET https://www.googleapis.com/oauth2/v3/certs` | Google public keys (JWKS, cached) to verify the ID token signature; then check iss, aud, exp, nonce |
| 8 | App -> Google | `GET https://www.googleapis.com/oauth2/v3/userinfo` (Bearer access token) | by default also called for profile/email scopes; claims merged |
| 9 | App -> browser | `302 /api/health` | after our success handler ran |
- Google endpoints come from Spring's built-in `CommonOAuth2Provider.GOOGLE` (registration id `google`). Unknown providers: set `spring.security.oauth2.client.provider.<name>.issuer-uri` and Spring reads `<issuer>/.well-known/openid-configuration` (discovery document).
- Our URL patterns are fixed defaults: `/oauth2/authorization/{registrationId}` and `/login/oauth2/code/{registrationId}`.
- Trust: Google verifies redirect_uri, client_id, client_secret, PKCE verifier. Our app verifies state, ID token signature, aud/iss/exp/nonce. The browser is never trusted with secrets or tokens.
- To observe: browser DevTools Network (preserve log) shows steps 1-5; for 6-8 set `logging.level.org.springframework.security.oauth2: TRACE`.

## 18. Google's ID token vs. our own JWT
- Google's ID token is a one-time proof for the login handshake; it stays on our server (back channel) and is discarded. It is never sent to the browser.
- After login the browser must prove who it is on every API call (HTTP is stateless). Options: server-side session (needs a shared store across services) or a signed token (JWT) that any service holding the verification key can check locally with no DB lookup. We use our own JWT.
- Why not reuse Google's token: wrong audience/purpose (login proof, not an API credential); identifies the person by Google's `sub`, forcing a lookup to our user UUID on every request; contains none of our data (roles, account status) and we cannot add claims; we do not control its expiry or revocation; ties every service to one provider (other providers would bring other formats).
- Flow: Google ID token -> user-service (verify, provision) -> issues OUR JWT (`sub` = our user UUID, `exp`, `iss`) -> React -> every API call via the gateway -> gateway verifies our signature -> downstream services.
- How our JWT reaches the browser (HttpOnly cookie vs. response body) is only about storing our token safely; it is unrelated to Google's token.

## 19. Login vs. staying logged in (OAuth2 does not imply JWT)
- Layer 1, authentication ("who is this?"): password, OAuth2/OIDC "Login with Google", magic link, passkeys. Layer 2, session ("how do I recognize them next request?"): server-side session cookie, JWT, or opaque access token + refresh token. OAuth2/OIDC belongs only to layer 1; afterwards the site does its normal layer 2.
- Common after "Login with Google": (1) server session cookie (opaque id mapped to the user in memory/Redis/DB; Spring Security's default `JSESSIONID`; easy to revoke); (2) the site's own JWT (SPAs, microservices); (3) opaque access + refresh tokens (large systems).
- JWT is a token *format* (signed JSON), not a protocol. The OIDC ID token happens to be a JWT, which is why JWTs appear in the OAuth flow, but our own session token is a separate choice.
- Why gave uses its own JWT: many services behind a gateway; sessions would need a shared store or a call to user-service per request; a signed JWT lets the gateway and services verify locally.
- Trade-off: a JWT cannot be deleted server-side before expiry; mitigate with short lifetimes (e.g. 15 min) plus refresh. For a single-server monolith a session cookie would be simpler.

## 20. Our own JWT in an HttpOnly cookie (stage 1)
- After the Google login the success handler provisions the user, issues OUR JWT, sets it as an HttpOnly cookie, invalidates the login session (the handshake needed it for state/nonce; afterwards we want stateless), and redirects.
- **Claims kept minimal:** `iss`, `sub` (= our user UUID, not Google's), `iat`, `exp` (15 min). JWTs are signed, not encrypted, so no personal data inside.
- **RS256 (key pair) over HS256 (shared secret):** with HS256 every verifier could also forge tokens. With RS256 only user-service holds the private key; the gateway/services get just the public key. Keys live outside the project (env vars `JWT_PRIVATE_KEY`/`JWT_PUBLIC_KEY` = `file:` paths); never commit the private key.
- **Cookie attributes:** `HttpOnly` (JS cannot read it -> XSS cannot steal it), `Secure` (HTTPS only; false locally, true in production), `SameSite=Lax` (not sent on cross-site POSTs -> blocks most CSRF; still sent on top-level navigation), `Path=/`, `Max-Age` = token TTL.
- **API behaviour:** unauthenticated `/api/**` returns 401 (not a redirect to Google) so a SPA can react.
- **Pending:** refresh tokens + logout (table `refresh_tokens`, hashed, rotated), CSRF protection for POST/PUT/DELETE (Spring's CSRF filter will return 403 until configured), move verification to the gateway, production `cookie-secure: true`.

## 21. Reference observation: how a real web app (Splitwise) authenticates API calls
(Seen in browser DevTools; secrets not recorded here. Never paste cookies or tokens into chats, tickets or docs: a live session cookie is a credential.)
- Login with Google is the same authorization-code flow: callback with `state`, `iss`, `code`, `scope`, then the server exchanges the code server-to-server (invisible to the browser) and answers with a redirect + `Set-Cookie`.
- API calls from the page are same-origin `fetch/XHR` requests that carry a **session cookie** (no `Authorization: Bearer`), plus a **CSRF token in a request header** (`X-CSRF-Token`).
- Cookie contents look like a Rails-style encrypted session plus a separate long-lived "remember me" cookie. Numeric user ids (we use UUIDs).
- Takeaways for gave: (1) cookie auth + CSRF header is a proven design; (2) serve the React app and the API from one origin through the gateway so cookies flow without CORS and `SameSite` works; (3) enable CSRF protection (Spring's `XSRF-TOKEN` cookie + `X-XSRF-TOKEN` header pattern) before adding any state-changing endpoint.

## 22. Deployment checklist (project goal: live demo for a resume)
- Deploy early (walking skeleton: gateway + user-service + bare React login) and keep deploying each feature; do not leave deployment to the last week (HTTPS, cookie, CORS and OAuth redirect issues all appear at once).
- Frontend: static host (Vercel / Netlify / Cloudflare Pages). Backend: all containers on one small VM with Docker Compose, the gateway as the only public entry point. Databases: one per service (Postgres containers, or managed free tiers).
- HTTPS + a real domain are required for `Secure` cookies and production OAuth. Set `gave.jwt.cookie-secure: true`.
- Google OAuth: publish the consent screen from Testing to Production so anyone can log in (basic scopes `openid profile email` need no security review; needs production redirect URI, privacy policy page, real domain). Add the production redirect URI in Google Console.
- Secrets (Google client secret, JWT private key, DB passwords) only in server environment variables / a secret manager, never in the repo.
- Cap AI usage (rate limits, per-user quotas) so a public demo cannot run up API costs.
- Repo quality: README with screenshot/GIF, architecture diagram, run-locally steps; tests for core logic (split/balance calculations); CI (GitHub Actions) building and testing on every push; docs folder records design decisions.

## 23. Auth decision: opaque tokens at the edge, JWT inside the cluster
Decided 2026-10-20 (supersedes the "own JWT validated by gateway" plan in section 19/20 for client-facing auth).
- **Edge (client <-> gateway):** opaque random token, stored server-side in Redis (key -> user id, device, expiry). Web may carry it in an HttpOnly cookie, the mobile app in `Authorization: Bearer <token>`. Same value, one flow for both clients.
- **Inside the cluster (gateway -> services, service -> service):** gateway looks the token up in Redis, then mints a short-lived internal JWT (RS256, `sub` = user UUID, tiny TTL) and forwards it. Services verify it locally with the public key; they never touch Redis or the client token.
- **Why:** instant revocation / "log out all devices" (delete the user's Redis keys), device list for free, no refresh-token rotation logic, nothing readable in the client credential. Internal JWT keeps services stateless and lets them trust a signed identity instead of a plain `X-User-Id` header (defence in depth).
- **Rule:** only the gateway validates client credentials. Services never parse client tokens; they must be unreachable except through the gateway (internal Docker network, no published ports).
- **Cost:** Redis becomes a hard dependency of login/every request (persistence + replication in production). One Redis lookup per request.
- **Swappable:** because auth lives at the gateway, the edge mechanism can be changed later without touching downstream services.
- **Redis licensing:** free to self-host (Docker). Valkey is the BSD-licensed drop-in fork if licensing matters. Managed free tiers exist (Redis Cloud, Upstash); check current limits.
- **Google sign-in:** web = authorization-code flow; mobile = native Google SDK ID token posted to the backend (`POST /auth/google`), verified, then an opaque token issued. Accept both client IDs as valid audiences.
- **Pending:** Redis container in infra compose, Spring Data Redis session store in user-service, gateway auth filter, internal JWT minting, logout / logout-all endpoints, replace stage-1 cookie JWT.

## 24. Redis session store, implemented in user-service (stage 2)
- **Token:** 32 random bytes from `SecureRandom`, Base64-URL encoded. Unguessable; carries no data (opaque).
- **Redis stores only the SHA-256 hash of the token** (`session:<hash>` -> hash of `userId`, `device`, with a TTL). A Redis leak or dump then does not hand out usable logins; same idea as storing password hashes.
- **Per-user index:** `user_sessions:<userId>` is a set of that user's session hashes. It powers logout-all and a future "your devices" screen. Expired sessions leave stale ids in the set; harmless because `revokeAll` deletes them anyway.
- **One credential, two transports:** the filter accepts `Authorization: Bearer <token>` (mobile) or the `SESSION` cookie (web). Same value, same Redis lookup.
- **Cookie:** `HttpOnly`, `SameSite=Lax`, `Path=/`, `Secure` via `gave.session.cookie-secure` (true in production).
- **Filter is created with `new` inside `SecurityConfig`, not `@Component`:** Spring Boot also registers any `Filter` bean in the servlet container, so it could run outside the security chain.
- **Login handshake session:** OAuth2 login needs an HTTP session for `state`/`nonce`; the success handler invalidates it after issuing our token.
- **Revocation:** logout deletes one session; logout-all deletes every session of the user, effective on the very next request (no waiting for expiry like a JWT).
- **Known gaps:** CSRF is ignored for `/api/auth/**` as a stopgap (cookie auth on state-changing endpoints needs real CSRF protection); no sliding expiry; no mobile `POST /auth/google` yet; validation still lives in user-service and moves to the gateway.
- **Gotcha (config):** Spring Boot does NOT fail on an unresolved `${VAR}` in a bound property; it keeps the literal text. A missing `GAVE_REDIS_PASSWORD` in the IntelliJ run configuration made the password the string `${GAVE_REDIS_PASSWORD}`: the app started and `/api/health` worked, but the first Redis call (login) failed with WRONGPASS -> 500. Symptom: works until the first call that touches the dependency.
- **Gotcha (secrets in .env):** Docker Compose and the shell strip the quotes around `"value"`; IntelliJ run-configuration env vars and Spring do not. Prefer plain alphanumeric/hex secrets (`openssl rand -hex 24`) so the same value can be pasted everywhere.
- **Gotcha (compose):** changing a service's `command:`/env in the compose file does nothing until the container is recreated (`docker compose up -d <service>`); the running container keeps the old config.

## 25. Error handling: two layers (401 / 403 / Problem Details)
- **Two layers produce errors and each needs its own handler.** (1) The security filter chain runs before Spring MVC: not logged in (401), not allowed (403), and failures thrown inside the OAuth2 success handler. (2) Spring MVC: validation, wrong method (405), unknown route (404), bugs (500). A `@RestControllerAdvice` only sees layer 2.
- **Common response format:** RFC 9457 Problem Details, `Content-Type: application/problem+json`, body `{type, title, status, detail, instance}`. Spring's `ProblemDetail` class builds it in MVC; in the security layer we write the same JSON by hand with `ProblemResponseWriter` (filters do not go through MVC message converters).
- **Layer 1:** `ApiAuthenticationEntryPoint` (401), `ApiAccessDeniedHandler` (403), registered in `SecurityConfig.exceptionHandling(...)`. `OAuth2LoginSuccessHandler` catches `AccountDeletedException | UnverifiedEmailException` and answers 403 with a generic message (the exception messages contain ids/emails and are only logged).
- **Layer 2:** `GlobalExceptionHandler extends ResponseEntityExceptionHandler` + `@RestControllerAdvice`. Extending inherits `@ExceptionHandler` methods for standard Spring MVC exceptions (405, 400, 404, 415, validation) rendered as Problem Details; the advice annotation registers the class. Explicit handlers for `AccessDeniedException` (403) and `AuthenticationException` (401); catch-all `Exception` logs the stack trace and returns a generic 500.
- **Handler selection:** Spring picks the `@ExceptionHandler` whose declared exception type is closest to the thrown one, so inherited specific handlers beat our `Exception.class` catch-all. Without extending the base class, a 405 would be swallowed by the catch-all and become a 500 (`ExceptionHandlerExceptionResolver` runs before `DefaultHandlerExceptionResolver`).
- **Never put exception messages in client responses** (ids, emails, internals); log them server-side.
- **Gotcha (entry points):** `DelegatingAuthenticationEntryPoint` uses the first registered entry point as the default when no matcher matches. Registering ours first made `/` return 401 too, not the Google redirect. Accepted: user-service is an API, login starts only at `/oauth2/authorization/google`.
- **Gotcha (Spring Security 7):** `AntPathRequestMatcher` was removed; use `PathPatternRequestMatcher.withDefaults().matcher("/api/**")`.
- **Browser testing:** `/api/me` in the address bar now shows 401 JSON; start the login at `/oauth2/authorization/google`.

## 26. CSRF protection: Spring's double-submit cookie token (Option A)
- **Threat:** browsers attach a site's cookies to every request to that site, whichever page triggered it. A page on `evil.com` can make your browser POST to gave with your `SESSION` cookie. Only auto-attached credentials (cookies) are exposed; `Authorization: Bearer` headers are added by our own code, so the mobile app is not CSRF-able.
- **Layers:** (1) `SameSite=Lax` on the SESSION cookie; (2) a CSRF token (double-submit cookie). Chosen over a lighter Origin/`Sec-Fetch-Site` filter because it is the standard, interview-recognisable design; trade-off is extra code and frontend work.
- **How it works:** the server sets a readable (NOT HttpOnly) cookie `XSRF-TOKEN=<uuid>`. On POST/PUT/PATCH/DELETE the frontend JS copies that value into header `X-XSRF-TOKEN`. `CsrfFilter` compares header value to cookie value; mismatch or missing -> 403 (`MissingCsrfTokenException`/`InvalidCsrfTokenException` -> our `ApiAccessDeniedHandler` -> Problem Details "Invalid or missing CSRF token"). An attacker's page can make the browser SEND the cookie but cannot READ it, and cross-site forms cannot set custom headers.
- **The server stores nothing:** `CookieCsrfTokenRepository` keeps the token only in the browser's cookie. "Load" = read the request cookie; "save" = write `Set-Cookie`. "Missing cookie" is never treated as "matching": `RepositoryDeferredCsrfToken.init()` generates a fresh token when none came in, so an absent/empty header can never equal it.
- **Our code (3 touch points):** `SecurityConfig` (repository `withHttpOnlyFalse()`, cookie customizer `SameSite=Lax` + `Secure` flag, `ignoringRequestMatchers(bearerRequests)`, handler plug-in); `SpaCsrfTokenRequestHandler`; `ApiAccessDeniedHandler` (CSRF-specific message). Spring's code generates/saves the token.
- **`SpaCsrfTokenRequestHandler`:** `handle()` runs on EVERY request, delegates to the Xor handler (BREACH masking of tokens rendered in bodies) and calls `csrfToken.get()` to force the lazy deferred token to load, which writes the cookie on the first response. `resolveCsrfTokenValue()` uses the plain handler when the `X-XSRF-TOKEN` header has text (JS sends the raw cookie value) and the xor handler otherwise (masked `_csrf` form field). It delegates to different objects with the same method name; it is not recursion and changes nothing.
- **Header only on writes:** the cookie goes on every request (browser); the header only on unsafe methods (our JS). GET and browser redirects need no header.
- **Bearer exemption:** requests with `Authorization: Bearer ...` skip the CSRF check (a foreign page cannot set that header). The old `/api/auth/**` exemption was removed.
- **Token lost (cookie deleted/expired):** the write gets 403 once; that response sets a fresh cookie; a robust frontend does a GET (e.g. `/api/me`) and retries once, and always re-reads the cookie before each write.
- **Known limits:** unsigned double-submit can be defeated if an attacker can plant cookies (compromised sibling subdomain, plain HTTP): keep one origin through the gateway, HTTPS in production, SameSite=Lax. CSRF tokens do not protect against XSS (script on our own page can read the cookie and set the header); HttpOnly SESSION cookie limits the damage.
- **Gotcha (browser testing):** Safari isolates cookies per private tab, so a second private tab looks logged out. Test in a normal window.
- **Gotcha (sessions pile up):** every login creates a new Redis session; old ones live until the 30-day TTL. Later: cap sessions per user / "your devices" screen.

## 27. Cookie attributes explained
- **HttpOnly:** the browser refuses to expose the cookie to JavaScript (`document.cookie` omits it) but still sends it with requests. Defends against XSS stealing the session token. It does not stop injected script from making requests that carry the cookie automatically. SESSION is HttpOnly; XSRF-TOKEN must NOT be (JS has to read it).
- **Secure:** only sent over HTTPS (false locally, true in production).
- **SameSite:** controls cross-SITE sending. "Site" = scheme + registrable domain (`app.gave.com` and `api.gave.com` are the same site; `gave.com` and `evil.com` are cross-site; ports are ignored, so `localhost:3000` and `localhost:8081` are same-site but cross-ORIGIN).
  - `Strict`: never sent on cross-site requests, even when clicking a link to the site (first page load looks logged out).
  - `Lax`: sent on same-site requests and on cross-site top-level navigations that use a safe method (clicking a link, GET); NOT sent on cross-site fetch/XHR, images, iframes, or form POSTs.
  - `None`: always sent (requires Secure); needed only for genuine cross-site setups.

## 28. API gateway, phase 1: plain proxy (Spring Cloud Gateway Server MVC)
- **Choice:** servlet-based Spring Cloud Gateway Server MVC (not the reactive WebFlux flavor). Same programming model as the services, so the session filter / CSRF code built in user-service can move over with few changes. Spring Cloud train **2025.1.x** supports Boot 4.0/4.1 (4.1 from 2025.1.2); we use 2025.1.3. Starter: `spring-cloud-starter-gateway-server-webmvc`, version managed by importing `spring-cloud-dependencies` in the root pom (`spring-cloud.version` property must be defined or Maven looks for a literal `${spring-cloud.version}` artifact).
- **Routing:** Java DSL (the documented MVC style): a `RouterFunction<ServerResponse>` bean; `route("user-service").route(predicate, http()).before(uri(url)).build()`. `http()` performs the proxied call; `before(uri(...))` sets the target. Paths with no matching route -> 404 from the gateway. Target URL comes from `gave.services.user-service.url`.
- **Phase 1 behaviour:** pure pass-through (status, headers, cookies unchanged). Browser talks only to `:8080`; user-service on `:8081` is internal.
- **Two fixes needed in user-service once it sits behind a proxy:** (1) set the Google `redirect-uri` explicitly (`${GAVE_PUBLIC_BASE_URL:http://localhost:8080}/login/oauth2/code/google`); Spring would otherwise derive it from the request it sees (`:8081`), sending Google's callback around the gateway; also register that URI in Google Cloud Console. (2) `server.tomcat.use-relative-redirects: true` so `sendRedirect("/api/me")` emits `Location: /api/me` (resolved by the browser against `:8080`) instead of an absolute `:8081` URL.
- **Phases:** 1 proxy; 2 session check via Redis at the gateway (reject unauthenticated before they reach services); 3 CSRF + internal JWT at the gateway, downstream services become JWT resource servers.
- **Gotchas:** stale IntelliJ run configurations keep the old main-class name after a package rename (`ClassNotFoundException: org.example...`): delete them and run from `main()`. After adding a dependency, reload Maven in IntelliJ or the IDE still reports missing packages (Maven itself compiles fine). A module generated by Spring Initializr includes a test class; the pom needs `spring-boot-starter-webmvc-test` (test scope) or test sources fail to compile.

## 29. Gateway phase 3a/3b: CSRF at the gateway and the internal JWT
- **3a (CSRF moves to the gateway):** the gateway is where cookies enter, so it issues `XSRF-TOKEN` and checks `X-XSRF-TOKEN` on unsafe methods (same classes as before: `CookieCsrfTokenRepository.withHttpOnlyFalse()`, `SpaCsrfTokenRequestHandler`, Bearer requests exempt). user-service has CSRF disabled. Both had to change in the same step: two CSRF filters would each `Set-Cookie: XSRF-TOKEN` with different values. CSRF is checked BEFORE the Redis session lookup, so forged requests never reach Redis. Dev caveat until 3c: user-service on :8081 accepts cookie writes without CSRF if hit directly (not reachable in a real deployment).
- **Gotcha (`/error`):** when forwarding fails (downstream down), Tomcat dispatches to `/error`; `/error` requires authentication and `OncePerRequestFilter` skips error dispatches, so the client saw 401 instead of a 5xx. Fix: `/error` is public in the gateway. Follow-up: map failed forwards to a 502 Problem Details.
- **3b (internal JWT):** after validating the client's ticket, the gateway signs a short-lived JWT (RS256) and attaches it to the proxied request. Only the gateway holds the private key; services verify with the public key (a shared secret would let any service forge tokens).
  - Header `{alg: RS256, kid: gateway-1}`; claims `iss=gave-gateway`, `aud=gave-services`, `sub=<user uuid>`, `sid=<redis session id = sha256(ticket)>`, `jti`, `iat`, `exp = iat + 60s`.
  - **`sid` claim:** lets user-service revoke the right session on logout without ever seeing the raw ticket.
  - **`kid`:** identifies the signing key, enabling rotation (publish two public keys, switch signing, retire the old one).
  - **Keys:** generated with `openssl genpkey` (PKCS#8 private, `BEGIN PRIVATE KEY`) and `openssl pkey -pubout` (X.509 public) into `~/.gave/keys/` (outside the repo, private key mode 600). Loaded via `RsaKeyConverters.pkcs8()/x509()`, wrapped in an RSA JWK and `NimbusJwtEncoder`.
  - **Where it runs:** a `.before(...)` function on the route rebuilds the request with `ServerRequest.from(request).headers(...)`. It always removes any client-supplied token header, then adds a freshly minted one only if the SecurityContext holds an `AuthenticatedSession`. Public routes (login) get none; the OAuth handshake still needs its JSESSIONID cookie.
  - **Transitional:** the JWT travels in `X-Gave-Token` for now; 3c switches to `Authorization: Bearer` and strips the client's cookie/ticket for authenticated requests (user-service's own session filter prefers the Bearer header, so using Authorization earlier would break it).
- **Verified (3b):** token arrives upstream; signature verifies with the real public key and fails with another key or a tampered payload; lifetime 60 s; `sid` equals the Redis key suffix; forged client header is replaced when authenticated and dropped on public routes; POST/PATCH bodies (incl. 5 KB) survive the request rebuild; invalid ticket -> 401 from the gateway.
- **Testing tip:** to inspect what a gateway forwards, point `gave.services.user-service.url` at a throwaway echo server. Python's `http.server` does not decode chunked request bodies; the gateway forwards bodies chunked, so an echo server must handle `Transfer-Encoding: chunked` or it will wrongly report empty bodies.

## 30. Gateway phase 3c: user-service becomes a JWT resource server
- **Trust boundary:** clients hold a ticket (cookie/Bearer); only the gateway understands it. Services receive only a gateway-signed JWT in `Authorization: Bearer` and never see the client's ticket or cookies.
- **Gateway routes split in two** (`RouteConfig`): (1) `loginRoutes` (`/oauth2/**`, `/login/**`): forwarded as they are (the OAuth handshake needs the browser's `JSESSIONID`), except any client `Authorization` header is dropped; (2) `userServiceApiRoutes` (`/api/me/**`, `/api/auth/**`, `/api/health`): always drops the client's `Authorization`; if the request is authenticated, also drops `Cookie` and sets `Authorization: Bearer <freshly minted JWT>`. Applying the strip to the login routes would break re-login for an already-logged-in browser (callback arrives with a valid SESSION and the handshake JSESSIONID).
- **user-service verification (`JwtConfig`):** `NimbusJwtDecoder.withPublicKey(...)` pinned to RS256 (never trust the token's own `alg`; blocks algorithm-confusion and `alg=none`), validators: default timestamp + issuer (`gave-gateway`) + a custom audience check (`gave-services`). Built explicitly instead of Boot's `jwt.public-key-location` property so issuer validation needs no OIDC discovery. Only the PUBLIC key is present in user-service.
- **Principal:** `GaveJwtAuthenticationConverter` turns a verified `Jwt` into `UsernamePasswordAuthenticationToken(CurrentUser(userId = sub, sessionId = sid), jwt, [])`; controllers use `@AuthenticationPrincipal CurrentUser` and stay independent of JWT classes.
- **Logout without the ticket:** `sid` (the Redis key suffix, sha256 of the ticket) lets user-service revoke exactly the caller's session (`SessionStore.revoke(sessionId)`); `sub` drives logout-all. `SessionStore.resolve` and `SessionAuthenticationFilter` were deleted from user-service (session READS now happen only in the gateway; WRITES only in user-service).
- **user-service `SecurityConfig`:** CSRF disabled (no cookie authentication reaches it), `oauth2Login` (still creates the Redis session after Google login) plus `oauth2ResourceServer(jwt)`, public: `/api/health`, `/error`; everything else authenticated; entry point/denied handler return Problem Details JSON.
- **Header precedence (unchanged rule):** the gateway filter prefers an explicit `Authorization` header over the cookie, so a valid cookie plus a bogus Bearer is unauthenticated (401).
- **Verified (18 + 6 checks):** through the gateway cookie/Bearer tickets work, invalid ticket 401; directly against user-service a raw ticket (cookie or Bearer) is 401, a properly signed JWT is 200, and forged-key / wrong audience / wrong issuer / expired / tampered payload / `alg=none` are all 401; logout revokes only the caller's session and clears the cookie, logout-all revokes all, revoked ticket then 401, cookie write without CSRF header 403; upstream sees the internal JWT and no Cookie, never the client's Authorization; login routes keep cookies.
- **Gotcha:** `AbstractAuthenticationToken` is in `org.springframework.security.authentication` (not `...core`).
- **Gotcha:** user-service no longer accepts a hand-made request with a raw cookie, so manual testing straight against :8081 needs a JWT; go through the gateway, or mint a test token signed with the dev key.

## 31. Profile endpoints: GET / PATCH /api/me
- **Identity comes from the token, never from the URL or body:** `/api/me` uses the `CurrentUser.userId()` from the verified JWT, so a user can only read/edit their own profile (no id in the path to tamper with: IDOR-proof by construction).
- **DTOs, not entities:** `ProfileResponse` (record, `from(User)`) controls exactly what leaves the API (no `deletedAt`, `updatedAt`, `identities`). `UpdateProfileRequest` (record) lists only the editable fields (`displayName`, `defaultCurrency`, `locale`); unknown JSON fields (`email`, `status`, `id`) are ignored, which blocks mass-assignment attacks.
- **Partial update (PATCH):** a field that is absent/null means "leave unchanged"; `User.updateProfile(...)` applies only non-null values. Consequence: you cannot clear a value to null with this endpoint (acceptable for these fields).
- **Validation layers:** (1) Bean Validation on the DTO (`spring-boot-starter-validation`; `@Valid @RequestBody`): `@Size`, `@Pattern`; constraints only fire on non-null values, so omitted fields pass. (2) Business validation in the service: `Currency.getInstance(code)` rejects well-formed but unknown codes (`ZZZ`) with `InvalidProfileException` -> 400. Pattern alone cannot know which 3-letter codes are real.
- **Error mapping:** `MethodArgumentNotValidException` is overridden in `GlobalExceptionHandler` to return Problem Details with an `errors` map (`field -> message`, first message per field); `UserNotFoundException` 404; `AccountDeletedException` 403; `InvalidProfileException` 400 (message is a fixed, safe string). Malformed JSON 400, wrong content type 415, wrong method 405 come from the inherited `ResponseEntityExceptionHandler`.
- **Transactions & `open-in-view: false`:** the service maps entity -> DTO inside `@Transactional`; the controller never touches entities. `PATCH` relies on JPA dirty checking (managed entity changes are flushed at commit; `@UpdateTimestamp` bumps `updated_at`), no explicit `save`.
- **Order of checks through the gateway:** CSRF runs before authentication, so an unauthenticated cookie-less write returns 403 (CSRF) not 401; Bearer writes skip CSRF.
- **Verified (19 checks against the real DB with throwaway users):** read, partial updates (trim, one field, several, `{}`), persistence, field errors (blank, 101 chars, shape, unknown currency, bad locale, several at once), malformed JSON, body fields `email/status/id` ignored, CSRF with cookie auth, deleted user 403, missing user 404, 415, 405.

## 32. Internal (service-to-service) endpoints and service identity
- **Separate prefix, controller and rule:** `/internal/users/lookup` and `/internal/users/invite` live in `InternalUserController` under `/internal/**`, with their own line in user-service's `SecurityConfig`. They must not sit under `/api/**`: anything the gateway exposes can be called by any logged-in user, which would allow user enumeration (lookup with guessed ids) and invite spam. The prefix keeps "what is public" a short readable list.
- **Three layers keep them private:** (1) the gateway's route table has no `/internal/**` route; (2) the gateway explicitly `denyAll()`s `/internal/**` (listed before the permit rules) so a broad route added later cannot expose them; (3) user-service itself requires a valid JWT on `/internal/**`, so a request that reaches :8081 directly still has to authenticate; in deployment service ports are not published.
- **Authentication: token relay.** A service called on behalf of a user forwards the JWT it received from the gateway (`Authorization: Bearer`, same audience for all services, 60 s TTL). The authentication object keeps the `Jwt` as its credentials, so a `RestClient` interceptor can read it back. Relay depends on the request thread (ThreadLocal) and has no answer for calls with no user behind them.
- **Most flows are not user-less:** group lookups, invites, the "unsettled balance?" check before deletion, and AI receipt scanning are all triggered by a user. Only scheduled/background work is user-less, so a service identity is deferred (YAGNI).
- **Service identity later:** OAuth2 client credentials from a small Spring Authorization Server module (clients per service, scopes like `users:read`/`users:invite`, JWKS). user-service then accepts two issuers (`gave-gateway` with the static public key, `gave-auth` via JWKS) using `JwtIssuerAuthenticationManagerResolver`, and the scope check goes in the one `/internal/**` rule. Alternatives considered: shared API key (weak, hard to rotate, acceptable only as a labelled stop-gap), mTLS/service mesh (right on Kubernetes at scale, overkill here), Keycloak (realistic but heavy to run and learn). How large companies do it: workload identity or mTLS for "which service", a JWT for "which user", a policy layer for "is it allowed".
- **Data minimisation:** lookup returns id, displayName, avatarUrl, status only (never email); deleted users are anonymized ("Deleted user", no avatar); `missing` ids are reported so callers never assume every id resolves; at most 100 ids per call (batching avoids N+1).
- **Invite is find-or-create and idempotent:** the email is lowercased (the table has `CHECK (email = lower(email))`); 201 when created, 200 when it existed (any case); concurrent invites of one new email are safe: `saveAndFlush` hits the unique constraint, the loser catches `DataIntegrityViolationException` and re-reads. The method is deliberately NOT `@Transactional`: in one transaction a constraint violation marks it rollback-only and the follow-up read would fail. The later Google login activates the same row (`INVITED -> ACTIVE`, same id), so memberships made before joining stay valid. An email of a deleted account returns 409.
- **Validation order:** Bean Validation runs before the service, so an email with surrounding spaces is rejected with 400 (the service's trim is only defensive for other callers).
- **Dev tooling:** `scripts/gen-dev-keys.sh` and `scripts/mint-dev-jwt.sh` let a teammate run a service without Google login or the gateway using their own local key pair; docs: `docs/api-internal-users.md` (contract), `docs/local-development.md`.
- **Gotcha (zsh):** `path` is tied to `$PATH`; using it as a loop variable breaks command lookup. Unquoted `${var:+-H "..."}` does not word-split in zsh (use an array: `args+=(-H "...")`).
