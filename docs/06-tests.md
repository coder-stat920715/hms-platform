# HMS Part 6: Test Suite (ABAC Matrix, Wiring, Saga, Outbox)

Covers the riskiest logic: the access-control policy and the saga/outbox paths. I wrote the tests against the code in `hms-architecture.md` and have not compiled or run them. Writing them turned up **three defects** in earlier parts; those come first.

---

## 0. Defects found while writing these tests

**Status: fixes 1 to 3 below have been applied to `hms-architecture.md` (1, 2) and `hms-services-part2.md` (3).** The snippets are kept here as the reference for what the tests expect.

| # | Where | Problem | Fix |
|---|-------|---------|-----|
| 1 | `HmsPermissionEvaluator.decide` (Part 1) | `ROLE_ADMIN` + `READ` matches no branch and is denied ("no matching policy"). Admins can't read anything, which is probably unintended. | Decide the policy explicitly. Suggested: admins read **NORMAL** records only. Fix below. |
| 2 | `AppointmentSagaOrchestrator` (Part 1) | A late `InvoiceCreated` that arrives after the saga has already `FAILED` (timeout sweeper, earlier compensation) is ignored by the stale-state guard, **leaving a live invoice with no appointment**. | Emit `VoidInvoice` for any `InvoiceCreated` that hits a terminal saga. Fix below. |
| 3 | `AllocationService.allocate` (Part 2) | Calls `publishFailureInNewTx` on `this`, so `REQUIRES_NEW` is skipped, and the transaction is marked rollback-only, which also rolls back the failure event. | Check all items first and only reserve if every one passes; publish `AllocationFailed` in the normal flow (no rollback needed). |

### Fix 1: admin read policy

```java
// in decide(...), replace the first line:
if (s.has("ROLE_ADMIN")) {
  return (a == Action.READ && r.sensitivity() == Sensitivity.NORMAL)
      ? Decision.permit("admin-read-normal")
      : Decision.deny("admin: read of normal records only");
}
```

### Fix 2: compensate orphaned invoices

```java
// in onBilling, replace the stale-state guard:
var saga = sagas.findById(UUID.fromString(rec.key())).orElse(null);
if (saga == null) return;
if (saga.getState() == SagaState.FAILED && "InvoiceCreated".equals(type)) {
  var ev = mapper.readValue(rec.value(), InvoiceCreated.class);
  command("billing", saga.getAppointmentId(), "VoidInvoice", new VoidInvoice(ev.invoiceId()));
  return;
}
if (saga.getState() != SagaState.SLOT_RESERVED) return;
```

### Fix 3: pharmacy allocation

```java
@Transactional
public void allocate(AllocateInventory cmd) {
  if (allocations.existsByAppointmentId(cmd.appointmentId())) return;
  var items = prescriptions.items(cmd.prescriptionId());

  for (var it : items) {                                        // 1. check
    if (stock.available(it.drugId()) < it.quantity()) {
      events.publishEvent(new DomainEvent("pharmacy", cmd.appointmentId().toString(),
          "AllocationFailed", new AllocationFailed(cmd.appointmentId(), "insufficient stock: " + it.drugId())));
      return;                                                   // nothing reserved yet, commit normally
    }
  }
  for (var it : items) {                                        // 2. reserve (atomic guard still protects races)
    if (stock.tryReserve(it.drugId(), it.quantity()) == 0)
      throw new ConcurrentStockChangeException(it.drugId());    // rolls back partial reservations; retried by listener
    allocations.save(new Allocation(cmd.appointmentId(), it.drugId(), it.quantity(), "ALLOCATED"));
  }
  events.publishEvent(new DomainEvent("pharmacy", cmd.appointmentId().toString(),
      "InventoryAllocated", new InventoryAllocated(cmd.appointmentId())));
}
```

The listener should retry `ConcurrentStockChangeException` (the second attempt re-checks and emits `AllocationFailed` if stock really is gone).

---

## 1. ABAC policy matrix (unit, no Spring)

`src/test/java/com/hms/patient/security/HmsPermissionEvaluatorPolicyTest.java`

