# Hospital Management System (HMS): Spring Boot 3.x / Java 21

Deliverables: (1) architecture diagram, (2) Maven setup, (3) security, (4) outbox + Debezium, (5) resilience config, (6) Saga + CQRS.
Code is a production-oriented reference skeleton: wire in your own domain classes, secrets management and IaC.

---

## 1. System Architecture

```
 Clients (web / mobile / partner APIs)
        |
 [Route 53]  latency + geo routing, failover health checks
        |
 [CloudFront] ACM TLS, static assets, geo-blocking
        |
 [AWS WAF + Shield Advanced]  OWASP managed rules, edge rate limit, IP reputation
        |
 ===================== PUBLIC SUBNETS (3 AZs) =====================
 [External ALB]  TLS termination, health checks
        |
 ===================== PRIVATE SUBNETS (app tier) =================
 [Spring Cloud Gateway x N]  JWT validation (JWKS), Redis token bucket,
        |                    security headers, discovery routes
        |                              \
        |                          [Redis (ElastiCache)]
        v
 [Internal ALB / NLB]
        |
   +----+--------+-------------+-------------------+------------------+
   |             |             |                   |                  |
 iam-auth    patient-      appointment-        billing-         pharmacy-inventory-
 service     service       service (Saga)      service          service
   |             |             |                   |                  |
 Keycloak/   PG + outbox   PG + outbox         PG + outbox        PG + outbox
 Cognito         \             |                   |                 /
                  \____________|___________________|________________/
                               |   (WAL, pgoutput)
                        [Debezium on Kafka Connect]  EventRouter SMT
                               |
                        [Apache Kafka (MSK)]  hms.<aggregate>.events
                         |             |                |
              notification-service  Saga consumers   Projectors (CQRS)
              (SMS/Email/Push)      (orchestrator)        |
                                                   [Elasticsearch / MongoDB]
                                                   (read models, search, reports)
 ===================== DATA SUBNETS (isolated) ====================
   RDS PostgreSQL Multi-AZ, MSK, ElastiCache, OpenSearch: no internet route
```

Security groups: ALB -> Gateway only; Gateway -> Internal ALB only; services -> their DB/Kafka only. mTLS between services (service mesh or ACM PCA), KMS encryption at rest, PHI audit log to S3 (Object Lock).

---

## 2. Maven

