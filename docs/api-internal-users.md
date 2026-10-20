# Internal API: user-service (for other services)

Service-to-service endpoints. **Not exposed to the outside**: the api-gateway has no route for `/internal/**` and
explicitly denies it, so only services on the internal network can call them. Browsers and the mobile app never do.

Base URL (config property in the calling service): `gave.services.user-service.url`, e.g. `http://localhost:8081`
locally, `http://user-service:8081` in Docker Compose.

## Authentication: token relay

A service calling on behalf of a user **forwards the JWT it received** from the gateway:

```
Authorization: Bearer <the same JWT group-service received>
```

user-service verifies it exactly as it does for any request (RS256 signature with the gateway's public key, issuer
`gave-gateway`, audience `gave-services`, not expired). Tokens live 60 seconds, which is plenty for one request chain.

Limits of relay (by design, for now):
- It only works while handling a user's request. A call with **no user behind it** (scheduled job, startup task) has no
  token to relay. That needs a service identity (OAuth2 client credentials); see "Later" below.
- Relay depends on a `ThreadLocal`: do the call on the request thread, or capture the token before going async.

## `POST /internal/users/lookup`

Names and avatars for many user ids in one call (batch, to avoid N+1 calls).

Request
```json
{ "ids": ["01a14b5d-acc1-7400-8bbd-b468bb91bdb6", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"] }
```
- 1 to 100 ids. Duplicates are ignored. Result order follows the request order.

Response `200`
```json
{
  "users": [
    { "id": "01a14b5d-acc1-7400-8bbd-b468bb91bdb6", "displayName": "Aamir Shaik",
      "avatarUrl": "https://...", "status": "ACTIVE" }
  ],
  "missing": ["aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"]
}
```
- `status` is `ACTIVE`, `INVITED` or `DELETED`.
- A `DELETED` user is anonymized: `displayName` is `"Deleted user"`, `avatarUrl` is `null`. Callers must tolerate it.
- `INVITED` users have a placeholder display name (the part of the email before `@`) until they sign in.
- `missing` lists ids that do not exist. **Never assume every id resolves.**
- Emails are intentionally **not** returned.

## `POST /internal/users/invite`

Find-or-create a user by email, for the group "invite a friend" flow.

Request
```json
{ "email": "friend@example.com" }
```
- Must be a valid address, at most 320 characters, **no surrounding spaces** (rejected with 400).
- Case-insensitive: `Friend@Example.com` and `friend@example.com` are the same person.

Response
```json
{ "id": "01a15b33-49d2-732f-a8f7-ab387dc4c102", "status": "INVITED" }
```
- `201 Created` when a new `INVITED` user was created, `200 OK` when the email already belonged to a user
  (`INVITED` or `ACTIVE`). **Idempotent**: calling it twice returns the same id.
- Concurrent calls for the same email are safe (one creates, the others return the same user).
- When the invited person first signs in with Google using that email, the existing `INVITED` row becomes `ACTIVE`
  and **keeps the same id**, so memberships created before they joined stay valid.
- `409 Conflict` if the email belongs to a deleted account.

## Errors

All errors are RFC 9457 Problem Details (`application/problem+json`):
```json
{ "type": "about:blank", "title": "Bad Request", "status": 400, "detail": "Validation failed",
  "instance": "/internal/users/invite", "errors": { "email": "must be a valid email address" } }
```

| Status | When |
|---|---|
| 400 | validation failure (`errors` maps field to message), malformed JSON, bad UUID |
| 401 | missing, expired, or invalid JWT (also wrong issuer or audience) |
| 409 | invite for an email that belongs to a deleted account |
| 415 / 405 | wrong content type / wrong method |

## Client sketch for group-service (Spring `RestClient` with relay)

```java
@Component
class UserServiceClient {

    private final RestClient client;

    UserServiceClient(RestClient.Builder builder,
                      @Value("${gave.services.user-service.url}") String baseUrl) {
        this.client = builder
                .baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    if (auth != null && auth.getCredentials() instanceof Jwt jwt) {
                        request.getHeaders().setBearerAuth(jwt.getTokenValue());
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    record LookupRequest(List<UUID> ids) {}
    record UserSummary(UUID id, String displayName, String avatarUrl, String status) {}
    record LookupResponse(List<UserSummary> users, List<UUID> missing) {}

    LookupResponse lookup(List<UUID> ids) {
        return client.post().uri("/internal/users/lookup")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new LookupRequest(ids))
                .retrieve()
                .body(LookupResponse.class);
    }
}
```
`auth.getCredentials()` is the `Jwt` because the authentication token is built from a verified JWT (Spring's default
`JwtAuthenticationToken` returns the `Jwt` from `getCredentials()` too). Set timeouts on the underlying HTTP client, retry
only safe calls (`lookup`), and degrade gracefully (show "Unknown member") if user-service is unavailable.

## Later

- **Service identity** for user-less calls: OAuth2 client credentials issued by a small Spring Authorization Server
  module, with scopes such as `users:read` and `users:invite`. user-service would then accept a second issuer
  (`JwtIssuerAuthenticationManagerResolver`) and require the scope on `/internal/**` in one place (`SecurityConfig`).
- Events (a message broker) plus local read models if lookups become a latency or coupling problem.
