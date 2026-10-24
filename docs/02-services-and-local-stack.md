# HMS Part 2: Remaining Bounded Contexts, Discovery, Local Stack

Covers `pharmacy-inventory-service`, `notification-service`, `patient-service`, `iam-auth-service`, service discovery, and a local docker-compose. Builds on `hms-architecture.md` (outbox, `DomainEvent`, `processed_message`, Resilience4j setup are reused).

---

## 1. pharmacy-inventory-service

Participates in the booking Saga: consumes `AllocateInventory`, replies `InventoryAllocated` / `AllocationFailed`.

### Schema

```sql
CREATE TABLE drug_stock (
  drug_id     UUID PRIMARY KEY,
  name        VARCHAR(200) NOT NULL,
  on_hand     INT NOT NULL CHECK (on_hand >= 0),
  reserved    INT NOT NULL DEFAULT 0 CHECK (reserved >= 0),
  reorder_at  INT NOT NULL DEFAULT 20,
  version     BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE allocation (
  appointment_id UUID NOT NULL,
  drug_id        UUID NOT NULL,
  quantity       INT  NOT NULL,
  status         VARCHAR(20) NOT NULL,   -- ALLOCATED | RELEASED | DISPENSED
  PRIMARY KEY (appointment_id, drug_id)
);
```

### Atomic allocation (no oversell)

```java
public interface DrugStockRepository extends JpaRepository<DrugStock, UUID> {
  /** Single atomic statement: succeeds only if enough free stock exists. */
  @Modifying
  @Query("""
      update DrugStock s set s.reserved = s.reserved + :qty
      where s.drugId = :drugId and (s.onHand - s.reserved) >= :qty
      """)
  int tryReserve(@Param("drugId") UUID drugId, @Param("qty") int qty);

  @Modifying
  @Query("update DrugStock s set s.reserved = s.reserved - :qty where s.drugId = :drugId and s.reserved >= :qty")
  int release(@Param("drugId") UUID drugId, @Param("qty") int qty);

  @Query("select (s.onHand - s.reserved) from DrugStock s where s.drugId = :drugId")
  int available(@Param("drugId") UUID drugId);
}

@Service @RequiredArgsConstructor
class AllocationService {
  private final DrugStockRepository stock;
  private final AllocationRepository allocations;
  private final PrescriptionClient prescriptions;     // resilient REST client
  private final ApplicationEventPublisher events;

  @Transactional
  public void allocate(AllocateInventory cmd) {
    if (allocations.existsByAppointmentId(cmd.appointmentId())) return;   // idempotent
    var items = prescriptions.items(cmd.prescriptionId());

    for (var it : items) {                                  // 1. check everything first
      if (stock.available(it.drugId()) < it.quantity()) {
        events.publishEvent(new DomainEvent("pharmacy", cmd.appointmentId().toString(),
            "AllocationFailed", new AllocationFailed(cmd.appointmentId(), "insufficient stock: " + it.drugId())));
        return;                                             // nothing reserved yet, so this commits normally
      }
    }
    for (var it : items) {                                  // 2. reserve; the atomic guard still protects races
      if (stock.tryReserve(it.drugId(), it.quantity()) == 0)
        throw new ConcurrentStockChangeException(it.drugId());   // rolls back partial reservations; listener retries
      allocations.save(new Allocation(cmd.appointmentId(), it.drugId(), it.quantity(), "ALLOCATED"));
    }
    events.publishEvent(new DomainEvent("pharmacy", cmd.appointmentId().toString(),
        "InventoryAllocated", new InventoryAllocated(cmd.appointmentId())));
  }

  /** Compensation: give reserved stock back. No-op if already released. */
  @Transactional
  public void release(UUID appointmentId) {
    allocations.findByAppointmentIdAndStatus(appointmentId, "ALLOCATED").forEach(a -> {
      stock.release(a.getDrugId(), a.getQuantity());
      a.setStatus("RELEASED");
    });
  }
}
```

The listener retries `ConcurrentStockChangeException` (use a `DefaultErrorHandler` with a small backoff); on the retry the check pass re-reads stock and emits `AllocationFailed` if it is really gone.

### Command listener and compensation