### Parent `pom.xml`

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
  </parent>

  <groupId>com.hms</groupId>
  <artifactId>hms-parent</artifactId>
  <version>1.0.0</version>
  <packaging>pom</packaging>

  <properties>
    <java.version>21</java.version>
    <spring-cloud.version>2023.0.3</spring-cloud.version>
    <resilience4j.version>2.2.0</resilience4j.version>
  </properties>

  <modules>
    <module>api-gateway</module>
    <module>iam-auth-service</module>
    <module>patient-service</module>
    <module>appointment-service</module>
    <module>billing-service</module>
    <module>pharmacy-inventory-service</module>
    <module>notification-service</module>
    <module>hms-common</module>
  </modules>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-dependencies</artifactId>
        <version>${spring-cloud.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>io.github.resilience4j</groupId>
        <artifactId>resilience4j-bom</artifactId>
        <version>${resilience4j.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
```

### `api-gateway/pom.xml` (dependencies)

```xml
<dependencies>
  <dependency><groupId>org.springframework.cloud</groupId><artifactId>spring-cloud-starter-gateway</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-oauth2-resource-server</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-redis-reactive</artifactId></dependency>
  <dependency><groupId>org.springframework.cloud</groupId><artifactId>spring-cloud-starter-loadbalancer</artifactId></dependency>
  <dependency><groupId>org.springframework.cloud</groupId><artifactId>spring-cloud-starter-circuitbreaker-reactor-resilience4j</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
</dependencies>
```

### Domain service `pom.xml` (dependencies, e.g. `appointment-service`)

```xml
<dependencies>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-oauth2-resource-server</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
  <dependency><groupId>org.springframework.kafka</groupId><artifactId>spring-kafka</artifactId></dependency>
  <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
  <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
  <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></dependency>
  <dependency><groupId>io.github.resilience4j</groupId><artifactId>resilience4j-spring-boot3</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-aop</artifactId></dependency>
  <dependency><groupId>io.micrometer</groupId><artifactId>micrometer-registry-prometheus</artifactId></dependency>
  <!-- tests -->
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
  <dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-test</artifactId><scope>test</scope></dependency>
  <dependency><groupId>org.testcontainers</groupId><artifactId>postgresql</artifactId><scope>test</scope></dependency>
  <dependency><groupId>org.testcontainers</groupId><artifactId>kafka</artifactId><scope>test</scope></dependency>
</dependencies>
```

---

## 3. Security

### 3.1 Gateway: JWT validation, Redis token bucket, headers

```java
// api-gateway: SecurityConfig.java
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

  @Bean
  SecurityWebFilterChain chain(ServerHttpSecurity http) {
    return http
      .csrf(ServerHttpSecurity.CsrfSpec::disable)               // stateless bearer-token API
      .authorizeExchange(a -> a
        .pathMatchers("/actuator/health/**").permitAll()
        .anyExchange().authenticated())
      .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults())) // JWKS from issuer-uri
      .headers(h -> h
        .contentTypeOptions(Customizer.withDefaults())
        .hsts(s -> s.includeSubdomains(true).maxAge(Duration.ofDays(365)))
        .frameOptions(f -> f.mode(XFrameOptionsServerHttpHeadersWriter.Mode.DENY))
        .contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'")))
      .build();
  }

  /** Token-bucket key: per authenticated subject, fallback to client IP. */
  @Bean
  KeyResolver userKeyResolver() {
    return exchange -> exchange.getPrincipal()
      .map(p -> p.getName())
      .switchIfEmpty(Mono.fromSupplier(() ->
        Objects.requireNonNull(exchange.getRequest().getRemoteAddress())
               .getAddress().getHostAddress()));
  }

  @Bean
  RedisRateLimiter redisRateLimiter() {
    return new RedisRateLimiter(50, 100, 1); // 50 tokens/s refill, burst 100
  }
}
```

```yaml
# api-gateway application.yml
spring:
  security.oauth2.resourceserver.jwt.issuer-uri: https://auth.hms.example.com/realms/hms
  data.redis: { host: ${REDIS_HOST}, port: 6379, ssl.enabled: true }
  cloud:
    gateway:
      discovery.locator: { enabled: true, lower-case-service-id: true }
      default-filters:
        - TokenRelay=
        - name: RequestRateLimiter
          args:
            key-resolver: "#{@userKeyResolver}"
            redis-rate-limiter.replenishRate: 50
            redis-rate-limiter.burstCapacity: 100
            redis-rate-limiter.requestedTokens: 1
        - RemoveRequestHeader=Cookie
        - AddResponseHeader=Cache-Control, no-store
      routes:
        - id: patient
          uri: lb://patient-service
          predicates: [ Path=/api/patients/** ]
          filters:
            - name: CircuitBreaker
              args: { name: patientCb, fallbackUri: "forward:/fallback/patient" }
        - id: appointment
          uri: lb://appointment-service
          predicates: [ Path=/api/appointments/** ]
```

### 3.2 Domain-service resource server + role mapping

```java
@Configuration
@EnableMethodSecurity   // enables @PreAuthorize with our PermissionEvaluator
public class ServiceSecurityConfig {

  @Bean
  SecurityFilterChain chain(HttpSecurity http) throws Exception {
    return http
      .csrf(AbstractHttpConfigurer::disable)
      .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .authorizeHttpRequests(a -> a
        .requestMatchers("/actuator/health/**").permitAll()
        .anyRequest().authenticated())
      .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter())))
      .build();
  }

  /** Map Keycloak realm roles -> ROLE_* authorities. */
  private JwtAuthenticationConverter converter() {
    var c = new JwtAuthenticationConverter();
    c.setJwtGrantedAuthoritiesConverter(jwt -> {
      Map<String, Object> realm = jwt.getClaim("realm_access");
      Collection<String> roles = realm == null ? List.of() : (Collection<String>) realm.get("roles");
      return roles.stream()
        .map(r -> new SimpleGrantedAuthority("ROLE_" + r.toUpperCase()))
        .collect(Collectors.toList());
    });
    return c;
  }

  @Bean
  static MethodSecurityExpressionHandler expressionHandler(HmsPermissionEvaluator evaluator) {
    var h = new DefaultMethodSecurityExpressionHandler();
    h.setPermissionEvaluator(evaluator);
    return h;
  }
}
```

### 3.3 ABAC `PermissionEvaluator`

```java
public enum Sensitivity { NORMAL, RESTRICTED, VIP }

