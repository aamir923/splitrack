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
