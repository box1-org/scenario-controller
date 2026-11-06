# Architecture

## Boundary

The controller orchestrates other services through their own public APIs. It owns only its runs and audit trail
(PostgreSQL). It reads no other service's tables, publishes no events, holds no Kubernetes credentials (the pod has
`automountServiceAccountToken: false`), and never builds a URL, command or path from caller input. A scenario the
platform cannot support is a catalog entry marked `NOT_YET_POSSIBLE`, with its reason, and no code.

| Upstream | Calls | Identity |
|---|---|---|
| order-service | `POST /api/v1/orders`, `GET /api/v1/orders/{id}`, `/timeline` | None: the service has no API authentication (box1-docs#40) |
| inventory-service | `GET /api/v1/inventory`; `PUT /internal/v1/sites/{siteId}`, `PUT /internal/v1/stock/{siteId}/{sku}`, `POST …/restock` | Reads: read policy `permit`. Admin: client-credentials token with audience `inventory-service` and role `inventory-admin` |
| fulfillment-adapter | `GET /api/fulfillments/{id}`, `GET /q/metrics` | Read policy `permit` |

Base URLs are fixed per service in configuration (`ORDER_SERVICE_URL`, `INVENTORY_SERVICE_URL`,
`FULFILLMENT_ADAPTER_URL`). Every call carries `X-Correlation-Id: scenario-<scenarioRunId>` and W3C `traceparent`.

## Runs

```text
start ─▶ RUNNING ─▶ AWAITING_OPERATOR (guided step shown) ─▶ VERIFIED | NOT_VERIFIED | FAILED
            │                                                   ▲
            └── stop ─▶ STOPPED   TTL sweep ─▶ EXPIRED   restart ─▶ ABORTED_RESTART
every ending ─▶ reset ─▶ RESET_OK | RESET_GUIDED | RESET_FAILED
```

- **One open run** at a time, enforced by a unique partial index on `scenario_run`, and **one replica** with the
  `Recreate` strategy, so a new pod never runs beside the old one (a new pod aborts any open run at startup).
- **Gates:** control enabled; `POD_NAMESPACE` (environment only) on the namespace allowlist; every upstream and token
  URL host on the upstream allowlist, so configuration alone cannot aim the controller at another environment.
- **TTL:** the catalog sets a default and a maximum (at most 3600 s); a start may only lower it. The run's own waits
  end at the TTL. A sweep every 5 s ends any run 30 s past its TTL whose thread did not end it.
- **Restart:** at startup, a run left open by a previous process becomes `ABORTED_RESTART` and is reset. Nothing
  resumes silently. If the database is down at startup, the sweep retries.
- **Verification** reads business state only: order state and timeline, inventory stock, the adapter's status (whose
  `orderId` must match). The fulfillment request ID comes from order-service's internal `ful_<order suffix>` naming,
  because no API publishes the link (box1-docs#18 G-1); a mismatch fails the run.
- **Guided ERP steps:** the ERP has no HTTP API and the controller never adds one. The run shows the console keystrokes,
  waits for the effect through the adapter, and reports `NOT_VERIFIED` if it is not observed before the TTL. Effects
  the controller cannot observe at all (an armed crash, a worker restart) need an operator confirmation, audited as an
  attestation. ERP-side changes end in `RESET_GUIDED` with the undo step: the ERP's faults and holds have no expiry of
  their own, so the controller's TTL bounds the run, not the ERP setting.
- **Exactly once:** after a first shipment the run watches for `SCENARIO_SHIPMENT_SETTLE`, then requires one
  `shipment.confirmed.v1` event (by event ID) on the order's timeline and an unchanged adapter shipment ID.
- **Reset after a run** first waits up to 30 s for the run's orders to be decided, so restored stock is never taken by
  the run's own in-flight order. The contention scenario orders everything available, so its race stays exact even if
  available stock ends above one (reset only adds).

## Fixtures

Starfleet Feline Supply: four sites (`site_spacedock`, `site_ds9`, `site_utopia_planitia`, `site_neutral_zone`
UNAVAILABLE), five customers (`cust_picard`, `cust_data`, `cust_janeway`, `cust_sisko`, `cust_kirk`), nine `CAT-*`
SKUs and two asset IDs. Customers need no seeding (order-service checks only their format). Opening stock: 500 of every
SKU at Spacedock, 2000 at Utopia Planitia, and at DS9 one `CAT-TRIBBLE-TOY` plus 20 each of three others.

Seeding uses inventory's create-once routes; a repeat changes nothing. Reset restocks a fixture record by the
difference between its opening quantity and current availability, under a reset-scoped idempotency key. It never
releases a reservation, cancels an order or reduces stock. Orders are additive: a fresh demo is a fresh run.

## Audit

`scenario_run` records actor (the token's `sub`, followed by `preferred_username` for readers; the controller's own
rows use `system:scenario-controller`), environment, namespace, mode, TTL, impact (fixture, order and fulfillment
IDs), status, detail and reset result. `scenario_audit` is append-only (database triggers refuse update and truncate)
and records every start, refusal, operator step, operator confirmation, result and reset. Retention is 90 days by
database time: a daily job deletes older rows, which is the only delete the trigger permits (any delete of rows that old
passes the trigger; there is no separate SQL function). 401 and 403 refusals happen before the controller sees the
request and are not in the audit trail; they are in the access log. API views add a display-only stardate,
`1000 × (year − 2323) + 1000 × fraction of the UTC year`, beside the authoritative ISO 8601 timestamp.

## Observability

JSON logs carry `scenarioRunId` and `correlationId`. Metrics: `scenario_runs_total{scenario,outcome}`,
`scenario_refusals_total{action}`, `scenario_active_runs`. Readiness depends on the audit store only.