```java
package com.hms.patient.security;   // same package as the evaluator: decide(...) is package-private

import static com.hms.patient.security.HmsPermissionEvaluator.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class HmsPermissionEvaluatorPolicyTest {

  // ---- fixtures ---------------------------------------------------------------
  static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
  static final UUID PATIENT = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
  static final Clock ON_SHIFT  = Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC); // 10:00
  static final Clock OFF_SHIFT = Clock.fixed(Instant.parse("2026-10-23T23:30:00Z"), ZoneOffset.UTC); // 23:30
  static final LocalTime SHIFT_START = LocalTime.of(8, 0), SHIFT_END = LocalTime.of(18, 0);

  enum Flag { OFF_SHIFT, SAME_DEPT, CARE_TEAM, TRUSTED_IP, BREAK_GLASS, SELF }

  record Case(String label, String role, Sensitivity sens, Action action, Set<Flag> flags, boolean expected) {
    @Override public String toString() { return label + " -> " + (expected ? "PERMIT" : "DENY"); }
  }

  static Case c(String label, String role, Sensitivity s, Action a, boolean expected, Flag... flags) {
    return new Case(label, role, s, a, flags.length == 0 ? EnumSet.noneOf(Flag.class) : EnumSet.copyOf(List.of(flags)), expected);
  }

  static Decision run(Case k) {
    var evaluator = new HmsPermissionEvaluator(null, null,
        k.flags().contains(Flag.OFF_SHIFT) ? OFF_SHIFT : ON_SHIFT);

    UUID userId = k.flags().contains(Flag.SELF) ? PATIENT : USER;
    var subject = new Subject(userId, "cardiology", SHIFT_START, SHIFT_END, Set.of(k.role()));
    var resource = new PatientResource(PATIENT, k.sens(),
        k.flags().contains(Flag.SAME_DEPT) ? "cardiology" : "oncology",
        k.flags().contains(Flag.CARE_TEAM) ? Set.of(USER) : Set.of());
    var env = new Env("10.1.1.1", k.flags().contains(Flag.TRUSTED_IP), k.flags().contains(Flag.BREAK_GLASS));
    return evaluator.decide(subject, resource, k.action(), env);
  }

  // ---- the matrix -------------------------------------------------------------
  static Stream<Case> matrix() {
    var N = Sensitivity.NORMAL; var R = Sensitivity.RESTRICTED; var V = Sensitivity.VIP;
    var READ = Action.READ; var WRITE = Action.WRITE; var DELETE = Action.DELETE;
    return Stream.of(
      // --- patient ---
      c("patient reads own record",               "ROLE_PATIENT", N, READ,  true,  Flag.SELF),
      c("patient reads own VIP record",           "ROLE_PATIENT", V, READ,  true,  Flag.SELF),
      c("patient reads someone else's record",    "ROLE_PATIENT", N, READ,  false),
      c("patient writes own record",              "ROLE_PATIENT", N, WRITE, false, Flag.SELF),
      c("patient deletes own record",             "ROLE_PATIENT", N, DELETE,false, Flag.SELF),

      // --- admin (after Fix 1) ---
      c("admin reads normal",                     "ROLE_ADMIN", N, READ,  true),
      c("admin reads restricted",                 "ROLE_ADMIN", R, READ,  false),
      c("admin reads VIP",                        "ROLE_ADMIN", V, READ,  false),
      c("admin writes",                           "ROLE_ADMIN", N, WRITE, false),
      c("admin deletes",                          "ROLE_ADMIN", N, DELETE,false),

      // --- reception / billing ---
      c("receptionist reads normal",              "ROLE_RECEPTIONIST", N, READ,  true),
      c("receptionist reads restricted",          "ROLE_RECEPTIONIST", R, READ,  false),
      c("receptionist reads VIP",                 "ROLE_RECEPTIONIST", V, READ,  false),
      c("receptionist writes normal",             "ROLE_RECEPTIONIST", N, WRITE, false),
      c("billing clerk reads normal",             "ROLE_BILLING_CLERK", N, READ,  true),
      c("billing clerk reads VIP",                "ROLE_BILLING_CLERK", V, READ,  false),
      c("billing clerk deletes",                  "ROLE_BILLING_CLERK", N, DELETE,false),

      // --- doctor: NORMAL ---
      c("doctor normal, same dept, on shift, trusted", "ROLE_DOCTOR", N, READ,  true,  Flag.SAME_DEPT, Flag.TRUSTED_IP),
      c("doctor normal, care team only",               "ROLE_DOCTOR", N, WRITE, true,  Flag.CARE_TEAM,  Flag.TRUSTED_IP),
      c("doctor normal, no relationship",              "ROLE_DOCTOR", N, READ,  false, Flag.TRUSTED_IP),
      c("doctor normal, off shift",                    "ROLE_DOCTOR", N, READ,  false, Flag.SAME_DEPT, Flag.TRUSTED_IP, Flag.OFF_SHIFT),
      c("doctor normal, untrusted network",            "ROLE_DOCTOR", N, READ,  false, Flag.SAME_DEPT),
      c("doctor deletes (forbidden for clinicians)",   "ROLE_DOCTOR", N, DELETE,false, Flag.CARE_TEAM, Flag.TRUSTED_IP),

      // --- doctor: RESTRICTED / VIP ---
      c("doctor restricted, same dept only",           "ROLE_DOCTOR", R, READ,  false, Flag.SAME_DEPT, Flag.TRUSTED_IP),
      c("doctor restricted, care team",                "ROLE_DOCTOR", R, READ,  true,  Flag.CARE_TEAM, Flag.TRUSTED_IP),
      c("doctor VIP, same dept only",                  "ROLE_DOCTOR", V, READ,  false, Flag.SAME_DEPT, Flag.TRUSTED_IP),
      c("doctor VIP, care team",                       "ROLE_DOCTOR", V, WRITE, true,  Flag.CARE_TEAM, Flag.TRUSTED_IP),

      // --- nurse ---
      c("nurse normal, same dept",                     "ROLE_NURSE", N, READ,  true,  Flag.SAME_DEPT, Flag.TRUSTED_IP),
      c("nurse restricted, care team",                 "ROLE_NURSE", R, READ,  true,  Flag.CARE_TEAM, Flag.TRUSTED_IP),
      c("nurse VIP, care team (doctors only)",         "ROLE_NURSE", V, READ,  false, Flag.CARE_TEAM, Flag.TRUSTED_IP),
      c("nurse deletes",                               "ROLE_NURSE", N, DELETE,false, Flag.CARE_TEAM, Flag.TRUSTED_IP),

      // --- break-glass ---
      c("doctor break-glass VIP read, no relationship","ROLE_DOCTOR", V, READ,  true,  Flag.BREAK_GLASS),
      c("doctor break-glass, off shift, untrusted",    "ROLE_DOCTOR", R, READ,  true,  Flag.BREAK_GLASS, Flag.OFF_SHIFT),
      c("doctor break-glass WRITE",                    "ROLE_DOCTOR", V, WRITE, false, Flag.BREAK_GLASS),
      c("doctor break-glass DELETE",                   "ROLE_DOCTOR", V, DELETE,false, Flag.BREAK_GLASS),
      c("nurse break-glass (doctors only)",            "ROLE_NURSE",  V, READ,  false, Flag.BREAK_GLASS),
      c("receptionist break-glass",                    "ROLE_RECEPTIONIST", V, READ, false, Flag.BREAK_GLASS),
      c("patient break-glass on other's record",       "ROLE_PATIENT", N, READ, false, Flag.BREAK_GLASS),

      // --- unknown role ---
      c("unknown role",                                "ROLE_JANITOR", N, READ, false, Flag.SAME_DEPT, Flag.TRUSTED_IP)
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("matrix")
  void policy(Case k) {
    assertThat(run(k).permit()).as(k.label()).isEqualTo(k.expected());
  }

  @Test
  void breakGlassIsFlaggedForReview() {
    var k = c("bg", "ROLE_DOCTOR", Sensitivity.VIP, Action.READ, true, Flag.BREAK_GLASS);
    assertThat(run(k).flagged()).isTrue();
  }

  @Test
  void normalAccessIsNotFlagged() {
    var k = c("n", "ROLE_DOCTOR", Sensitivity.NORMAL, Action.READ, true, Flag.CARE_TEAM, Flag.TRUSTED_IP);
    assertThat(run(k).flagged()).isFalse();
  }

  // ---- invariants over the full cross-product ----------------------------------
  static final List<String> ROLES = List.of("ROLE_ADMIN", "ROLE_DOCTOR", "ROLE_PATIENT",
      "ROLE_NURSE", "ROLE_RECEPTIONIST", "ROLE_BILLING_CLERK", "ROLE_UNKNOWN");

  static Stream<Case> everything() {
    var out = new ArrayList<Case>();
    for (var role : ROLES) for (var s : Sensitivity.values()) for (var a : Action.values())
      for (int mask = 0; mask < 64; mask++) {
        var flags = EnumSet.noneOf(Flag.class);
        for (var f : Flag.values()) if ((mask & (1 << f.ordinal())) != 0) flags.add(f);
        out.add(new Case(role + "/" + s + "/" + a + "/" + flags, role, s, a, flags, false));
      }
    return out.stream();
  }

  @ParameterizedTest @MethodSource("everything")
  void noRoleEverDeletes(Case k) {
    if (k.action() == Action.DELETE) assertThat(run(k).permit()).isFalse();
  }

  @ParameterizedTest @MethodSource("everything")
  void patientsNeverWriteAndNeverReadOthers(Case k) {
    if (!k.role().equals("ROLE_PATIENT")) return;
    boolean self = k.flags().contains(Flag.SELF);
    boolean permit = run(k).permit();
    if (k.action() != Action.READ) assertThat(permit).isFalse();
    if (!self) assertThat(permit).isFalse();
  }

  @ParameterizedTest @MethodSource("everything")
  void onlyDoctorsCanBreakGlassAndOnlyToRead(Case k) {
    if (!k.flags().contains(Flag.BREAK_GLASS)) return;
    var d = run(k);
    if (d.flagged()) {
      assertThat(k.role()).isEqualTo("ROLE_DOCTOR");
      assertThat(k.action()).isEqualTo(Action.READ);
    }
  }

  @ParameterizedTest @MethodSource("everything")
  void vipRequiresCareTeamDoctorOrBreakGlassOrSelf(Case k) {
    if (k.sens() != Sensitivity.VIP || !run(k).permit()) return;
    boolean careTeamDoctor = k.role().equals("ROLE_DOCTOR") && k.flags().contains(Flag.CARE_TEAM);
    boolean breakGlass = k.flags().contains(Flag.BREAK_GLASS) && k.role().equals("ROLE_DOCTOR");
    boolean self = k.role().equals("ROLE_PATIENT") && k.flags().contains(Flag.SELF);
    assertThat(careTeamDoctor || breakGlass || self).as(k.label()).isTrue();
  }

  // ---- shift boundaries -----------------------------------------------------------
  @Test
  void shiftBoundariesAreInclusive() {
    var day = new Subject(USER, "x", LocalTime.of(8, 0), LocalTime.of(18, 0), Set.of());
    assertThat(day.onShift(LocalTime.of(8, 0))).isTrue();
    assertThat(day.onShift(LocalTime.of(18, 0))).isTrue();
    assertThat(day.onShift(LocalTime.of(7, 59))).isFalse();
    assertThat(day.onShift(LocalTime.of(18, 1))).isFalse();
  }

  @Test
  void overnightShiftWrapsPastMidnight() {
    var night = new Subject(USER, "x", LocalTime.of(22, 0), LocalTime.of(6, 0), Set.of());
    assertThat(night.onShift(LocalTime.of(23, 30))).isTrue();
    assertThat(night.onShift(LocalTime.of(3, 0))).isTrue();
    assertThat(night.onShift(LocalTime.of(12, 0))).isFalse();
  }
}
```

