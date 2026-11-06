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
| `SCENARIO_ALLOWED_AZP` | Clients whose tokens may carry roles | `scenario-controller` |
| `QUARKUS_DATASOURCE_*` | Audit store | template Secret |

Without the inventory identity the controller starts, its gates and audit work, and seeding and reset report
"Inventory admin identity is not configured" (`RESET_FAILED`).

## Demonstrating

```sh
export TOKEN=...            # client scenario-controller, role scenario-operator; never commit or print it
scripts/scenario.sh seed    # once; repeats change nothing
scripts/scenario.sh start inventory-contention
scripts/scenario.sh watch scn_...
```

Guided ERP scenarios print the console step in `operatorStep`. Perform it in the ERP console
(`virtctl console legacy-erp -n legacy-erp-dev`); the run verifies the effect through the fulfillment adapter.
The adapter reaches the ERP VM over SFTP (box1-workloads-dev#100). `erp-delayed-processing` and `erp-crash-after-commit` also need the ERP's demo faults enabled (`[demo] faults = true`), which are off in the VM image.

## Recovery

- **A run seems stuck:** `scripts/scenario.sh stop <scenario>`. Stop ends the run and resets it.
- **The controller crashed mid-run:** on restart the run becomes `ABORTED_RESTART` and is reset automatically. If
  the pod cannot start, the run still ends at its TTL once the pod is back (sweep), and fixture stock can be restored by
  hand with `scripts/scenario.sh reset <scenario>`.
- **`RESET_GUIDED`:** read `resetDetail` and undo the ERP-side step it names (release a hold, set the delay to 0,
  disarm the crash).
- **`RESET_FAILED`:** usually the inventory identity or inventory itself. Fix that, then `reset <scenario>`.
- **No fault the controller enables can outlive its TTL:** the controller itself enables none; it only places orders
  and restocks fixture records. ERP faults belong to the ERP console and are undone there.

## Not yet possible

`erp-unavailable`, `broker-outage`, `poison-event`, `bad-artifact-gate` and `red-alert` refuse with their reason
(see the catalog). Each needs a platform or service change first; see box1-docs#19 and #71.