public record PatientResource(UUID patientId, Sensitivity sensitivity,
                              String department, Set<UUID> careTeam) {}

public interface PatientResourceLookup { Optional<PatientResource> find(UUID patientId); }

@Component
@RequiredArgsConstructor
@Slf4j
public class HmsPermissionEvaluator implements PermissionEvaluator {

  private final PatientResourceLookup lookup;
  private final AuditPublisher audit;
  private final Clock clock;

  /** Usage: @PreAuthorize("hasPermission(#patientId, 'Patient', 'READ')") */
  @Override
  public boolean hasPermission(Authentication auth, Serializable targetId,
                               String targetType, Object permission) {
    if (!(auth instanceof JwtAuthenticationToken jwtAuth) || targetId == null) return false;
    if (!"Patient".equals(targetType)) return false;

    Jwt jwt = jwtAuth.getToken();
    Action action = Action.valueOf(String.valueOf(permission));
    PatientResource res = lookup.find(UUID.fromString(targetId.toString())).orElse(null);
    if (res == null) return false;                       // fail closed

    Env env = Env.fromRequest();                         // IP, location, break-glass header
    Subject sub = Subject.from(jwt, jwtAuth.getAuthorities());

    Decision d = decide(sub, res, action, env);
    audit.record(sub.userId(), res.patientId(), action, d);
    return d.permit();
  }

  @Override
  public boolean hasPermission(Authentication a, Object target, Object permission) {
    return target instanceof PatientResource r
        && hasPermission(a, r.patientId(), "Patient", permission);
  }

  // ---- policy ---------------------------------------------------------------
  Decision decide(Subject s, PatientResource r, Action a, Env env) {
    // Admins: non-clinical oversight. Read NORMAL records only; never write or delete clinical data.
    if (s.has("ROLE_ADMIN"))
      return (a == Action.READ && r.sensitivity() == Sensitivity.NORMAL)
          ? Decision.permit("admin-read-normal") : Decision.deny("admin: read of normal records only");

    // Patients: own record only, read only
    if (s.has("ROLE_PATIENT"))
      return (a == Action.READ && s.userId().equals(r.patientId()))
          ? Decision.permit("self") : Decision.deny("patient: not self");

    // Billing / reception: demographics only, never VIP/RESTRICTED clinical data
    if (s.hasAny("ROLE_BILLING_CLERK", "ROLE_RECEPTIONIST"))
      return (a == Action.READ && r.sensitivity() == Sensitivity.NORMAL)
          ? Decision.permit("admin-read-normal") : Decision.deny("insufficient role/sensitivity");

    if (s.hasAny("ROLE_DOCTOR", "ROLE_NURSE")) {
      if (a == Action.DELETE) return Decision.deny("clinical delete forbidden");
      boolean onShift   = s.onShift(LocalTime.now(clock));
      boolean sameDept  = Objects.equals(s.department(), r.department());
      boolean careTeam  = r.careTeam().contains(s.userId());
      boolean trustedIp = env.fromHospitalNetwork();

      // Break-glass: allowed for clinicians in emergencies, always flagged for review
      if (env.breakGlass() && s.has("ROLE_DOCTOR") && a == Action.READ) {
        log.warn("BREAK_GLASS user={} patient={}", s.userId(), r.patientId());
        return Decision.permitFlagged("break-glass");
      }
      if (!onShift || !trustedIp) return Decision.deny("outside shift or network");

      return switch (r.sensitivity()) {
        case NORMAL     -> (sameDept || careTeam) ? Decision.permit("dept/care-team") : Decision.deny("no relationship");
        case RESTRICTED -> careTeam               ? Decision.permit("care-team")      : Decision.deny("restricted: not on care team");
        case VIP        -> (careTeam && s.has("ROLE_DOCTOR")) ? Decision.permit("vip care-team doctor") : Decision.deny("vip");
      };
    }
    return Decision.deny("no matching policy");
  }