```java
@Component @RequiredArgsConstructor
class PharmacyCommandListener {
  private final AllocationService service;
  private final Deduper dedupe;
  private final ObjectMapper mapper;

  @KafkaListener(topics = "hms.pharmacy-commands.events", groupId = "pharmacy")
  @Transactional
  void on(ConsumerRecord<String, String> r, @Header("messageId") String id,
          @Header("eventType") String type) throws Exception {
    if (!dedupe.firstTime(id, "pharmacy")) return;
    switch (type) {
      case "AllocateInventory" -> service.allocate(mapper.readValue(r.value(), AllocateInventory.class));
      case "ReleaseInventory"  -> service.release(UUID.fromString(r.key()));
    }
  }
}
```

### Low-stock events (feed notification-service)

```java
@Scheduled(fixedDelay = 300_000)
@SchedulerLock(name = "lowStockScan", lockAtMostFor = "4m")      // ShedLock: one pod runs it
@Transactional
void scanLowStock() {
  stockRepo.findBelowReorderLevel().forEach(s ->
    events.publishEvent(new DomainEvent("pharmacy", s.getDrugId().toString(),
        "LowStockDetected", new LowStock(s.getDrugId(), s.getName(), s.available()))));
}
```

---

## 2. notification-service

Pure consumer. Subscribes to domain topics, resolves recipient and channel preferences, sends through AWS SES / SNS, with per-channel resilience and dedupe.

### Event to notification mapping

```java
@Component @RequiredArgsConstructor
class NotificationListener {
  private final NotificationRouter router;
  private final Deduper dedupe;

  @KafkaListener(topics = {"hms.appointment.events", "hms.billing.events", "hms.pharmacy.events"},
                 groupId = "notification", concurrency = "3")
  void on(ConsumerRecord<String, String> r, @Header("messageId") String id,
          @Header("eventType") String type) {
    if (!dedupe.firstTime(id, "notification")) return;
    switch (type) {
      case "AppointmentConfirmed" -> router.send(Template.APPT_CONFIRMED, r.key(), r.value());
      case "AppointmentFailed"    -> router.send(Template.APPT_FAILED,    r.key(), r.value());
      case "InvoiceCreated"       -> router.send(Template.INVOICE_READY,  r.key(), r.value());
      case "LowStockDetected"     -> router.sendToRole("ROLE_PHARMACIST", Template.LOW_STOCK, r.value());
      default -> { }   // ignore unrelated events
    }
  }
}
```

### Channel strategy

```java
public interface NotificationChannel {
  ChannelType type();
  void send(Recipient to, RenderedMessage msg);
}

@Component @RequiredArgsConstructor
class EmailChannel implements NotificationChannel {
  private final SesV2Client ses;
  public ChannelType type() { return ChannelType.EMAIL; }

  @CircuitBreaker(name = "ses")
  @Retry(name = "ses")
  public void send(Recipient to, RenderedMessage m) {
    ses.sendEmail(b -> b.fromEmailAddress("no-reply@hms.example.com")
        .destination(d -> d.toAddresses(to.email()))
        .content(c -> c.simple(s -> s.subject(x -> x.data(m.subject()))
                                      .body(x -> x.text(t -> t.data(m.body()))))));
  }
}

@Component @RequiredArgsConstructor
class SmsChannel implements NotificationChannel {
  private final SnsClient sns;
  public ChannelType type() { return ChannelType.SMS; }

  @CircuitBreaker(name = "sns", fallbackMethod = "toEmail")
  @Retry(name = "sns")
  public void send(Recipient to, RenderedMessage m) {
    sns.publish(b -> b.phoneNumber(to.phone()).message(m.shortBody()));
  }
}

@Service @RequiredArgsConstructor
class NotificationRouter {
  private final Map<ChannelType, NotificationChannel> channels;   // injected by type
  private final RecipientDirectory directory;
  private final TemplateRenderer renderer;
  private final NotificationLog log;

  void send(Template t, String subjectId, String payload) {
    Recipient to = directory.resolve(subjectId);
    for (ChannelType ch : to.preferredChannels()) {
      try {
        channels.get(ch).send(to, renderer.render(t, payload, to.locale()));
        log.sent(subjectId, ch, t);
        return;                                  // first success wins
      } catch (Exception e) {
        log.failed(subjectId, ch, t, e);         // fall through to next channel
      }
    }
    throw new NotificationFailedException(subjectId);   // -> DLT after retries
  }
}
```

