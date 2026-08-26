# iOS auth contract (v0.1)

Generated from the current backend Java sources and Android behavioral reference. Not an OpenAPI invention.

Backend JSON is **camelCase**. Access tokens are JWTs; refresh tokens are opaque. The backend remains the authority — the iOS client does not decode JWT claims for authorization.

## Base URL

Configured via xcconfig `PHOKARTA_API_BASE_URL`, injected into Info.plist, normalized with a trailing slash.

- Debug may use `http://` for local development.
- Release must be absolute `https://`.
- `https://api.phokarta.invalid/` is an explicit non-production placeholder. Runtime refuses to send auth traffic to `.invalid` hosts.

## Endpoints implemented in iOS v0.1

### `POST /api/v1/auth/register`

- Auth: public
- Status: `201 Created`
- Rate limit: `register` bucket
- Request (`RegisterRequest`):

```json
{ "email": "string", "username": "string", "displayName": "string", "password": "string" }
```

Validation (backend):

| Field | Rules |
|-------|--------|
| email | `@NotBlank @Email @Size(max=320)`, normalized trim+lowercase |
| username | `@NotBlank @Size(min=3,max=32)` `^[a-zA-Z0-9_]+$`, normalized trim+lowercase |
| displayName | `@NotBlank @Size(min=1,max=100)`, trimmed |
| password | `@NotBlank @Size(min=8,max=72)` |

- Response (`AuthSessionResponse`): `user`, `accessToken`, `refreshToken`, `tokenType` (`Bearer`), `expiresIn` (seconds), `accessTokenExpiresAt` (ISO-8601 Instant)
- iOS behavior: **sign in immediately** from the returned tokens (same as Android).

### `POST /api/v1/auth/login`

- Auth: public
- Status: `200 OK`
- Rate limit: `login` bucket
- Request (`LoginRequest`):

```json
{ "identifier": "string", "password": "string" }
```

`identifier` is email or username (`@NotBlank @Size(max=320)`). Password DTO allows 1–72 characters; service then requires 8–72.

- Response: same `AuthSessionResponse` as register
- Invalid email/username/password → `401` `INVALID_CREDENTIALS` (same code for missing users)

### `POST /api/v1/auth/refresh`

- Auth: public (refresh token in body, not Authorization header)
- Status: `200 OK`
- Rate limit: `refresh` bucket
- Request (`RefreshRequest`): `{ "refreshToken": "string" }` (`@Size(min=20,max=512)`)
- Response (`TokenPairResponse`): `accessToken`, `refreshToken`, `tokenType`, `expiresIn`, `accessTokenExpiresAt`
- Semantics: rotating refresh tokens. Successful refresh issues a **new** refresh token and revokes the previous one. Reuse of a rotated/revoked token revokes the whole family and returns `401` `INVALID_REFRESH_TOKEN`.

### `POST /api/v1/auth/logout`

- Auth: public
- Status: `204 No Content`
- Request (`LogoutRequest`): `{ "refreshToken": "string" }`
- Revokes that refresh session if found. No bearer header.

iOS / Android UX: local logout **always** clears Keychain/session even if the network call fails. Remote revocation is best-effort. The UI must not claim the server session was revoked when the call failed.

### `GET /api/v1/me`

- Auth: `Authorization: Bearer <access>`
- Status: `200 OK`
- Response (`UserProfileResponse`): `id` (UUID), `email`, `username`, `displayName`, `bio`, `avatarUrl`, `followerCount`, `followingCount`, `friendCount`
- Missing/expired access: `401` with `TOKEN_EXPIRED` or `UNAUTHORIZED`
- iOS v0.1 domain user keeps identity/display fields only (`id`, `email`, `username`, `displayName`)

## Token lifetimes (backend defaults)

- Access JWT: 15 minutes (`PHOKARTA_JWT_ACCESS_TTL`)
- Refresh session: 30 days (`PHOKARTA_JWT_REFRESH_TTL`)
- iOS does not treat client-side expiry as authority. Failed access is handled via `401` + single-flight refresh.

## Authenticated request retry

1. Send request with current access token
2. If `401` on a retry-eligible authenticated endpoint:
   - If another request already rotated the access token, retry with the new token
   - Otherwise single-flight `POST /api/v1/auth/refresh`
   - Persist new access **and** new refresh atomically
   - Retry the original request **once**
3. Do not retry login, register, refresh, or logout
4. Do not loop on a second `401`

## Error contract (`ApiError`)

JSON camelCase:

```json
{
  "timestamp": "2026-08-26T16:00:00Z",
  "status": 401,
  "code": "INVALID_CREDENTIALS",
  "message": "Invalid email/username or password",
  "path": "/api/v1/auth/login",
  "requestId": "uuid",
  "fieldErrors": { "field": "message" },
  "requiredVersion": null
}
```

`fieldErrors` and `requiredVersion` may be omitted (`NON_NULL`). `X-Request-Id` is also an HTTP header.

### Auth-relevant codes

| HTTP | code | iOS mapping |
|------|------|-------------|
| 400 / 422 | `VALIDATION_ERROR`, `MALFORMED_REQUEST` | validation |
| 401 | `INVALID_CREDENTIALS` | invalid credentials |
| 401 | `INVALID_REFRESH_TOKEN`, `TOKEN_EXPIRED`, `UNAUTHORIZED` | unauthorized / terminal session loss when refresh fails |
| 403 | `FORBIDDEN` | forbidden |
| 404 | `NOT_FOUND` | not found |
| 409 | `EMAIL_ALREADY_EXISTS` | duplicate email |
| 409 | `USERNAME_ALREADY_EXISTS` | duplicate username |
| 429 | `RATE_LIMITED` | rate limited (no automatic retry) |
| 5xx | `INTERNAL_ERROR` | server / transient for restore |

Auth rate limit: 30 attempts / 60s per action+client IP (in-memory limiter).

## Session restore (product behavior)

1. Load Keychain session
2. None → signed out
3. If present: refresh, then `GET /me`
4. Transient network / timeout / 5xx / 429 → **keep** stored session and treat as signed in (offline)
5. Terminal refresh/auth loss → clear Keychain, signed out

## Future compatibility (not implemented in v0.1)

- `GET /api/v1/me/policy-status` → `{ requiredVersion, acceptedVersion, accepted }`
- `POST /api/v1/me/policy-acceptance`
- `DELETE /api/v1/me` account deletion (password required for local accounts; terminal 401/404 treated as account gone on Android)

iOS v0.1 does not call these endpoints.
