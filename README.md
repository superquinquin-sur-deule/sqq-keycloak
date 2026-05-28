# SQQ Keycloak Extensions

Custom Keycloak extensions for Super Quinquin sur Deule. Built on Keycloak 26.0.0 with Java 17.

This project packages two extensions into a single JAR deployed as a Keycloak provider:

## Odoo User Storage Provider

A federated user storage SPI (`odoo-user-storage`) that treats an Odoo instance as the source of truth for users,
credentials, and roles. All communication with Odoo goes through JSON-RPC (`{odoo-url}/jsonrpc`).

### SPI interfaces implemented

| Interface                  | Purpose                                                                  |
|----------------------------|--------------------------------------------------------------------------|
| `UserStorageProvider`      | Base federated storage integration                                       |
| `CredentialInputValidator` | Validates passwords against Odoo on every login                          |
| `UserLookupProvider`       | Looks up users in Odoo by username / email                               |
| `UserQueryProvider`        | Powers searches from the admin console                                   |
| `UserCountMethodsProvider` | Reports user counts                                                      |
| `ImportSynchronization`    | Exposes full and incremental sync jobs that the Keycloak scheduler runs  |

### Authentication

On login, Keycloak delegates password validation to Odoo's `common.authenticate` endpoint. Passwords are **never stored
in Keycloak** — every login round-trips to Odoo. A user must have a non-empty email and password for validation to be
attempted.

### Profile import & per-login sync

The first time a user authenticates, Keycloak imports them; on subsequent logins the local record is refreshed from
Odoo. The following fields are synchronized:

- **Username** — taken from Odoo's `res.users.email` (users without an email are skipped)
- **Email** — marked as verified automatically
- **First / last name** — parsed from the Odoo user name (supports both `Last, First` and space-separated forms)
- **Enabled flag** — always `true` for synced users; only the post-sync deactivation pass disables users that have
  disappeared from Odoo
- **Custom attributes** — `odoo_uid` (the `res.users.id`, used as the federation key) and `odoo_roles` (CSV snapshot
  of the roles granted by the last sync, used to revoke obsolete role mappings)
- **Federation link** — set to this component so Keycloak knows the record is owned by the provider

### Role synchronization

Every Odoo group attached to a user is mapped one-to-one onto a Keycloak realm role with the same name. There is no
hard-coded whitelist: the provider creates the realm role on demand if it doesn't exist yet.

To revoke without touching unrelated roles, the set of roles granted by the previous sync is stored on the user as the
`odoo_roles` attribute. On each sync the provider:

1. Computes `desired` = current Odoo group names for that user.
2. For each role in `previous - desired`, revokes the role mapping (only if it actually exists on the user).
3. For each role in `desired`, creates the realm role if missing and grants it.
4. Overwrites `odoo_roles` with the new set.

Realm roles granted manually in Keycloak — or technical roles such as `default-roles-*` and `offline_access` — are
never touched as long as they were not previously granted by this provider.

### Periodic full sync

When the provider is scheduled from the admin console, the sync job:

1. Paginates through all `res.users` records (Odoo excludes archived users by default) in batches of 100
2. Collects the union of `groups_id` across the batch and resolves names with a single `res.groups.read` call
3. Upserts each user and applies role sync
4. After a successful pass, disables any local user whose `odoo_uid` is no longer present

If the fetch or upsert phase fails, the deactivation pass is skipped to avoid cascading disables from a partial view of
Odoo. Added / updated / failed counts and elapsed time are logged at the end of each run.

`syncSince` is also implemented and runs the same full pass (Odoo is the source of truth, so an incremental delta is
not tracked separately).

### User lookup

`getUserByUsername` and `getUserByEmail` both search `res.users` by `email`, since the Keycloak username is the user's
email. `getUserById` returns `null` — users are always resolved through the federated storage layer. All lookups require
admin credentials (see below).

### Configuration

Add a User Federation provider of type `odoo-user-storage` in the Keycloak admin console with:

| Parameter        | Type     | Required | Description                                                          |
|------------------|----------|----------|----------------------------------------------------------------------|
| `odoo-url`       | string   | Yes      | Base URL of the Odoo server. `https://` is auto-prepended if missing |
| `odoo-database`  | string   | Yes      | Name of the Odoo database                                            |
| `admin-login`    | string   | No       | Service account login — enables lookup, full sync, and role sync     |
| `admin-password` | password | No       | Service account password                                             |

### With vs without admin credentials

| Capability                    | No admin creds             | With admin creds |
|-------------------------------|----------------------------|------------------|
| Login & password validation   | ✓                          | ✓                |
| Profile import on first login | ✓                          | ✓                |
| Lookup by username / email    | ✗ (returns `null`)         | ✓                |
| Periodic full sync            | ✗ (aborted with a warning) | ✓                |
| Role synchronization          | ✗                          | ✓                |

## SuperQuinquin Login Theme

A branded login theme (`surdeule`) that extends the default Keycloak theme with SQQ visual identity.

**Activation:** in the Keycloak admin console, go to **Realm Settings > Themes** and select `surdeule` as the Login
Theme.

## Building

```bash
mvn -B package
```

This produces `target/sqq-keycloak-extensions-1.0.0-SNAPSHOT.jar`.

## Running locally

```bash
mvn -B package && docker compose up --build
```

Keycloak will be available at `http://localhost:8080` with admin credentials `admin`/`admin`, backed by a PostgreSQL
database.

## Docker image

The Dockerfile produces a custom Keycloak image with the extension JAR baked in:

```dockerfile
FROM quay.io/keycloak/keycloak:26.0
# Extension JAR is copied into /opt/keycloak/providers/
```

The CI pipeline (`.github/workflows/build-and-push.yml`) builds and pushes this image to GHCR on every push to `main`
or version tag.