The exhaustive tests cover 7 roles × 3 sensitivities × 3 actions × 64 context combinations (about 4,000 cases) and run in milliseconds because they are pure functions. The invariants encode the rules auditors care about, so any future policy edit that breaks one fails the build.

---

## 2. Security wiring (`@PreAuthorize` end to end)

Proves the evaluator is actually invoked, fails closed when the resource is missing, and produces 403, not 200 or 500.

```java
@WebMvcTest(PatientController.class)
@Import({ServiceSecurityConfig.class, HmsPermissionEvaluator.class, PatientControllerSecurityTest.TestBeans.class})
class PatientControllerSecurityTest {

  @TestConfiguration
  static class TestBeans {
    @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC); }
  }

  @Autowired MockMvc mvc;
  @MockBean PatientResourceLookup lookup;
  @MockBean AuditPublisher audit;
  @MockBean JwtDecoder jwtDecoder;               // resource-server needs one; jwt() post-processor bypasses it

  static final UUID DOCTOR = UUID.randomUUID(), PATIENT = UUID.randomUUID();

  private RequestPostProcessor doctor(String dept) {
    return jwt().jwt(j -> j.subject(DOCTOR.toString())
                           .claim("department", dept)
                           .claim("shift_start", "08:00").claim("shift_end", "18:00"))
                .authorities(new SimpleGrantedAuthority("ROLE_DOCTOR"));
  }
  private static RequestPostProcessor fromHospital() {
    return r -> { r.setRemoteAddr("10.1.2.3"); return r; };
  }

  @Test void noToken_is401() throws Exception {
    mvc.perform(get("/api/patients/{id}", PATIENT)).andExpect(status().isUnauthorized());
  }

  @Test void careTeamDoctor_on_hospital_network_is200() throws Exception {
    when(lookup.find(PATIENT)).thenReturn(Optional.of(
        new PatientResource(PATIENT, Sensitivity.RESTRICTED, "oncology", Set.of(DOCTOR))));
    mvc.perform(get("/api/patients/{id}", PATIENT).with(doctor("cardiology")).with(fromHospital()))
       .andExpect(status().isOk());
    verify(audit).record(eq(DOCTOR), eq(PATIENT), any(), argThat(Decision::permit));
  }

  @Test void doctor_outside_care_team_on_restricted_is403_and_audited() throws Exception {
    when(lookup.find(PATIENT)).thenReturn(Optional.of(
        new PatientResource(PATIENT, Sensitivity.RESTRICTED, "cardiology", Set.of())));
    mvc.perform(get("/api/patients/{id}", PATIENT).with(doctor("cardiology")).with(fromHospital()))
       .andExpect(status().isForbidden());
    verify(audit).record(eq(DOCTOR), eq(PATIENT), any(), argThat(d -> !d.permit()));
  }

  @Test void unknownPatient_failsClosed_403() throws Exception {
    when(lookup.find(PATIENT)).thenReturn(Optional.empty());
    mvc.perform(get("/api/patients/{id}", PATIENT).with(doctor("cardiology")).with(fromHospital()))
       .andExpect(status().isForbidden());                 // not 404: don't reveal existence
  }

  @Test void clientCannotForgeBreakGlassWithoutDoctorRole() throws Exception {
    when(lookup.find(PATIENT)).thenReturn(Optional.of(
        new PatientResource(PATIENT, Sensitivity.VIP, "oncology", Set.of())));
    var nurse = jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                     .authorities(new SimpleGrantedAuthority("ROLE_NURSE"));
    mvc.perform(get("/api/patients/{id}", PATIENT).with(nurse).header("X-Break-Glass", "true"))
       .andExpect(status().isForbidden());
  }
}
```

