# Local development

## Prerequisites
- JDK 21 (Temurin works), Docker Desktop, `openssl`, `curl`
- Maven is bundled: use `./mvnw` from the repo root

## 1. Infrastructure (PostgreSQL + Redis)

```bash
cp infra/.env.example infra/.env        # then edit infra/.env and set your OWN random passwords
                                        # e.g. openssl rand -hex 24  (plain hex avoids quoting problems)
docker compose -f infra/docker-compose.db.yml up -d
```
- `infra/.env` is git-ignored: never commit it.
- Compose project name is `gave`. PostgreSQL listens on `localhost:5433` (database `gave_user`), Redis on `6379`
  (ACL user `gave_redis_service`, the default user is disabled).
- Redis is not browsable with `KEYS` (blocked by the ACL); use `redis-cli --scan`.

## 2. Signing keys (local only)

The api-gateway signs a short-lived JWT per request; services verify it with the public key.

```bash
scripts/gen-dev-keys.sh                 # creates ~/.gave/keys/jwt-private.pem and jwt-public.pem
```
- Keys live in your home directory, outside the repo. Never commit or share the private key.
- Each developer generates their own pair. A service only needs the **public** key; if you run your own gateway it needs
  the private one too.

## 3. Environment variables

| Variable | Used by | Meaning |
|---|---|---|
| `GAVE_SERVICE_PASSWORD` | user-service | PostgreSQL password (same value as in `infra/.env`) |
| `GAVE_REDIS_PASSWORD` | user-service, api-gateway | Redis password for `gave_redis_service` |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | user-service | Google OAuth client (only needed for the real Google login) |

Set them in your IDE run configuration **without surrounding quotes**. Spring does not fail on a missing `${VAR}`: it keeps
the literal text, so the app starts and then fails on the first call that needs the value (for example a Redis login).

## 4. Run

| Service | Port | Command |
|---|---|---|
| user-service | 8081 | `./mvnw -pl user-service spring-boot:run` |
| api-gateway | 8080 | `./mvnw -pl api-gateway spring-boot:run` |

Browsers and the mobile app talk to the **gateway (8080)** only. Google sign-in starts at
`http://localhost:8080/oauth2/authorization/google`; the redirect URI
`http://localhost:8080/login/oauth2/code/google` must be registered in your Google Cloud OAuth client.

## 5. Developing a service without Google login or the gateway

Mint a token in the gateway's format and call your service directly:

```bash
TOKEN=$(scripts/mint-dev-jwt.sh 11111111-2222-3333-4444-555555555555)   # any user uuid, valid 1 hour
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/me
```
- It signs with your local private key, so your service must be configured with the matching public key.
- The token carries `iss=gave-gateway`, `aud=gave-services`, `sub=<user uuid>`, `sid=dev-session`.
- `/api/me` needs a real row in the `users` table for that uuid. Insert a throwaway user with `psql` or use your own id.

## 6. A new service that verifies the gateway's JWT

Copy the resource-server setup from user-service: `config/JwtConfig` (decoder from the public key, RS256 pinned, issuer
and audience validators), a small converter that turns the JWT into your principal, and a `SecurityConfig` that
requires authentication, disables CSRF (no cookies reach services) and returns Problem Details for 401/403. To be
reachable from outside, the gateway needs a route for your paths; internal endpoints are never routed.

See `docs/api-internal-users.md` for calling user-service from another service.
