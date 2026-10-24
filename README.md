# Hospital Management System (HMS)

Spring Boot 3.3 / Java 21 microservices: OAuth2/OIDC + RBAC/ABAC, transactional outbox with Debezium CDC to Kafka,
an orchestrated booking saga with compensation, a CQRS read model, Resilience4j, plus Terraform and Helm for AWS.

> **Verification status.** Written without access to a Java build environment, so **nothing has been compiled or run**.
> What was checked: every Java file's package matches its path, braces balance, every `com.hms.*` import resolves,
> all Terraform parses as HCL, all YAML/JSON parses. Expect to fix a few compile errors on the first
> `mvn package`; build with `-DskipTests` first if a test blocks you. Terraform was not planned or applied.

## Modules

| Module | Port | Responsibility |
|---|---|---|
| `hms-common` | n/a | Outbox, events, idempotent consumers, Kafka DLT handling, JWT resource-server config, retry jitter |
| `api-gateway` | 8080 | Spring Cloud Gateway: JWT validation, Redis token-bucket rate limit, circuit breakers, routing |
| `iam-auth-service` | 8081 | Break-glass declarations (audit events). Tokens come from Keycloak / Cognito |
| `patient-service` | 8082 | Demographics, encrypted national ID, care team, **ABAC `PermissionEvaluator`** |
| `appointment-service` | 8083 | Slots, booking, **saga orchestrator**, timeout sweeper, **CQRS projector + search** (MongoDB) |
| `billing-service` | 8084 | Invoices, insurance check (circuit breaker + retry with full jitter), void compensation |
| `pharmacy-inventory-service` | 8085 | Stock, atomic allocation, release compensation, low-stock alerts |
| `notification-service` | 8086 | Email (SES), SMS and push (SNS), or log-only mode; contact directory from patient events |

Other folders: `infra/local` (docker-compose, Debezium connector template, demo script), `infra/keycloak`
(dev realm with one user per role), `infra/terraform` (VPC, edge, EKS, data tier), `deploy` (Helm chart + values),
`docs` (the design write-ups the code was derived from).

## Run locally

Prerequisites: JDK 21, Maven 3.9+, Docker.

```bash
docker compose -f infra/local/docker-compose.yml up -d     # postgres, kafka, connect, redis, mongo, keycloak
mvn clean package -DskipTests                               # builds every module

# start each service (separate terminals); pharmacy uses the dev profile for seed drugs
mvn -pl iam-auth-service            spring-boot:run
mvn -pl patient-service             spring-boot:run
mvn -pl appointment-service         spring-boot:run
mvn -pl billing-service             spring-boot:run
SPRING_PROFILES_ACTIVE=dev mvn -pl pharmacy-inventory-service spring-boot:run
mvn -pl notification-service        spring-boot:run
mvn -pl api-gateway                 spring-boot:run

# once the services have started (Flyway has created each outbox table + publication):
infra/local/register-connectors.sh                          # Debezium outbox connectors
infra/local/demo.sh                                         # register patient, book 2 appointments, search, ABAC check
```

Dev logins (password = username): `admin1`, `doctor1`, `nurse1`, `reception1`, `billing1`, `pharmacist1`, `patient1`.
Token: `curl -d client_id=hms-web -d grant_type=password -d username=doctor1 -d password=doctor1 http://localhost:8180/realms/hms/protocol/openid-connect/token`

Tests: `mvn -pl patient-service test` runs the ABAC policy matrix (about 40 named cases plus invariants over roughly 4,000 combinations).

Container image for any module: `docker build --build-arg MODULE=patient-service -t hms/patient-service .`

## How the pieces fit

1. A service changes state and publishes a `DomainEvent` in the same `@Transactional` method. `OutboxEventListener`
   writes the row to `outbox` before commit (so state and message commit atomically).
2. Debezium tails the Postgres WAL for each `outbox` table and routes rows to `hms.<aggregatetype>.events`
   (key = aggregate id; headers `eventType`, `messageId`).
3. `appointment-service` books in one transaction: appointment + saga row + slot lock (`SKIP LOCKED`) + a `CreateInvoice`
   command. Billing replies, then pharmacy (if drugs were prescribed), then the appointment is confirmed.
   On failure it compensates in reverse: void invoice, release slot. A sweeper compensates sagas that time out.
4. Consumers dedupe on `messageId` inside their own transaction (`processed_message`), so redelivery is harmless.
   Poison messages go to `<topic>.DLT` after exponential backoff.
5. The projector turns appointment events into a MongoDB document; `GET /api/appointments/search` reads only that.

## Differences from the design documents in `docs/`

- Read model is **MongoDB** (the brief allows it), not OpenSearch. `infra/terraform/redis_opensearch.tf` still provisions
  OpenSearch; swap it for DocumentDB (Terraform not included) or delete it.
- Prescription items travel **inside the booking request** (`items: [{drugId, quantity}]`), so there is no synchronous
  prescription lookup and no `PrescriptionClient`.
- No service-discovery client: the gateway uses plain URLs (`PATIENT_URL` etc.). In Kubernetes these are Service names.
- Outbox `payload` is `TEXT` (not `jsonb`) and rows are removed by a scheduled cleaner, because deleting a row in the
  same transaction that inserts it would cancel the insert.
- `ROLE_PHARMACIST` was added to the six roles in the brief, because pharmacy stock needs an owner.
- The ABAC evaluator lives in `patient-service`; the other services use role checks plus ownership checks for patients.
- MSK IAM authentication is a Spring profile (`msk-iam`), see `hms-common/src/main/resources/application-msk-iam.yml`.

## Known gaps (read before production)

1. **Break-glass is disabled by default** (`hms.security.break-glass-enabled=false`). The signal is an `X-Break-Glass`
   request header that any caller can set. Replace it with a short-lived signed claim issued after a justification
   (`iam-auth-service` already records the justification as an audit event) before enabling it.
2. **Unverified code:** see the status note at the top. Hibernate runs with `ddl-auto: validate`; if validation complains
   about a column type, fix the migration or temporarily set `none`.
3. Only the ABAC policy has tests. Saga, outbox, Debezium and gateway tests are described in `docs/06-tests.md`.
4. Terraform gaps: ALB access-log bucket policy, ACM DNS-validation records for the regional certificate, DocumentDB,
   second region for failover, Route 53 health-check/origin-header issue (see `docs/07-changelog.md`).
5. `hms.crypto.key` has a development default. Inject it from KMS / Secrets Manager.
6. The Keycloak realm is for development only (`sslRequired: none`, `redirectUris: *`, passwords equal usernames).
7. User provisioning is done in the identity provider; there is no admin API in `iam-auth-service`.