PHI rule: messages contain **no clinical detail** ("Your appointment on Oct 28 is confirmed", never the diagnosis). Push goes through SNS mobile endpoints or FCM; keep device tokens in a table keyed by user.

```yaml
resilience4j:
  circuitbreaker.instances:
    ses: { base-config: default }
    sns: { base-config: default }
  retry.instances:
    ses: { base-config: default }
    sns: { base-config: default }
spring.kafka.listener.ack-mode: record
```

---

## 3. patient-service

### Entity and events

```java
@Entity @Table(name = "patient")
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Patient {
  @Id private UUID id;
  @Convert(converter = EncryptedStringConverter.class) private String nationalId;   // field-level encryption (KMS data key)
  private String fullName;
  private LocalDate dateOfBirth;
  private String department;
  @Enumerated(EnumType.STRING) private Sensitivity sensitivity = Sensitivity.NORMAL;
  @ElementCollection @CollectionTable(name = "patient_care_team")
  private Set<UUID> careTeam = new HashSet<>();
  @Version private long version;
}

@Service @RequiredArgsConstructor
class PatientService {
  private final PatientRepository repo;
  private final ApplicationEventPublisher events;

  @Transactional
  @PreAuthorize("hasPermission(#id, 'Patient', 'WRITE')")
  public Patient changeSensitivity(UUID id, Sensitivity s) {
    var p = repo.findById(id).orElseThrow();
    p.setSensitivity(s);
    events.publishEvent(new DomainEvent("patient", id.toString(), "PatientSensitivityChanged",
        new SensitivityChanged(id, s)));
    return p;
  }
}
```

### Lookup used by the ABAC evaluator (with cache)

```java
@Component @RequiredArgsConstructor
class JpaPatientResourceLookup implements PatientResourceLookup {
  private final PatientRepository repo;

  @Cacheable(cacheNames = "patientResource", key = "#id")     // Caffeine, 30s TTL
  @Transactional(readOnly = true)
  public Optional<PatientResource> find(UUID id) {
    return repo.findById(id).map(p ->
        new PatientResource(p.getId(), p.getSensitivity(), p.getDepartment(), Set.copyOf(p.getCareTeam())));
  }
}
```

Keep the TTL short (or evict on `PatientSensitivityChanged`) so sensitivity downgrades and care-team removals take effect quickly.

### Other services need patient data

Do not call `patient-service` synchronously from the Saga path. Other services keep a small local replica (`patient_ref`: id, name, contact) built from `hms.patient.events`. That removes a runtime dependency and keeps booking available if patient-service is down.

---

## 4. iam-auth-service

Don't hand-roll an authorization server. Use Keycloak (or Cognito) for tokens, and make `iam-auth-service` a thin admin and policy facade.

### Responsibilities

1. Provision users and roles through the Keycloak Admin API (`ROLE_DOCTOR`, `ROLE_NURSE`, etc.).
2. Maintain subject attributes (`department`, `shift_start`, `shift_end`) and expose them as JWT claims.
3. Receive break-glass justifications and publish `BreakGlassUsed` audit events.

### Keycloak realm excerpt (attribute to claim mapper)

```json
{
  "realm": "hms",
  "accessTokenLifespan": 300,
  "sslRequired": "all",
  "bruteForceProtected": true,
  "roles": { "realm": [
    {"name":"admin"},{"name":"doctor"},{"name":"patient"},
    {"name":"nurse"},{"name":"receptionist"},{"name":"billing_clerk"}
  ]},
  "clientScopes": [{
    "name": "hms-attributes",
    "protocol": "openid-connect",
    "protocolMappers": [
      {"name":"department","protocol":"openid-connect","protocolMapper":"oidc-usermodel-attribute-mapper",
       "config":{"user.attribute":"department","claim.name":"department","jsonType.label":"String","access.token.claim":"true"}},
      {"name":"shift_start","protocol":"openid-connect","protocolMapper":"oidc-usermodel-attribute-mapper",
       "config":{"user.attribute":"shift_start","claim.name":"shift_start","jsonType.label":"String","access.token.claim":"true"}},
      {"name":"shift_end","protocol":"openid-connect","protocolMapper":"oidc-usermodel-attribute-mapper",
       "config":{"user.attribute":"shift_end","claim.name":"shift_end","jsonType.label":"String","access.token.claim":"true"}}
    ]
  }]
}
```

