# Runbook

## Configuration

| Variable | Meaning | Dev value |
|---|---|---|
| `SCENARIO_CONTROL_ENABLED` | Gate 1 | `true` |
| `POD_NAMESPACE` | From the downward API; gate 2 compares it with the allowlist | `scenario-controller-dev` |
| `SCENARIO_NAMESPACE_ALLOWLIST` | Comma-separated namespaces where scenarios may run | `scenario-controller-dev` |
| `SCENARIO_ENVIRONMENT` | Recorded in every audit row | `dev` |
| `ORDER_SERVICE_URL`, `INVENTORY_SERVICE_URL`, `FULFILLMENT_ADAPTER_URL` | Fixed upstream base URLs | cluster DNS, port 8080 |
| `INVENTORY_TOKEN_URL`, `INVENTORY_TOKEN_CLIENT_ID`, `INVENTORY_TOKEN_CLIENT_SECRET` | Client credentials for inventory admin calls; the secret comes from a Secret reference | unset until approved |
| `SCENARIO_UPSTREAM_HOST_ALLOWLIST` | Gate 3: hosts the upstream and token URLs may point at | the three dev services and `keycloak.apps.ocp.box1.org` |
| `SCENARIO_ALLOWED_AZP` | Clients whose tokens may carry roles | `scenario-controller-cli` |
| `SCENARIO_POLL_INTERVAL`, `SCENARIO_UPSTREAM_TIMEOUT`, `SCENARIO_EXPIRY_INTERVAL` | Polling, per-call timeout, expiry sweep | `PT2S`, `PT5S`, `5s` |
| `SCENARIO_MINIMUM_VISIBLE_DELAY`, `SCENARIO_SHIPMENT_SETTLE` | Delay that counts as visible; watch window for a duplicate shipment | `PT60S`, `PT10S` |
| `QUARKUS_DATASOURCE_*` | Audit store | template Secret |

Without the inventory identity the controller starts, its gates and audit work, and seeding and reset report
"Inventory admin identity is not configured" (`RESET_FAILED`).

## Demonstrating

```sh
export TOKEN=...            # from client scenario-controller-cli, role scenario-operator; never commit or print it
scripts/scenario.sh seed    # once; repeats change nothing
scripts/scenario.sh start inventory-contention
scripts/scenario.sh watch scn_...
```

Guided ERP scenarios print the console step in `operatorStep`. Steps the controller cannot observe (arming the crash,
seeing the worker restart) name an `operatorStepId`; confirm them with `scripts/scenario.sh confirm <scenarioRunId> <step>`.
A confirmation is recorded as the operator's attestation, with their identity. Perform it in the ERP console
(`virtctl console legacy-erp -n legacy-erp-dev`); the run verifies the effect through the fulfillment adapter.
The adapter reaches the ERP VM over SFTP (box1-workloads-dev#100). `erp-delayed-processing` and `erp-crash-after-commit` also need the ERP's demo faults enabled (`[demo] faults = true`), which are off in the VM image.

## Recovery

- **A run seems stuck:** `scripts/scenario.sh stop <scenario>`. Stop ends the run and resets it.
- **The controller crashed or was killed mid-run:** at its next start the run becomes `ABORTED_RESTART` and is reset,
  and a reset that a dead process never recorded is completed. While the controller is down nothing it started is
  left enabled: it enables no fault itself (see below), and its orders are ordinary fixture orders.
- **The controller cannot start** (for example the audit database is down: Flyway runs at startup, so the pod
  crash-loops until PostgreSQL is back): no reset can run from the controller. Fixture stock that a run consumed stays
  consumed, which affects only fixture records. Bring the database back; the next start aborts the open run and resets.
  If that is not possible, restock a fixture record by hand through inventory's own route (`POST
  /internal/v1/stock/{siteId}/{sku}/restock`, `inventory-admin`), never in its database.
- **`RESET_GUIDED`:** read `resetDetail` and undo the ERP-side step it names (release a hold, set the delay to 0,
  disarm the crash). **The ERP's demo faults and holds have no expiry of their own** (box1-ops-erp-simulator): the
  controller's TTL ends the run and names the undo step, but only the operator at the ERP console can clear them.
- **`RESET_FAILED`:** usually the inventory identity, inventory itself, or a run's orders still undecided after 30 s.
  Fix that, then `scripts/scenario.sh reset <scenario>` (refused while another run is open).

## Audit database: backup, restore and migrations

- The audit trail lives in `scenario-controller-postgres` (PVC `scenario-controller-postgres-data`). Back it up with
  `pg_dump` from inside the pod (`oc exec` with the platform's normal access, not this service's credential), and keep
  the dump with the lab's other backups. Restoring is `psql` of that dump into an empty database with the controller
  scaled to 0.
- Migrations are additive (`V1` schema, `V2` adds `operator_step_id`). Rolling back the image to a build without `V2`
  works: the old code ignores the extra column. Never delete a migration that has run.
- The audit rows are append-only by trigger, and retention deletes only rows older than 90 days. The database role the
  application uses owns the tables and could drop the triggers; that is the template credential arrangement tracked in
  box1-docs#41.

## Not yet possible

`erp-unavailable`, `broker-outage`, `poison-event`, `bad-artifact-gate` and `red-alert` refuse with their reason
(see the catalog). Each needs a platform or service change first; see box1-docs#19 and #71.
