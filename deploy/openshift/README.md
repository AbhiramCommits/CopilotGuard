# OpenShift deployment

Alternative deployment path to Azure App Service. The CD workflow documents this path
but does not run it by default (see the commented-out `deploy-openshift` job in
`.github/workflows/cd.yml`).

## Prerequisites

- `oc` CLI, logged in to the target cluster.
- A published image in GHCR: `ghcr.io/<owner>/copilotguard:<tag>`.
- Postgres and MongoDB reachable from the cluster (e.g. via a Cloud Provider operator
  or an in-cluster operator such as Crunchy Postgres / Percona MongoDB).

## Deploy with `oc new-app`

```sh
oc new-app ghcr.io/<owner>/copilotguard:<tag> --name copilotguard

oc create secret generic copilotguard-secrets \
  --from-literal=postgres-url='jdbc:postgresql://postgres:5432/copilotguard' \
  --from-literal=mongo-uri='mongodb://mongo:27017/copilotguard' \
  --from-literal=anthropic-api-key='sk-ant-...' \
  --from-literal=github-token='...'

oc set env deployment/copilotguard --from=secret/copilotguard-secrets

oc expose service/copilotguard --port=8080
```

## Deploy from manifests

Edit the image reference in `deployment.yaml`, then:

```sh
oc apply -f deploy/openshift/
```

The manifests create a Deployment (with readiness/liveness probes and resource limits),
a Service, and a TLS edge-terminated Route.