  public enum Action { READ, WRITE, DELETE }
  record Decision(boolean permit, boolean flagged, String reason) {
    static Decision permit(String r)        { return new Decision(true,  false, r); }
    static Decision permitFlagged(String r) { return new Decision(true,  true,  r); }
    static Decision deny(String r)          { return new Decision(false, false, r); }
  }
  record Subject(UUID userId, String department, LocalTime shiftStart, LocalTime shiftEnd,
                 Set<String> roles) {
    static Subject from(Jwt j, Collection<? extends GrantedAuthority> auths) {
      return new Subject(UUID.fromString(j.getSubject()), j.getClaimAsString("department"),
        LocalTime.parse(j.getClaimAsString("shift_start") == null ? "00:00" : j.getClaimAsString("shift_start")),
        LocalTime.parse(j.getClaimAsString("shift_end")   == null ? "23:59" : j.getClaimAsString("shift_end")),
        auths.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet()));
    }
    boolean has(String r) { return roles.contains(r); }
    boolean hasAny(String... r) { return Arrays.stream(r).anyMatch(roles::contains); }
    boolean onShift(LocalTime t) {
      return shiftStart.isBefore(shiftEnd)
        ? !t.isBefore(shiftStart) && !t.isAfter(shiftEnd)
        : !t.isBefore(shiftStart) || !t.isAfter(shiftEnd); // overnight shift
    }
  }
  record Env(String ip, boolean fromHospitalNetwork, boolean breakGlass) {
    static Env fromRequest() {
      var req = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
      String ip = Optional.ofNullable(req.getHeader("X-Forwarded-For")).orElse(req.getRemoteAddr());
      return new Env(ip, ip.startsWith("10."), "true".equalsIgnoreCase(req.getHeader("X-Break-Glass")));
    }
  }
}
```

```java
@RestController @RequestMapping("/api/patients")
class PatientController {
  @PreAuthorize("hasPermission(#id, 'Patient', 'READ')")
  @GetMapping("/{id}") PatientDto get(@PathVariable UUID id) { /* ... */ return null; }