The last test only documents the evaluator; in production the gateway must also strip client-supplied `X-Break-Glass` and `X-Forwarded-For`. Add a gateway test that these headers are removed from inbound requests (see section 5).

---

## 3. Saga orchestrator (Mockito, every failure path)

```java
@ExtendWith(MockitoExtension.class)
class AppointmentSagaOrchestratorTest {

  @Mock AppointmentSagaRepository sagas;
  @Mock AppointmentRepository appointments;
  @Mock SlotRepository slots;
  @Mock ProcessedMessageRepository processed;
  @Mock ApplicationEventPublisher events;
  ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  AppointmentSagaOrchestrator orchestrator;

  UUID id = UUID.randomUUID(), invoiceId = UUID.randomUUID(), patientId = UUID.randomUUID(), doctorId = UUID.randomUUID();

  @BeforeEach void setUp() {
    orchestrator = new AppointmentSagaOrchestrator(sagas, appointments, slots, processed, events, mapper);
  }

  private AppointmentSaga saga(SagaState state, boolean pharmacy) {
    var s = new AppointmentSaga();
    s.setAppointmentId(id); s.setState(state); s.setNeedsPharmacy(pharmacy);
    when(sagas.findById(id)).thenReturn(Optional.of(s));
    return s;
  }

  private ConsumerRecord<String, String> rec(Object payload) throws Exception {
    return new ConsumerRecord<>("t", 0, 0, id.toString(), mapper.writeValueAsString(payload));
  }

  private List<DomainEvent> published() {
    var cap = ArgumentCaptor.forClass(DomainEvent.class);
    verify(events, atLeast(0)).publishEvent(cap.capture());
    return cap.getAllValues();
  }

  private static Predicate<DomainEvent> type(String t) { return e -> e.type().equals(t); }

  // ---- happy paths -----------------------------------------------------------------
  @Test void start_reservesSlot_andRequestsInvoice() {
    var slot = mock(Slot.class);
    when(slots.lockFree(doctorId, Instant.parse("2026-11-01T09:00:00Z"))).thenReturn(Optional.of(slot));

    orchestrator.start(new AppointmentRequested(id, patientId, doctorId,
        Instant.parse("2026-11-01T09:00:00Z"), false, BigDecimal.TEN));

    verify(slot).reserve(id);
    assertThat(published()).anyMatch(type("CreateInvoice"));
  }

  @Test void invoiceCreated_withoutPharmacy_confirms() throws Exception {
    var s = saga(SagaState.SLOT_RESERVED, false);
    orchestrator.onBilling(rec(new InvoiceCreated(invoiceId, id, null)), UUID.randomUUID().toString(), "InvoiceCreated");
    assertThat(s.getState()).isEqualTo(SagaState.CONFIRMED);
    assertThat(published()).anyMatch(type("AppointmentConfirmed"));
  }

  @Test void invoiceCreated_withPharmacy_requestsAllocation() throws Exception {
    var s = saga(SagaState.SLOT_RESERVED, true);
    orchestrator.onBilling(rec(new InvoiceCreated(invoiceId, id, UUID.randomUUID())), UUID.randomUUID().toString(), "InvoiceCreated");
    assertThat(s.getState()).isEqualTo(SagaState.INVOICE_CREATED);
    assertThat(published()).anyMatch(type("AllocateInventory"));
  }

  @Test void inventoryAllocated_confirms() {
    var s = saga(SagaState.INVOICE_CREATED, true);
    orchestrator.onPharmacy(new ConsumerRecord<>("t", 0, 0, id.toString(), "{}"), UUID.randomUUID().toString(), "InventoryAllocated");
    assertThat(s.getState()).isEqualTo(SagaState.CONFIRMED);
  }

  // ---- compensation ------------------------------------------------------------------
  @Test void slotUnavailable_failsImmediately_noCommandsSent() {
    when(slots.lockFree(any(), any())).thenReturn(Optional.empty());
    orchestrator.start(new AppointmentRequested(id, patientId, doctorId, Instant.now(), false, BigDecimal.TEN));
    assertThat(published()).extracting(DomainEvent::type).containsExactly("AppointmentFailed");
  }

  @Test void invoiceFailed_releasesSlot_withoutVoidingAnything() throws Exception {
    var s = saga(SagaState.SLOT_RESERVED, false);
    orchestrator.onBilling(rec(new InvoiceFailed(id, "insurance rejected")), UUID.randomUUID().toString(), "InvoiceFailed");
    verify(slots).release(id);
    assertThat(s.getState()).isEqualTo(SagaState.FAILED);
    assertThat(published()).noneMatch(type("VoidInvoice")).anyMatch(type("AppointmentFailed"));
  }

  @Test void allocationFailed_voidsInvoice_andReleasesSlot() {
    var s = saga(SagaState.INVOICE_CREATED, true);
    s.setInvoiceId(invoiceId);
    orchestrator.onPharmacy(new ConsumerRecord<>("t", 0, 0, id.toString(), "{}"), UUID.randomUUID().toString(), "AllocationFailed");
    verify(slots).release(id);
    assertThat(published()).anyMatch(type("VoidInvoice")).anyMatch(type("AppointmentFailed"));
    assertThat(s.getState()).isEqualTo(SagaState.FAILED);
  }

  // ---- idempotency and ordering --------------------------------------------------------
  @Test void duplicateMessage_isIgnored() throws Exception {
    when(processed.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));
    orchestrator.onBilling(rec(new InvoiceCreated(invoiceId, id, null)), UUID.randomUUID().toString(), "InvoiceCreated");
    verifyNoInteractions(sagas);
    verify(events, never()).publishEvent(any());
  }

  @Test void outOfOrderReply_inWrongState_isIgnored() {
    var s = saga(SagaState.CONFIRMED, false);
    orchestrator.onPharmacy(new ConsumerRecord<>("t", 0, 0, id.toString(), "{}"), UUID.randomUUID().toString(), "AllocationFailed");
    assertThat(s.getState()).isEqualTo(SagaState.CONFIRMED);              // a confirmed booking isn't undone by a stray event
    verify(events, never()).publishEvent(any());
  }

  // ---- defect 2 regression ----------------------------------------------------------------
  @Test void lateInvoiceCreated_afterSagaFailed_isVoided() throws Exception {
    saga(SagaState.FAILED, false);
    orchestrator.onBilling(rec(new InvoiceCreated(invoiceId, id, null)), UUID.randomUUID().toString(), "InvoiceCreated");
    assertThat(published()).filteredOn(type("VoidInvoice")).hasSize(1);
    verify(appointments, never()).markConfirmed(any());
  }
}
```

