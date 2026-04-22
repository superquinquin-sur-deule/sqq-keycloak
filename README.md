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

- **Username** — taken from Odoo's `barcode_base` (users without a barcode are skipped)
- **Email** — marked as verified automatically
- **First / last name** — parsed from the Odoo partner name (supports both `Last, First` and space-separated forms)
- **Enabled flag** — mirrors Odoo's `is_member` (non-members are disabled, not deleted)
- **Custom attributes** — `odoo_uid`, `odoo_partner_id`, `odoo_is_member`
- **Federation link** — set to this component so Keycloak knows the record is owned by the provider

### Role synchronization

Odoo groups are mapped one-to-one onto Keycloak realm roles by name. Only a fixed whitelist of roles is managed by the
provider; every other Keycloak role is left untouched.

Managed roles:

> Cashier, Purchaser, Purchase Manager, Inventory Manager, Teamleader, Member Manager, Accountant, Foodcoop Admin,
> Member, BDMLecture, BDMPresence, BDMSaisie, Subscription, Communications Officer, Communications Manager, Welcome
> meeting team, Member accountant, Point of Sales Manager, BadgeReader, Staff

For each synced user, the provider fetches `res.users.groups_id`, resolves group names via `res.groups`, grants any
missing managed role (creating the realm role if needed), and removes any managed role the user no longer has in Odoo.

### Periodic full sync

When the provider is scheduled from the admin console, the sync job:

1. Paginates through all `res.partner` records with `is_member = true` in batches of 100
2. Fetches roles for the batch in a single call
3. Upserts each user and applies role sync
4. After a successful pass, disables any local user whose Odoo partner is no longer a member

If the fetch or upsert phase fails, the deactivation pass is skipped to avoid cascading disables from a partial view of
Odoo. Added / updated / failed counts and elapsed time are logged at the end of each run.

`syncSince` is also implemented and runs the same full pass (Odoo is the source of truth, so an incremental delta is
not tracked separately).

### User lookup

`getUserByUsername` and `getUserByEmail` search Odoo for a partner that has a linked `res.users` record, matching on
`barcode_base` and `email` respectively. `getUserById` returns `null` — users are always resolved through the federated
storage layer. All lookups require admin credentials (see below).

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