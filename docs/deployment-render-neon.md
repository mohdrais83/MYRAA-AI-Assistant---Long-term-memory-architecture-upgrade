# Render and Neon deployment readiness

## Current deployment state

The separate hosted API source is under `backend/`, including a PostgreSQL driver, a manual migration, and authentication/API modules. A `render.yaml` blueprint now describes a free web service with auto-deploy disabled and required values left for secure manual configuration. There is still no configured PostgreSQL database, trusted owner authority, Render or Neon account/service, or deployment. Android client source exists but has no configured endpoint or session. The existing desktop Express server remains loopback-only and is not the Android backend.

Do not expose the desktop listener publicly. Deploy only the separate hosted backend after reviewing its code and migration, installing a verified authority trust key, configuring the exact HTTPS origin/proxy chain, and providing persistent PostgreSQL and server-side Gemini configuration.

Android has source implementations for HTTPS configuration, authenticated conversations and streaming, remote history, enrollment, and remote memory. No origin, trusted authority, or authenticated session is configured, and no hosted service/database exists, so those operations are unavailable here. The health check does not attest Gemini credentials or owner-authority readiness. See [backend-api-auth.md](./backend-api-auth.md), [android-secure-api-contract.md](./android-secure-api-contract.md), and [memory-integration.md](./memory-integration.md) for the current boundaries.

No external service was created or used and no spending occurred (₹0).
Gemini API usage may incur charges depending on the selected account, model, quota, and current Google terms. Before configuring a credential, the owner must check current pricing and enforce a zero-spend policy; do not assume a free quota.

## Environment variables

| Variable | Current meaning | Required now |
| --- | --- | --- |
| `GEMINI_API_KEY` | Server-side fallback for the desktop Gemini provider when no key is stored in the local secrets file. Never send it to Android or return it from configuration APIs. | Optional for desktop startup; required for a live AI connection unless a local desktop key is configured. |
| `MYRAA_DATA_DIR` | Writable directory for local JSON memory, settings, logs, and local desktop `secrets.json`. | Optional; defaults to the process working directory. |
| `DESKTOP_AGENT_URL` | Local Python agent URL; defaults to `http://127.0.0.1:8765`. | Optional for desktop startup; required only if the agent uses a non-default local URL. |
| `MYRAA_AGENT_EXE` | Optional path to the packaged local Python-agent executable. | Optional; Python source fallback exists. |
| `MYRAA_PYTHON` | Optional Python executable path used by the desktop-agent source fallback. | Optional if a Python runtime is available on `PATH`. |
| `NODE_ENV` | Selects Vite development middleware versus the production desktop UI. | Set by the desktop launch/build environment as appropriate. |
| `DATABASE_URL` | PostgreSQL connection URL used only by the separate hosted backend and migration command. The backend enforces TLS certificate verification and rejects SSL override parameters. | Required for backend start/migration; no database URL is configured. |
| `MYRAA_MIGRATION_DATABASE_URL` | Separate privileged connection used only for the explicit manual migration command; never put it in the long-running hosted service. | Required only when applying migrations; no migration role or database is configured. |
| `MYRAA_GEMINI_MODEL` | Explicit model name for hosted text generation. | Required for backend start; model availability is unverified. |
| `MYRAA_OWNER_AUTHORITY_PUBLIC_KEY` | PEM Ed25519 public key for a separately operated owner authority. Its trust must be established out-of-band. | Required for backend start; no trusted authority/key exists here. |
| `MYRAA_RATE_LIMIT_HMAC_KEY` | Base64url-encoded random key of at least 32 bytes for hashed rate-limit subjects. | Required for backend start; no value is configured. |
| `MYRAA_PUBLIC_ORIGIN` | Exact HTTPS origin used for origin restrictions. It is not authentication. | Required for backend start; use only the real hosting URL after service creation. |
| `MYRAA_TRUST_PROXY_HOPS` | Verified TLS-terminating proxy hop count for Express HTTPS detection. | Required for backend start; set only after confirming the deployed network path. |

`PORT` is consumed by the separate hosted backend, but not by desktop `server.ts`, which uses port `3000` and binds to `127.0.0.1`. `DATABASE_URL` and `MYRAA_*` backend variables do not activate or alter the desktop process.

## Future Render constraints

## Production Docker deployment

The hosted backend is deployed from the repository-root `Dockerfile`. It is a multi-stage Node.js 22 LTS image: the build stage runs `npm ci --include=dev`, bundles `backend/server.ts` with the existing `npm run backend:build` script, and the runtime stage installs production dependencies with `npm ci --omit=dev --ignore-scripts` before copying the bundled server. The final container runs as the built-in non-root `node` user and starts `node dist/backend/server.js`.

The Dockerfile does not contain or infer any secrets. Render supplies `PORT` at runtime, and [backend/server.ts](/workspaces/MYRAA-AI-Assistant---Long-term-memory-architecture-upgrade/backend/server.ts) already listens on `0.0.0.0` with that value.