Add one more for the timeout sweeper once it exists: a saga stuck in `SLOT_RESERVED` past the SLA is compensated, and one stuck in `INVOICE_CREATED` also voids the invoice.

---

## 4. Outbox atomicity and idempotency (Testcontainers)

```java
@SpringBootTest
@Testcontainers
class OutboxAtomicityIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

  @Autowired TransactionTemplate tx;
  @Autowired AppointmentRepository appointments;
  @Autowired OutboxRepository outbox;
  @Autowired ApplicationEventPublisher events;
  @Autowired ProcessedMessageRepository processed;

  @Test void rollback_removesBothTheStateChange_andTheOutboxRow() {
    long a0 = appointments.count(), o0 = outbox.count();
    assertThatThrownBy(() -> tx.executeWithoutResult(st -> {
      var appt = appointments.save(Appointment.requested(sampleCommand()));
      events.publishEvent(new DomainEvent("appointment", appt.getId().toString(),
          "AppointmentRequested", new AppointmentRequested(appt.getId(), null, null, null, false, null)));
      throw new IllegalStateException("boom after publish");
    })).isInstanceOf(IllegalStateException.class);

    assertThat(appointments.count()).isEqualTo(a0);
    assertThat(outbox.count()).isEqualTo(o0);
  }

  @Test void commit_writesBothRows_inOneTransaction() {
    long a0 = appointments.count(), o0 = outbox.count();
    tx.executeWithoutResult(st -> {
      var appt = appointments.save(Appointment.requested(sampleCommand()));
      events.publishEvent(new DomainEvent("appointment", appt.getId().toString(),
          "AppointmentRequested", new AppointmentRequested(appt.getId(), null, null, null, false, null)));
    });
    assertThat(appointments.count()).isEqualTo(a0 + 1);
    assertThat(outbox.count()).isEqualTo(o0 + 1);       // (or 0 delta if you delete rows after insert)
  }

  @Test void processedMessage_primaryKey_blocksDuplicates() {
    var id = UUID.randomUUID();
    processed.saveAndFlush(new ProcessedMessage(id, "appointment-saga"));
    assertThatThrownBy(() -> processed.saveAndFlush(new ProcessedMessage(id, "appointment-saga")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
```