On Cognito, use a pre-token-generation Lambda to inject the same claims and map groups to roles. Require MFA for all staff roles. Keep access tokens short-lived (5 min) with refresh rotation.

### Break-glass endpoint

```java
@RestController @RequestMapping("/api/iam/break-glass")
@RequiredArgsConstructor
class BreakGlassController {
  private final ApplicationEventPublisher events;

  @PreAuthorize("hasRole('DOCTOR')")
  @PostMapping
  ResponseEntity<Void> declare(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid BreakGlassRequest req) {
    events.publishEvent(new DomainEvent("iam", jwt.getSubject(), "BreakGlassUsed",
        new BreakGlassUsed(jwt.getSubject(), req.patientId(), req.reason(), Instant.now())));
    return ResponseEntity.accepted().build();
  }
}
```

Compliance reads `hms.iam.events` and reviews each break-glass access after the fact.

---

## 5. Service discovery

Pick one; the gateway's `lb://service-name` routes work with either.

**On EKS (recommended): Kubernetes-native discovery**

```xml
<dependency>
  <groupId>org.springframework.cloud</groupId>
  <artifactId>spring-cloud-starter-kubernetes-client-loadbalancer</artifactId>
</dependency>
```
```yaml
spring.cloud.kubernetes.discovery.enabled: true
spring.cloud.kubernetes.discovery.all-namespaces: false
```
Grant the gateway's ServiceAccount `get/list/watch` on `services` and `endpoints`.

**On ECS: AWS Cloud Map**: register services in a private DNS namespace, set routes to `http://appointment-service.hms.local:8080`, and drop discovery from the gateway.

---

## 6. Local development stack (`docker-compose.yml`)

```yaml
services:
  postgres:
    image: postgres:16
    command: ["postgres","-c","wal_level=logical","-c","max_replication_slots=10","-c","max_wal_senders=10"]
    environment: { POSTGRES_PASSWORD: hms, POSTGRES_USER: hms }
    ports: ["5432:5432"]
    volumes: ["./init-dbs.sql:/docker-entrypoint-initdb.d/init.sql"]   # creates patients, appointments, billing, pharmacy DBs

  kafka:
    image: apache/kafka:3.8.0
    ports: ["9092:9092"]
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093,INTERNAL://:29092
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092,INTERNAL://kafka:29092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,INTERNAL:PLAINTEXT
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_INTER_BROKER_LISTENER_NAME: INTERNAL
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1

  connect:
    image: quay.io/debezium/connect:2.7
    depends_on: [kafka, postgres]
    ports: ["8083:8083"]
    environment:
      BOOTSTRAP_SERVERS: kafka:29092
      GROUP_ID: hms-connect
      CONFIG_STORAGE_TOPIC: connect_configs
      OFFSET_STORAGE_TOPIC: connect_offsets
      STATUS_STORAGE_TOPIC: connect_statuses

  redis:
    image: redis:7
    ports: ["6379:6379"]

  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.15.0
    environment: { discovery.type: single-node, xpack.security.enabled: "false" }
    ports: ["9200:9200"]

  keycloak:
    image: quay.io/keycloak/keycloak:25.0
    command: ["start-dev","--import-realm"]
    environment: { KEYCLOAK_ADMIN: admin, KEYCLOAK_ADMIN_PASSWORD: admin }
    volumes: ["./realm-hms.json:/opt/keycloak/data/import/realm-hms.json"]
    ports: ["8180:8080"]
```

Register each connector with `curl -X POST -H 'Content-Type: application/json' --data @connector.json localhost:8083/connectors` (one per service database).

---

## 7. Suggested build order

1. `hms-common` (outbox, `DomainEvent`, dedupe, error handler) and the Flyway migrations.
2. `iam-auth-service` plus the Keycloak realm, then the gateway with JWT validation.
3. `patient-service` with the ABAC evaluator and tests (policy matrix as parameterized tests).
4. `appointment-service` + `billing-service` happy path, then the pharmacy leg.
5. Failure drills: kill billing mid-saga, force `AllocationFailed`, stop Debezium and confirm no lost events on restart.
6. `notification-service`, the CQRS projector, then Terraform for the Route 53 to CloudFront to WAF to ALB edge.

Next candidates: Terraform for the AWS edge and VPC, a Kubernetes/Helm layout, or the ABAC policy test matrix.