In Render, create a Docker Web Service with:

| Render setting | Required value |
| --- | --- |
| Repository | `mohdrais83/MYRAA-AI-Assistant---Long-term-memory-architecture-upgrade` |
| Runtime | Docker |
| Dockerfile path | `./Dockerfile` |
| Docker context | `.` |
| Branch | Choose explicitly; do not assume deployment from the workspace |
| Plan | Free, if the owner elects to use Render; no spending is authorized here |
| Auto-deploy | Disabled |
| Health check path | `/healthz` |
| Instance/region | Owner-selected |

Configure these environment variables as Render secret or plaintext values only after the real services exist:

| Environment variable | Required configuration |
| --- | --- |
| `DATABASE_URL` | Least-privilege PostgreSQL runtime URL with credentials, verified TLS, no `sslmode` or other SSL override parameters. |
| `GEMINI_API_KEY` | Server-side Gemini credential. Keep this in Render secrets. |
| `MYRAA_GEMINI_MODEL` | An explicitly available Gemini model name. |
| `MYRAA_OWNER_AUTHORITY_PUBLIC_KEY` | PEM-encoded Ed25519 public key from the independently trusted owner authority. |
| `MYRAA_RATE_LIMIT_HMAC_KEY` | Unpadded base64url encoding of at least 32 random bytes. |
| `MYRAA_PUBLIC_ORIGIN` | Exact verified public HTTPS origin, no path or credentials. |
| `MYRAA_TRUST_PROXY_HOPS` | Verified TLS-proxy hop count, from 1 through 5. |
| `PORT` | Supplied by Render. Do not replace it with a local or hard-coded port. |
| `NODE_ENV` | `production` |

Do not configure `MYRAA_MIGRATION_DATABASE_URL` on the running web service. It is only for the separate, explicitly authorized migration process. Apply `backend/migrations/001_secure_backend.sql` manually and review the target database before starting the service.

The separate backend source and `render.yaml` are prepared for a future Render process; no service exists. If later selected, create the Render service manually and choose a currently available free-tier option. Keep automatic deployment disabled; deploy manually only after reviewing source, migration, trust-key provisioning, and proxy settings. Free services can sleep, take time to wake, and have provider-imposed usage limits. Check current Render terms and quotas before configuration; no spending is authorized.

Obtain the real service URL from the Render dashboard only after service creation. Verify its HTTPS certificate and exact origin, then configure the matching trusted origin in the eventual server and Android endpoint configuration. Do not invent, prefill, or trust a guessed hostname.

The hosted process refuses to start unless all runtime variables are configured. Configure them in the provider's secret environment, never source, Android resources, logs, or client configuration. Runtime names are `DATABASE_URL`, `GEMINI_API_KEY`, `MYRAA_GEMINI_MODEL`, `MYRAA_OWNER_AUTHORITY_PUBLIC_KEY`, `MYRAA_RATE_LIMIT_HMAC_KEY`, `MYRAA_PUBLIC_ORIGIN`, `MYRAA_TRUST_PROXY_HOPS`, and platform-supplied `PORT`. The one-off migration command additionally requires `MYRAA_MIGRATION_DATABASE_URL`; do not configure that privileged credential on the running web service. The current app does not supply these values.

When a separate authenticated server exists, its Gemini credential can be entered as the Render secret environment variable `GEMINI_API_KEY`; do not commit it to YAML, source, logs, or Android.

## Future Neon constraints and migration sequence

Neon is optional and is not connected. If later selected, use a currently available free tier, monitor usage limits, and keep the database opt-in. Free-tier usage limits can affect availability; verify current terms before setup. No external spending is authorized.

The source migration is `backend/migrations/001_secure_backend.sql`. It has not been applied and there is no configured Neon/PostgreSQL database. Application startup never applies migrations automatically. After creating a database manually, the owner/operator must:

1. Create the free-tier database manually and store its connection string only in the server's secret configuration.
2. Review migration history and each SQL file in dependency order.
3. Back up any existing data and confirm the target database.
4. Apply each migration manually with a PostgreSQL client, stopping on the first error; record the applied migration identifier out-of-band.
5. Start the hosted service only after the adapter, schema, owner authority, and configuration are reviewed and verified.

The explicit migration command is `npm run backend:migrate -- --apply`, with `DATABASE_URL` set to the least-privilege runtime role and `MYRAA_MIGRATION_DATABASE_URL` set to a distinct migration owner. Both URLs must target the same PostgreSQL host, port, and database name. The runner creates its migration ledger, checks checksums, applies each unapplied SQL file in a transaction, and grants the runtime role required per-table permissions. Review the SQL and confirm the target before running it; no migration command was run for this task.

Do not point the existing local JSON memory store at a cloud database or treat local memories as owner-scoped cloud data. Gemini API usage may incur separate charges; this source has no provider spend/quota enforcement.
