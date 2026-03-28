# SQQ Keycloak Extensions

Custom Keycloak extensions for Super Quinquin sur Deule. Built on Keycloak 26.0.0 with Java 17.

This project packages two extensions into a single JAR deployed as a Keycloak provider:

## Odoo User Storage Provider

A federated user storage SPI that authenticates users against an Odoo instance via JSON-RPC.

**How it works:**

1. When a user logs in, Keycloak delegates credential validation to Odoo's `common.authenticate` endpoint
2. On successful authentication, the user profile (name, email) is fetched from Odoo and imported into Keycloak
3. User attributes are kept in sync on every login — the Odoo `uid` is stored as a custom `odoo_uid` attribute

**Configuration:**

Add a User Federation provider of type `odoo-user-storage` in the Keycloak admin console with:

| Parameter        | Required | Description                                                   |
|------------------|----------|---------------------------------------------------------------|
| `odoo-url`       | Yes      | Base URL of the Odoo server (e.g. `https://odoo.example.com`) |
| `odoo-database`  | Yes      | Name of the Odoo database                                     |
| `admin-login`    | No       | Service account login — enables user lookup by username/email |
| `admin-password` | No       | Service account password                                      |

Without admin credentials, Keycloak cannot search for users proactively — authentication still works, but users must log
in at least once before they appear in Keycloak.

## SuperQuinquin Login Theme

A branded login theme (`surdeule`) that extends the default Keycloak theme with SQQ visual identity.

**Activation:**

In the Keycloak admin console, go to **Realm Settings > Themes** and select `surdeule` as the Login Theme.

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

The CI pipeline (`.github/workflows/build-and-push.yml`) builds and pushes this image to GHCR on every push to `main` or
version tag.