If you adopt "delete the outbox row right after insert" (Part 1), assert on the Debezium output instead (next section), because the row will not be visible after commit.

---

## 5. Remaining tests worth adding

- **Debezium end to end (slow, nightly):** Testcontainers `KafkaContainer` + `DebeziumContainer` + Postgres with `wal_level=logical`. Register the connector from section 4.3 of Part 1, commit an outbox row, and assert one record arrives on `hms.appointment.events` with key = aggregate ID and `eventType` / `messageId` headers. Restart Connect and assert no duplicate beyond what the consumer dedupes.
- **Saga E2E:** run appointment, billing and pharmacy against the same Kafka container. Scenarios: happy path; billing outage (Resilience4j retries then `InvoiceFailed`); out-of-stock (invoice voided, slot released); duplicate delivery of every message; consumer crash between processing and commit.
- **Resilience:** WireMock stub returning 503 three times then 200. Assert four attempts with delays in `[0, 500ms]`, `[0, 1s]`, `[0, 2s]` (full jitter), the breaker opening after the failure-rate threshold, and the fallback exception.
- **Gateway:** `WebTestClient` tests that expired and wrong-issuer JWTs get 401, `X-Break-Glass` and `X-Forwarded-For` from clients are stripped, the 101st request in a window gets 429, and responses carry HSTS and `Cache-Control: no-store`.
- **Architecture:** ArchUnit rules that controllers never call repositories directly, only `*Outbox*` classes touch `outbox`, and no service imports another service's domain package.
- **Contract:** Spring Cloud Contract (or Pact) for each event payload, so a producer change that breaks a consumer fails CI.

Run order in CI: unit and matrix (seconds) -> `@WebMvcTest` -> Testcontainers ITs -> nightly Debezium and saga E2E.
