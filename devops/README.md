# projectr-x — Kubernetes manifests

Kustomize-based deployment for the projectr-x stack on k3s.

## Layout

```
devops/
├── base/                  # stateless app: services + redis + frontend
└── overlays/
    └── dev/               # env: namespace=crypto-radar, CNPG DBs, ingress, backups
```

The **base** is environment-agnostic — no namespace, no env-specific config.
Each overlay sets `namespace:` and layers in env-specific resources (databases,
backup objectstore, ingress hostnames, replica counts).

To add a new environment later: copy `overlays/dev/` to e.g. `overlays/prod/`,
adjust the namespace, replica counts, storage sizes, and backup bucket.

## Prerequisites on the cluster

| Component | Why |
|---|---|
| [CloudNativePG operator](https://cloudnative-pg.io/) | runs the postgres `Cluster` CRDs |
| [CNPG Barman Cloud plugin](https://github.com/cloudnative-pg/plugin-barman-cloud) | WAL archiving + scheduled backups |
| [Longhorn](https://longhorn.io/) (or any RWO storage class) | PVC backing for redis + DB clusters |
| Traefik (k3s default) | ingress controller |

## Deploy

```bash
# 1. Apply secrets out-of-band (NEVER committed). Copy the template:
cp overlays/dev/secrets.example.yaml overlays/dev/secrets.yaml
# Edit secrets.yaml to fill in real values, then:
kubectl apply -f overlays/dev/secrets.yaml

# 2. Apply the overlay (creates namespace + everything else)
kubectl apply -k overlays/dev
```

Subsequent updates:

```bash
kubectl apply -k overlays/dev
```

## Secrets — handled out-of-band

Because this repo is **public**, no secret values are tracked in git.
The `secrets.example.yaml` file is a template documenting what Secret
resources you need to create. Concrete steps:

1. `cp overlays/dev/secrets.example.yaml overlays/dev/secrets.yaml`
2. Fill in `WHALE_ALERT_API_KEY`, `GEMINI_API_KEY`, S3 backup credentials
3. `kubectl apply -f overlays/dev/secrets.yaml`
4. `secrets.yaml` is in `.gitignore` and will not be committed.

**Database credentials are never written by you** — CNPG generates random
passwords at bootstrap and stores them in:
- `marketdata-db-app` (TimescaleDB)
- `cryptonews-db-app` (Postgres)

Service Deployments reference these auto-generated Secrets by name, so the
chain is fully automated.

## Image registry

Default image references in base: `ghcr.io/stukans/projectr-x-<service>:latest`.
Replace with your actual registry/tags before deploying. If your registry is
private, add the pull secret name to `base/serviceaccount/serviceaccount.yaml`.

## Database init SQL

The TimescaleDB and Postgres initialization SQL is embedded as ConfigMaps in
the overlay (`*-db-init-sql.yaml`). The original copies live at
`<repo-root>/db/init/*.sql` and are also mounted by `docker-compose.yml`. If
you change one, change both — or refactor to a single source of truth.

The TimescaleDB extension is created via the cluster's `postInitTemplateSQL`
(superuser → template1 → inherited into the application database). The init
SQL ConfigMap therefore does **not** contain a `CREATE EXTENSION timescaledb`
line — that would fail because the application user lacks superuser rights.

## Notes on the TimescaleDB image

CNPG requires a postgres image that bundles the timescaledb extension binary
**and** complies with CNPG's image contract. The default in
`marketdata-db-cluster.yaml` is a community image
(`ghcr.io/imusmanmalik/timescaledb-postgis`). Swap to your preferred image if
you maintain your own.
