# scenario-controller

Box1's safe demo orchestration API ([box1-docs#19](https://github.com/box1-org/box1-docs/issues/19)): a declarative
catalog of named, bounded and audited demo scenarios, and the canonical demo fixture set, **Starfleet Feline Supply**.
It is not a command runner: there is no exec, no shell, no Kubernetes write and no caller-supplied URL.

Owner: `user:default/mike`. Quarkus 3.40.1.1, Java 21, Maven 3.9.16. PostgreSQL holds runs and the audit trail.
Kafka was selected at scaffold time but is not used: the controller produces and consumes no events.

## What it does

| Scenario | Title | Mechanism | Status |
|---|---|---|---|
| `baseline-order` | Make it so | Service API; ERP shipping is a guided console step | Available |
| `inventory-contention` | The Trouble with Tribbles | Service API: two simultaneous orders for the last unit | Available |
| `erp-manual-hold` | Hold that order, Number One | Guided ERP console step, verified through the fulfillment adapter | Available |
| `erp-delayed-processing` | Impulse power only | Guided ERP console step (needs ERP demo faults) | Available |
| `erp-crash-after-commit` | Warp core breach (one-shot, contained) | Guided ERP console step (needs ERP demo faults) | Available |
| `erp-unavailable` | The ERP has cloaked | VM-level | Not yet possible |
| `broker-outage` | Subspace interference | Platform-level | Not yet possible |
| `poison-event` | Q was here | Platform-level | Not yet possible |
| `bad-artifact-gate` | Unsigned shuttlecraft denied docking | Platform-level | Not yet possible |
| `red-alert` | Red alert | Asset service control API | Not yet possible |

The catalog is `src/main/resources/catalog/scenarios.yaml`; each entry gives prerequisites, fixture IDs, durations,
expected observation, reset action and operator steps. A "not yet possible" entry carries its reason and has no code.

- **Gates:** `SCENARIO_CONTROL_ENABLED=true`, the pod's namespace (`POD_NAMESPACE`, environment only) in
  `SCENARIO_NAMESPACE_ALLOWLIST`, and every upstream host in `SCENARIO_UPSTREAM_HOST_ALLOWLIST`. Anything else refuses
  every action, and the refusal is audited.
- **Access:** bearer tokens from realm `box1`, audience `scenario-controller`, `azp` in `SCENARIO_ALLOWED_AZP`, roles
  `scenario-viewer` (read) and `scenario-operator` (start, stop, reset, seed) from `resource_access.scenario-controller`.
  Two clients are allowed: `scenario-controller-cli` (the CLI) and `operations-console` (its Demo control page, which
  forwards the signed-in person's token).
- **Runs:** one at a time; each has a `scenarioRunId` (`scn_…`), correlation ID `scenario-<scenarioRunId>`, order
  idempotency keys `stardock-<scenarioRunId>-<n>`, a TTL, and a reset when it ends. A guided step not observed before
  the TTL ends the run `NOT_VERIFIED`, never as success.
- **Fixtures:** seeded only through inventory's create-once routes, idempotently; reset only adds stock back up to the
  opening quantity. Nothing outside the fixture set is changed, and nothing is deleted.

API: `GET /internal/v1/scenarios`, `POST /internal/v1/scenarios/{name}/start|stop|reset`, `GET /internal/v1/runs`,
`GET /internal/v1/runs/{scenarioRunId}`, `GET /internal/v1/audit`, `GET /internal/v1/fixtures`,
`POST /internal/v1/fixtures/seed`, `POST /internal/v1/runs/{scenarioRunId}/confirm`. The served OpenAPI at `/q/openapi` is the contract. CLI: `scripts/scenario.sh`.

See [docs/architecture.md](docs/architecture.md) and [docs/runbook.md](docs/runbook.md).

## Local development

Install JDK 21 and use the included Maven wrapper:

```sh
./mvnw quarkus:dev
./mvnw verify -DskipITs=false -Dgit.ref="$(git rev-parse HEAD)"
# Container-backed tests (PostgreSQL in a container, fake upstream services):
DOCKER_HOST=unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}') \
  TESTCONTAINERS_RYUK_DISABLED=true ./mvnw -Pcontainers verify
curl http://localhost:8080/api/greetings?name=Ada
curl http://localhost:8080/api/info
```

Verification runs unit tests, HTTP tests, packaged JVM integration tests,
Checkstyle, and SpotBugs/FindSecBugs. `/api/info` includes the immutable Git SHA
embedded in the image; local builds default to `unknown` unless supplied above.
Java package `org.box1` is deliberately stable across generated services.

## First deployment

Create the service in Developer Hub. Its task tracks repository creation,
validated development onboarding, platform readiness, initial CI, image promotion,
and the verified first deployment. When the task completes, its output links to
source, catalog, initial pipeline, Argo CD, and the running service. No manual
onboarding merge or extra source commit is required.

If onboarding fails, the task reports the failed state and diagnostic link.
Submit the same name, owner, visibility, and description to resume the recorded
workflow. The repository and development resources are reused. A concurrent
request for the same service is rejected while the first task holds its lease.
The initial CI run is named `onb-scenario-controller-<revision>`; a retried failed
build has a numbered suffix. Completed builds are reused.

Route: https://scenario-controller-dev.apps.ocp.box1.org

## Delivery and configuration

PRs validate without registry credentials. Main releases use centrally managed
Tasks from `box1-gitops/platform/pipelines/tasks`, resolved from `box1-ci`.
GitHub checks link to cluster PipelineRuns named `scenario-controller-validate-*`
and `scenario-controller-release-*`. Pipelines as Code and Developer Hub use
separate GitHub Apps, both installed for all `box1-org` repositories.

Jib creates JVM images from a digest-pinned UBI 9 / Java 21 runtime without a
Docker daemon. Images use `registry.apps.ocp.box1.org/box1-ci/scenario-controller:<SHA>`;
OpenShift pulls by digest through the internal registry service.

After onboarding, the GitOps repository is the authoritative deployment source.
Change the Route, probes, resources, or
`GREETING_PREFIX` there. The default smoke contract expects `Hello from box1`.
The ConfigMap also sets JVM container memory limits. The pod has no Kubernetes API token; its only credential besides
the database is the inventory admin client secret, from Secret `scenario-controller-inventory-token`.

Endpoints: `/api/greetings`, `/api/info`, `/q/health/live`, `/q/health/ready`,
`/q/health/started`, `/q/openapi`, `/q/metrics`.

The development promotion policy is automated after checks. Other environments
need explicit platform configuration and approval. Static analysis is included, and the release runs
`box1-security-gates` in `gate` mode (SBOM and RHACS policy check).

## API documentation and endpoint testing

Developer Hub populates **Provides APIs** with `scenario-controller-api`.
Open the API's **Definition** tab to browse the deployed OpenAPI contract and use
**Try it out**. The catalog reads `https://scenario-controller-dev.apps.ocp.box1.org/q/openapi`,
so the API entity becomes available after the first successful deployment.
Swagger UI is also available at `https://scenario-controller-dev.apps.ocp.box1.org/q/swagger-ui/`.
The production contract targets the public HTTPS Route; override `PUBLIC_API_URL`
when deploying behind another public address. CORS allows the Developer Hub
origin to make tester requests without cookies. API authentication, if added,
should be declared in the OpenAPI contract and supplied through **Authorize**.

## Documentation

Developer Hub publishes `docs/` on the service's **Docs** tab using TechDocs.
List new pages in `mkdocs.yml`.
