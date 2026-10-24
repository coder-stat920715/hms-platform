package com.hms.patient.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.hms.common.domain.Sensitivity;
import com.hms.patient.security.HmsPermissionEvaluator.Action;
import com.hms.patient.security.HmsPermissionEvaluator.Decision;
import com.hms.patient.security.HmsPermissionEvaluator.Env;
import com.hms.patient.security.HmsPermissionEvaluator.Subject;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Pure-function tests of the ABAC policy: a hand-written matrix plus invariants over the full cross-product. */
class HmsPermissionEvaluatorPolicyTest {

  static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
  static final UUID PATIENT = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
  static final Clock ON_SHIFT = Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC);   // 10:00
  static final Clock OFF_SHIFT = Clock.fixed(Instant.parse("2026-10-23T23:30:00Z"), ZoneOffset.UTC);  // 23:30
  static final LocalTime SHIFT_START = LocalTime.of(8, 0);
  static final LocalTime SHIFT_END = LocalTime.of(18, 0);

  enum Flag { OFF_SHIFT, SAME_DEPT, CARE_TEAM, TRUSTED_IP, BREAK_GLASS, SELF }

  record Case(String label, String role, Sensitivity sens, Action action, Set<Flag> flags, boolean expected) {
    @Override public String toString() { return label + " -> " + (expected ? "PERMIT" : "DENY"); }
  }

  static Case c(String label, String role, Sensitivity s, Action a, boolean expected, Flag... flags) {
    Set<Flag> set = EnumSet.noneOf(Flag.class);
    set.addAll(List.of(flags));
    return new Case(label, role, s, a, set, expected);
  }

  static Decision run(Case k) {
    var props = new SecurityPolicyProperties(true, List.of("10.0.0.0/8"));
    var evaluator = new HmsPermissionEvaluator(null, null,
        k.flags().contains(Flag.OFF_SHIFT) ? OFF_SHIFT : ON_SHIFT, props);

    UUID userId = k.flags().contains(Flag.SELF) ? PATIENT : USER;
    var subject = new Subject(userId, "cardiology", SHIFT_START, SHIFT_END, Set.of(k.role()));
    var resource = new PatientResource(PATIENT, k.sens(),
        k.flags().contains(Flag.SAME_DEPT) ? "cardiology" : "oncology",
        k.flags().contains(Flag.CARE_TEAM) ? Set.of(USER) : Set.of());
    var env = new Env("10.1.1.1", k.flags().contains(Flag.TRUSTED_IP), k.flags().contains(Flag.BREAK_GLASS));
    return evaluator.decide(subject, resource, k.action(), env);
  }

  static Stream<Case> matrix() {
    var N = Sensitivity.NORMAL;
    var R = Sensitivity.RESTRICTED;
    var V = Sensitivity.VIP;
    var READ = Action.READ;
    var WRITE = Action.WRITE;
    var DELETE = Action.DELETE;
    return Stream.of(
        c("patient reads own record", "ROLE_PATIENT", N, READ, true, Flag.SELF),
        c("patient reads own VIP record", "ROLE_PATIENT", V, READ, true, Flag.SELF),
        c("patient reads someone else's record", "ROLE_PATIENT", N, READ, false),
        c("patient writes own record", "ROLE_PATIENT", N, WRITE, false, Flag.SELF),
        c("patient deletes own record", "ROLE_PATIENT", N, DELETE, false, Flag.SELF),

        c("admin reads normal", "ROLE_ADMIN", N, READ, true),
        c("admin reads restricted", "ROLE_ADMIN", R, READ, false),
        c("admin reads VIP", "ROLE_ADMIN", V, READ, false),
        c("admin writes", "ROLE_ADMIN", N, WRITE, false),
        c("admin deletes", "ROLE_ADMIN", N, DELETE, false),

        c("receptionist reads normal", "ROLE_RECEPTIONIST", N, READ, true),
        c("receptionist reads restricted", "ROLE_RECEPTIONIST", R, READ, false),
        c("receptionist reads VIP", "ROLE_RECEPTIONIST", V, READ, false),
        c("receptionist writes normal", "ROLE_RECEPTIONIST", N, WRITE, false),
        c("billing clerk reads normal", "ROLE_BILLING_CLERK", N, READ, true),
        c("billing clerk reads VIP", "ROLE_BILLING_CLERK", V, READ, false),
        c("billing clerk deletes", "ROLE_BILLING_CLERK", N, DELETE, false),

        c("doctor normal, same dept, on shift, trusted", "ROLE_DOCTOR", N, READ, true, Flag.SAME_DEPT, Flag.TRUSTED_IP),
        c("doctor normal, care team only, write", "ROLE_DOCTOR", N, WRITE, true, Flag.CARE_TEAM, Flag.TRUSTED_IP),
        c("doctor normal, no relationship", "ROLE_DOCTOR", N, READ, false, Flag.TRUSTED_IP),
        c("doctor normal, off shift", "ROLE_DOCTOR", N, READ, false, Flag.SAME_DEPT, Flag.TRUSTED_IP, Flag.OFF_SHIFT),
        c("doctor normal, untrusted network", "ROLE_DOCTOR", N, READ, false, Flag.SAME_DEPT),
        c("doctor deletes", "ROLE_DOCTOR", N, DELETE, false, Flag.CARE_TEAM, Flag.TRUSTED_IP),

        c("doctor restricted, same dept only", "ROLE_DOCTOR", R, READ, false, Flag.SAME_DEPT, Flag.TRUSTED_IP),
        c("doctor restricted, care team", "ROLE_DOCTOR", R, READ, true, Flag.CARE_TEAM, Flag.TRUSTED_IP),
        c("doctor VIP, same dept only", "ROLE_DOCTOR", V, READ, false, Flag.SAME_DEPT, Flag.TRUSTED_IP),
        c("doctor VIP, care team", "ROLE_DOCTOR", V, WRITE, true, Flag.CARE_TEAM, Flag.TRUSTED_IP),

        c("nurse normal, same dept", "ROLE_NURSE", N, READ, true, Flag.SAME_DEPT, Flag.TRUSTED_IP),
        c("nurse restricted, care team", "ROLE_NURSE", R, READ, true, Flag.CARE_TEAM, Flag.TRUSTED_IP),
        c("nurse VIP, care team (doctors only)", "ROLE_NURSE", V, READ, false, Flag.CARE_TEAM, Flag.TRUSTED_IP),
        c("nurse deletes", "ROLE_NURSE", N, DELETE, false, Flag.CARE_TEAM, Flag.TRUSTED_IP),

        c("doctor break-glass VIP read, no relationship", "ROLE_DOCTOR", V, READ, true, Flag.BREAK_GLASS),
        c("doctor break-glass, off shift, untrusted", "ROLE_DOCTOR", R, READ, true, Flag.BREAK_GLASS, Flag.OFF_SHIFT),
        c("doctor break-glass WRITE", "ROLE_DOCTOR", V, WRITE, false, Flag.BREAK_GLASS),
        c("doctor break-glass DELETE", "ROLE_DOCTOR", V, DELETE, false, Flag.BREAK_GLASS),
        c("nurse break-glass (doctors only)", "ROLE_NURSE", V, READ, false, Flag.BREAK_GLASS),
        c("receptionist break-glass", "ROLE_RECEPTIONIST", V, READ, false, Flag.BREAK_GLASS),

        c("unknown role", "ROLE_JANITOR", N, READ, false, Flag.SAME_DEPT, Flag.TRUSTED_IP));
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
  void breakGlassIgnoredWhenDisabled() {
    var props = new SecurityPolicyProperties(false, List.of("10.0.0.0/8"));
    var evaluator = new HmsPermissionEvaluator(null, null, ON_SHIFT, props);
    var subject = new Subject(USER, "cardiology", SHIFT_START, SHIFT_END, Set.of("ROLE_DOCTOR"));
    var resource = new PatientResource(PATIENT, Sensitivity.VIP, "oncology", Set.of());
    var d = evaluator.decide(subject, resource, Action.READ, new Env("10.1.1.1", true, true));
    assertThat(d.permit()).isFalse();
  }

  // ---- invariants over the full cross-product (7 roles x 3 sensitivities x 3 actions x 64 contexts) ----
  static final List<String> ROLES = List.of("ROLE_ADMIN", "ROLE_DOCTOR", "ROLE_PATIENT", "ROLE_NURSE",
      "ROLE_RECEPTIONIST", "ROLE_BILLING_CLERK", "ROLE_UNKNOWN");

  static Stream<Case> everything() {
    var out = new ArrayList<Case>();
    for (var role : ROLES) {
      for (var s : Sensitivity.values()) {
        for (var a : Action.values()) {
          for (int mask = 0; mask < 64; mask++) {
            var flags = EnumSet.noneOf(Flag.class);
            for (var f : Flag.values()) if ((mask & (1 << f.ordinal())) != 0) flags.add(f);
            out.add(new Case(role + "/" + s + "/" + a + "/" + flags, role, s, a, flags, false));
          }
        }
      }
    }
    return out.stream();
  }

  @ParameterizedTest
  @MethodSource("everything")
  void noRoleEverDeletes(Case k) {
    if (k.action() == Action.DELETE) assertThat(run(k).permit()).isFalse();
  }

  @ParameterizedTest
  @MethodSource("everything")
  void patientsNeverWriteAndNeverReadOthers(Case k) {
    if (!k.role().equals("ROLE_PATIENT")) return;
    boolean permit = run(k).permit();
    if (k.action() != Action.READ) assertThat(permit).isFalse();
    if (!k.flags().contains(Flag.SELF)) assertThat(permit).isFalse();
  }

  @ParameterizedTest
  @MethodSource("everything")
  void onlyDoctorsBreakGlass_andOnlyToRead(Case k) {
    var d = run(k);
    if (d.flagged()) {
      assertThat(k.role()).isEqualTo("ROLE_DOCTOR");
      assertThat(k.action()).isEqualTo(Action.READ);
    }
  }

  @ParameterizedTest
  @MethodSource("everything")
  void vipNeedsCareTeamDoctorOrBreakGlassOrSelf(Case k) {
    if (k.sens() != Sensitivity.VIP || !run(k).permit()) return;
    boolean careTeamDoctor = k.role().equals("ROLE_DOCTOR") && k.flags().contains(Flag.CARE_TEAM);
    boolean breakGlass = k.role().equals("ROLE_DOCTOR") && k.flags().contains(Flag.BREAK_GLASS);
    boolean self = k.role().equals("ROLE_PATIENT") && k.flags().contains(Flag.SELF);
    assertThat(careTeamDoctor || breakGlass || self).as(k.label()).isTrue();
  }

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