  @PreAuthorize("hasPermission(#id, 'Patient', 'WRITE')")
  @PutMapping("/{id}") PatientDto update(@PathVariable UUID id, @RequestBody @Valid UpdatePatient cmd) { return null; }
}
```

Hardening notes: `X-Forwarded-For` and `X-Break-Glass` must only be trusted when set by the gateway (strip client-supplied copies at the edge). Break-glass reads should raise a compliance alert and require post-hoc justification.

---

## 4. Transactional Outbox + Debezium

### 4.1 Flyway migration

```sql
CREATE TABLE outbox (
  id            UUID         PRIMARY KEY,
  aggregatetype VARCHAR(100) NOT NULL,
  aggregateid   VARCHAR(100) NOT NULL,
  type          VARCHAR(100) NOT NULL,
  payload       JSONB        NOT NULL,
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- Logical replication for Debezium
ALTER TABLE outbox REPLICA IDENTITY FULL;
CREATE PUBLICATION dbz_outbox FOR TABLE outbox;

-- Idempotent consumers
CREATE TABLE processed_message (
  message_id   UUID PRIMARY KEY,
  consumer     VARCHAR(100) NOT NULL,
  processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

### 4.2 Entity, repository, publisher

```java
@Entity @Table(name = "outbox")
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {
  @Id private UUID id;
  @Column(name = "aggregatetype", nullable = false) private String aggregateType;
  @Column(name = "aggregateid",   nullable = false) private String aggregateId;
  @Column(nullable = false) private String type;
  @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb", nullable = false)
  private String payload;
  @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

  public OutboxEvent(String aggregateType, String aggregateId, String type, String payload) {
    this.id = UUID.randomUUID();
    this.aggregateType = aggregateType; this.aggregateId = aggregateId;
    this.type = type; this.payload = payload;
  }
}

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {}

/** Domain code publishes this via ApplicationEventPublisher. */
public record DomainEvent(String aggregateType, String aggregateId, String type, Object payload) {}

@Component
@RequiredArgsConstructor
class OutboxEventListener {
  private final OutboxRepository repo;
  private final ObjectMapper mapper;

  /** BEFORE_COMMIT => outbox row is part of the SAME local transaction as the state change. */
  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  void on(DomainEvent e) throws JsonProcessingException {
    var row = new OutboxEvent(e.aggregateType(), e.aggregateId(), e.type(),
                              mapper.writeValueAsString(e.payload()));
    repo.save(row);
    // Optional: repo.delete(row) here. Debezium reads the INSERT from the WAL,
    // so the table stays tiny without a cleanup job.
  }
}
```

Usage:

```java
@Service @RequiredArgsConstructor
class AppointmentService {
  private final AppointmentRepository appointments;
  private final ApplicationEventPublisher events;

  @Transactional
  public Appointment request(BookCmd c) {
    var a = appointments.save(Appointment.requested(c));      // state change
    events.publishEvent(new DomainEvent("appointment", a.getId().toString(),
        "AppointmentRequested", new AppointmentRequested(a.getId(), c.patientId(), c.doctorId(), c.slot())));
    return a;                                                  // both rows commit atomically
  }
}
```

### 4.3 Debezium Kafka Connect config

`POST http://connect:8083/connectors`

```json
{
  "name": "appointment-outbox-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "tasks.max": "1",
    "database.hostname": "${file:/secrets/db.properties:host}",
    "database.port": "5432",
    "database.user": "${file:/secrets/db.properties:user}",
    "database.password": "${file:/secrets/db.properties:password}",
    "database.dbname": "appointments",
    "topic.prefix": "appointment-db",
    "plugin.name": "pgoutput",
    "publication.name": "dbz_outbox",
    "slot.name": "appointment_outbox_slot",
    "table.include.list": "public.outbox",
    "tombstones.on.delete": "false",
    "heartbeat.interval.ms": "10000",

    "key.converter": "org.apache.kafka.connect.storage.StringConverter",
    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
    "value.converter.schemas.enable": "false",

    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.table.field.event.id": "id",
    "transforms.outbox.table.field.event.key": "aggregateid",
    "transforms.outbox.table.field.event.type": "type",
    "transforms.outbox.table.field.event.payload": "payload",
    "transforms.outbox.table.field.event.timestamp": "created_at",
    "transforms.outbox.route.by.field": "aggregatetype",
    "transforms.outbox.route.topic.replacement": "hms.${routedByValue}.events",
    "transforms.outbox.table.expand.json.payload": "true",
    "transforms.outbox.table.fields.additional.placement": "type:header:eventType,id:header:messageId"
  }
}
```

Keying by `aggregateid` guarantees per-aggregate ordering within a partition. Debezium delivers **at-least-once**, so every consumer must dedupe on the `messageId` header (`processed_message` table above). Monitor replication-slot lag: an unconsumed slot bloats WAL on RDS.

---

## 5. Resilience4j

```yaml
# application.yml (service-to-service clients)
resilience4j:
  circuitbreaker:
    configs:
      default:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 20
        minimum-number-of-calls: 10
        failure-rate-threshold: 50
        slow-call-duration-threshold: 2s
        slow-call-rate-threshold: 80
        wait-duration-in-open-state: 20s
        permitted-number-of-calls-in-half-open-state: 5
        automatic-transition-from-open-to-half-open-enabled: true
        record-exceptions: [java.io.IOException, java.util.concurrent.TimeoutException, org.springframework.web.client.HttpServerErrorException]
        ignore-exceptions: [com.hms.common.BusinessValidationException]
    instances:
      billingClient:
        base-config: default
      pharmacyClient:
        base-config: default
        sliding-window-type: TIME_BASED
        sliding-window-size: 30        # seconds

  retry:
    configs:
      default:
        max-attempts: 4                       # 1 call + 3 retries
        wait-duration: 500ms                  # initial delay
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2.0   # 500ms, 1s, 2s
        retry-exceptions: [java.io.IOException, java.util.concurrent.TimeoutException]
        ignore-exceptions: [com.hms.common.BusinessValidationException, io.github.resilience4j.circuitbreaker.CallNotPermittedException]
    instances:
      billingClient:   { base-config: default }
      pharmacyClient:  { base-config: default }

  bulkhead:
    instances:
      billingClient:  { max-concurrent-calls: 25, max-wait-duration: 100ms }
  thread-pool-bulkhead:
    instances:
      pharmacyClient: { core-thread-pool-size: 8, max-thread-pool-size: 16, queue-capacity: 50, keep-alive-duration: 20ms }

  ratelimiter:
    instances:
      billingClient:  { limit-for-period: 100, limit-refresh-period: 1s, timeout-duration: 0 }

  timelimiter:
    instances:
      pharmacyClient: { timeout-duration: 3s, cancel-running-future: true }
```

**Full jitter.** Config properties only offer the `randomized-wait-factor` variant, so implement AWS-style *full jitter* (random delay in `[0, base * 2^(n-1)]`) with a customizer:

```java
@Configuration
class RetryJitterConfig {
  @Bean
  RetryConfigCustomizer billingRetry() {
    return RetryConfigCustomizer.of("billingClient", b -> b.intervalFunction(fullJitter(500, 2.0)));
  }
  @Bean
  RetryConfigCustomizer pharmacyRetry() {
    return RetryConfigCustomizer.of("pharmacyClient", b -> b.intervalFunction(fullJitter(500, 2.0)));
  }
  static IntervalFunction fullJitter(long initialMs, double multiplier) {
    return attempt -> {                                   // attempt starts at 1
      long cap = (long) (initialMs * Math.pow(multiplier, attempt - 1));
      return ThreadLocalRandom.current().nextLong(0, cap + 1);
    };
  }
}
```

Client with fallback (annotation order: Retry wraps CircuitBreaker by default):

```java
@Component @RequiredArgsConstructor
class BillingClient {
  private final RestClient http;

  @CircuitBreaker(name = "billingClient", fallbackMethod = "fallback")
  @Retry(name = "billingClient")
  @Bulkhead(name = "billingClient")
  Invoice createInvoice(CreateInvoice cmd) {
    return http.post().uri("lb://billing-service/api/invoices").body(cmd).retrieve().body(Invoice.class);
  }
  Invoice fallback(CreateInvoice cmd, Throwable t) {
    throw new DownstreamUnavailableException("billing", t);  // Saga treats as retryable/compensate
  }
}
```

Retry only idempotent calls. Send an `Idempotency-Key` header (the appointment ID) on POSTs.

---

## 6. Saga + CQRS: Appointment Booking

### Flow (orchestrated by `appointment-service`)

```
AppointmentRequested (local)
  -> SLOT_RESERVED ──> [cmd] CreateInvoice ──> billing-service
        InvoiceCreated ───────────────┐          InvoiceFailed ─> COMPENSATE: release slot -> FAILED
                                      v
                         (if prescription) [cmd] AllocateInventory ──> pharmacy-inventory-service
                               InventoryAllocated ─> CONFIRMED -> AppointmentConfirmed (notify + project)
                               AllocationFailed   ─> COMPENSATE: [cmd] VoidInvoice, release slot -> FAILED
```

### Saga state + orchestrator

```java
public enum SagaState { STARTED, SLOT_RESERVED, INVOICE_CREATED, CONFIRMED, COMPENSATING, FAILED }

@Entity @Table(name = "appointment_saga")
@Getter @Setter
public class AppointmentSaga {
  @Id private UUID appointmentId;
  @Enumerated(EnumType.STRING) private SagaState state;
  private UUID invoiceId;
  private boolean needsPharmacy;
  private String failureReason;
  @Version private long version;
}

@Component
@RequiredArgsConstructor
@Slf4j
public class AppointmentSagaOrchestrator {

  private final AppointmentSagaRepository sagas;
  private final AppointmentRepository appointments;
  private final SlotRepository slots;
  private final ProcessedMessageRepository processed;
  private final ApplicationEventPublisher events;   // -> outbox
  private final ObjectMapper mapper;

  // ---- step 0: start --------------------------------------------------------
  @Transactional
  public void start(AppointmentRequested e) {
    var slot = slots.lockFree(e.doctorId(), e.slot())              // SELECT ... FOR UPDATE SKIP LOCKED
        .orElse(null);
    var saga = new AppointmentSaga();
    saga.setAppointmentId(e.appointmentId());
    saga.setNeedsPharmacy(e.hasPrescription());
    if (slot == null) { fail(saga, "slot unavailable"); return; }
    slot.reserve(e.appointmentId());
    saga.setState(SagaState.SLOT_RESERVED);
    sagas.save(saga);
    command("billing", e.appointmentId(), "CreateInvoice",
        new CreateInvoice(e.appointmentId(), e.patientId(), e.fee()));
  }

  // ---- step 1: billing replies ----------------------------------------------
  @KafkaListener(topics = "hms.billing.events", groupId = "appointment-saga")
  @Transactional
  public void onBilling(ConsumerRecord<String, String> rec,
                        @Header("messageId") String msgId,
                        @Header("eventType") String type) throws Exception {
    if (!markProcessed(msgId)) return;                              // idempotency
    var saga = sagas.findById(UUID.fromString(rec.key())).orElse(null);
    if (saga == null) return;
    // Late reply after the saga already failed (timeout/compensation): void the orphaned invoice.
    if (saga.getState() == SagaState.FAILED && "InvoiceCreated".equals(type)) {
      var late = mapper.readValue(rec.value(), InvoiceCreated.class);
      command("billing", saga.getAppointmentId(), "VoidInvoice", new VoidInvoice(late.invoiceId()));
      return;
    }
    if (saga.getState() != SagaState.SLOT_RESERVED) return;                   // stale/duplicate

    switch (type) {
      case "InvoiceCreated" -> {
        var ev = mapper.readValue(rec.value(), InvoiceCreated.class);
        saga.setInvoiceId(ev.invoiceId());
        saga.setState(SagaState.INVOICE_CREATED);
        if (saga.isNeedsPharmacy())
          command("pharmacy", saga.getAppointmentId(), "AllocateInventory",
                  new AllocateInventory(saga.getAppointmentId(), ev.prescriptionId()));
        else confirm(saga);
      }
      case "InvoiceFailed" -> compensate(saga, "billing failed");   // nothing to void yet
      default -> { }
    }
  }

  // ---- step 2: pharmacy replies ---------------------------------------------
  @KafkaListener(topics = "hms.pharmacy.events", groupId = "appointment-saga")
  @Transactional
  public void onPharmacy(ConsumerRecord<String, String> rec,
                         @Header("messageId") String msgId,
                         @Header("eventType") String type) {
    if (!markProcessed(msgId)) return;
    var saga = sagas.findById(UUID.fromString(rec.key())).orElse(null);
    if (saga == null || saga.getState() != SagaState.INVOICE_CREATED) return;

    switch (type) {
      case "InventoryAllocated" -> confirm(saga);
      case "AllocationFailed"   -> compensate(saga, "inventory unavailable");
      default -> { }
    }
  }

  // ---- terminal transitions -------------------------------------------------
  private void confirm(AppointmentSaga s) {
    s.setState(SagaState.CONFIRMED);
    appointments.markConfirmed(s.getAppointmentId());
    events.publishEvent(new DomainEvent("appointment", s.getAppointmentId().toString(),
        "AppointmentConfirmed", new AppointmentConfirmed(s.getAppointmentId())));  // notify + CQRS
  }

  /** Compensations run in reverse order of completed steps. */
  private void compensate(AppointmentSaga s, String reason) {
    s.setState(SagaState.COMPENSATING);
    if (s.getInvoiceId() != null)
      command("billing", s.getAppointmentId(), "VoidInvoice", new VoidInvoice(s.getInvoiceId()));
    slots.release(s.getAppointmentId());
    fail(s, reason);
  }

  private void fail(AppointmentSaga s, String reason) {
    s.setState(SagaState.FAILED);
    s.setFailureReason(reason);
    sagas.save(s);
    appointments.markFailed(s.getAppointmentId(), reason);
    events.publishEvent(new DomainEvent("appointment", s.getAppointmentId().toString(),
        "AppointmentFailed", new AppointmentFailed(s.getAppointmentId(), reason)));
  }

  /** Commands travel through the same outbox; aggregatetype routes to the target service's topic. */
  private void command(String targetService, UUID id, String type, Object payload) {
    events.publishEvent(new DomainEvent(targetService + "-commands", id.toString(), type, payload));
  }

  private boolean markProcessed(String msgId) {
    try { processed.saveAndFlush(new ProcessedMessage(UUID.fromString(msgId), "appointment-saga")); return true; }
    catch (DataIntegrityViolationException dup) { return false; }
  }
}
```

Operational requirements: Kafka listener container with `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` (non-retryable: deserialization and business errors); a scheduled **saga timeout sweeper** that compensates sagas stuck in `SLOT_RESERVED` / `INVOICE_CREATED` beyond an SLA (e.g. 2 min); billing and pharmacy handlers must themselves be idempotent (`VoidInvoice` on an unknown or already voided invoice is a no-op).

### Billing participant (sketch)

```java
@KafkaListener(topics = "hms.billing-commands.events", groupId = "billing")
@Transactional
void on(ConsumerRecord<String,String> r, @Header("messageId") String id, @Header("eventType") String type) {
  if (!dedupe(id)) return;
  switch (type) {
    case "CreateInvoice" -> {
      var cmd = read(r.value(), CreateInvoice.class);
      try {
        var inv = invoices.save(Invoice.create(cmd));
        insurance.verify(inv);                                 // @CircuitBreaker + @Retry inside
        events.publishEvent(new DomainEvent("billing", cmd.appointmentId().toString(),
            "InvoiceCreated", new InvoiceCreated(inv.getId(), cmd.appointmentId(), cmd.prescriptionId())));
      } catch (DownstreamUnavailableException | InsuranceRejectedException ex) {
        events.publishEvent(new DomainEvent("billing", cmd.appointmentId().toString(),
            "InvoiceFailed", new InvoiceFailed(cmd.appointmentId(), ex.getMessage())));
      }
    }
    case "VoidInvoice" -> invoices.findById(read(r.value(), VoidInvoice.class).invoiceId()).ifPresent(Invoice::voidIt);
  }
}
```

Note: `billing` events use `aggregatetype = "billing"` -> topic `hms.billing.events`; commands use `"billing-commands"` -> `hms.billing-commands.events`. Debezium needs the same connector/outbox setup in every service's database.

### CQRS read model (Elasticsearch / OpenSearch projection)

Note: if the data tier is Amazon OpenSearch (Part 5), replace `ElasticsearchOperations` with the `opensearch-java` client or `spring-data-opensearch`; the Elasticsearch 8 client is not protocol-compatible.


```java
@Document(indexName = "appointment-view")
public record AppointmentView(@Id String appointmentId, String patientId, String doctorId,
                              String doctorName, Instant slot, String status, String failureReason,
                              Instant updatedAt) {}

@Component
@RequiredArgsConstructor
class AppointmentProjector {
  private final ElasticsearchOperations es;
  private final ObjectMapper mapper;

  @KafkaListener(topics = "hms.appointment.events", groupId = "appointment-projector")
  void project(ConsumerRecord<String, String> r, @Header("eventType") String type) throws Exception {
    var id = r.key();
    var existing = Optional.ofNullable(es.get(id, AppointmentView.class));
    var v = switch (type) {
      case "AppointmentRequested" -> {
        var e = mapper.readValue(r.value(), AppointmentRequested.class);
        yield new AppointmentView(id, e.patientId().toString(), e.doctorId().toString(),
            null, e.slot(), "PENDING", null, Instant.now());
      }
      case "AppointmentConfirmed" -> existing.map(a -> a.withStatus("CONFIRMED")).orElse(null);
      case "AppointmentFailed" -> {
        var e = mapper.readValue(r.value(), AppointmentFailed.class);
        yield existing.map(a -> a.withStatus("FAILED").withFailureReason(e.reason())).orElse(null);
      }
      default -> null;
    };
    if (v != null) es.save(v);                       // idempotent upsert keyed by appointmentId
  }
}
```

Read endpoints (`GET /api/appointments/search`) hit Elasticsearch only. ABAC still applies: filter the query by `patientId` ∈ the caller's permitted set, or enforce document-level security. Projections are rebuildable by replaying Kafka from offset 0 (retain topics long enough, or snapshot to S3).

---

## 7. Implementation checklist

- Secrets in AWS Secrets Manager; rotate DB and Debezium credentials; Kafka ACLs per service principal.
- PHI: field-level encryption for SSN and diagnosis text, TLS everywhere, never log payloads.
- Observability: Micrometer -> Prometheus/Grafana, OpenTelemetry tracing across Kafka headers, alerts on CB open rate, consumer lag, replication-slot lag, saga stuck counts.
- Testing: Testcontainers (Postgres, Kafka, Debezium) for outbox/saga integration tests; contract tests (Spring Cloud Contract) for event schemas; chaos tests for retry/CB behaviour.
- Schema evolution: versioned event payloads (`eventVersion` header), Schema Registry (Avro/JSON Schema) with backward-compatible changes.
- HIPAA/GDPR: immutable audit trail, retention policies, right-to-erasure via crypto-shredding of per-patient keys.
