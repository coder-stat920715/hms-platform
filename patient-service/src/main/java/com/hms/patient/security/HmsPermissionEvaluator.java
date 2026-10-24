package com.hms.patient.security;

import com.hms.common.domain.Sensitivity;
import jakarta.servlet.http.HttpServletRequest;
import java.io.Serializable;
import java.time.Clock;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * ABAC layered on RBAC. Usage: {@code @PreAuthorize("hasPermission(#id, 'Patient', 'READ')")}.
 * Subject attributes: user id, department, shift hours. Resource attributes: sensitivity, department, care team.
 * Environment attributes: source network, break-glass flag. Fails closed on any missing information.
 */
@Component
public class HmsPermissionEvaluator implements PermissionEvaluator {

  private static final Logger log = LoggerFactory.getLogger(HmsPermissionEvaluator.class);

  private final PatientResourceLookup lookup;
  private final AuditPublisher audit;
  private final Clock clock;
  private final SecurityPolicyProperties props;

  public HmsPermissionEvaluator(PatientResourceLookup lookup, AuditPublisher audit, Clock clock,
                                SecurityPolicyProperties props) {
    this.lookup = lookup;
    this.audit = audit;
    this.clock = clock;
    this.props = props;
  }

  @Override
  public boolean hasPermission(Authentication auth, Serializable targetId, String targetType, Object permission) {
    if (!(auth instanceof JwtAuthenticationToken jwtAuth) || targetId == null || permission == null) return false;
    if (!"Patient".equals(targetType)) return false;

    final Action action;
    final UUID patientId;
    final Subject subject;
    try {
      action = Action.valueOf(String.valueOf(permission));
      patientId = UUID.fromString(targetId.toString());
      subject = Subject.from(jwtAuth.getToken(), jwtAuth.getAuthorities());
    } catch (IllegalArgumentException | java.time.DateTimeException e) {
      return false;
    }

    PatientResource resource = lookup.find(patientId).orElse(null);
    if (resource == null) return false;                       // fail closed; do not reveal existence

    Decision d = decide(subject, resource, action, Env.fromCurrentRequest(props));
    audit.record(subject.userId(), patientId, action.name(), d.permit(), d.flagged(), d.reason());
    return d.permit();
  }

  @Override
  public boolean hasPermission(Authentication auth, Object target, Object permission) {
    return target instanceof PatientResource r && hasPermission(auth, r.patientId(), "Patient", permission);
  }

  // ---------------------------------------------------------------- policy
  Decision decide(Subject s, PatientResource r, Action a, Env env) {

    // Admins: non-clinical oversight. Read NORMAL records only; never write or delete clinical data.
    if (s.has("ROLE_ADMIN")) {
      return (a == Action.READ && r.sensitivity() == Sensitivity.NORMAL)
          ? Decision.permit("admin-read-normal") : Decision.deny("admin: read of normal records only");
    }

    // Patients: own record, read only.
    if (s.has("ROLE_PATIENT")) {
      return (a == Action.READ && s.userId().equals(r.patientId()))
          ? Decision.permit("self") : Decision.deny("patient: not self or not read");
    }

    // Front desk / billing: demographics only, never RESTRICTED or VIP.
    if (s.hasAny("ROLE_BILLING_CLERK", "ROLE_RECEPTIONIST")) {
      return (a == Action.READ && r.sensitivity() == Sensitivity.NORMAL)
          ? Decision.permit("admin-read-normal") : Decision.deny("insufficient role or sensitivity");
    }

    if (s.hasAny("ROLE_DOCTOR", "ROLE_NURSE")) {
      if (a == Action.DELETE) return Decision.deny("clinical delete forbidden");

      // Break-glass: doctors only, read only, always flagged for post-hoc review. Disabled unless configured.
      if (props.breakGlassEnabled() && env.breakGlass() && s.has("ROLE_DOCTOR") && a == Action.READ) {
        log.warn("BREAK_GLASS user={} patient={}", s.userId(), r.patientId());
        return Decision.permitFlagged("break-glass");
      }

      boolean onShift = s.onShift(LocalTime.now(clock));
      if (!onShift || !env.fromHospitalNetwork()) return Decision.deny("outside shift or trusted network");

      boolean sameDept = Objects.equals(s.department(), r.department());
      boolean careTeam = r.careTeam().contains(s.userId());

      return switch (r.sensitivity()) {
        case NORMAL -> (sameDept || careTeam) ? Decision.permit("department or care team")
            : Decision.deny("no treatment relationship");
        case RESTRICTED -> careTeam ? Decision.permit("care team") : Decision.deny("restricted: not on care team");
        case VIP -> (careTeam && s.has("ROLE_DOCTOR")) ? Decision.permit("vip care-team doctor")
            : Decision.deny("vip: care-team doctors only");
      };
    }
    return Decision.deny("no matching policy");
  }

  // ---------------------------------------------------------------- value types
  public enum Action { READ, WRITE, DELETE }

  record Decision(boolean permit, boolean flagged, String reason) {
    static Decision permit(String reason) { return new Decision(true, false, reason); }
    static Decision permitFlagged(String reason) { return new Decision(true, true, reason); }
    static Decision deny(String reason) { return new Decision(false, false, reason); }
  }

  record Subject(UUID userId, String department, LocalTime shiftStart, LocalTime shiftEnd, Set<String> roles) {

    static Subject from(Jwt jwt, Collection<? extends GrantedAuthority> authorities) {
      String start = jwt.getClaimAsString("shift_start");
      String end = jwt.getClaimAsString("shift_end");
      return new Subject(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("department"),
          LocalTime.parse(start == null ? "00:00" : start), LocalTime.parse(end == null ? "23:59" : end),
          authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet()));
    }

    boolean has(String role) { return roles.contains(role); }

    boolean hasAny(String... r) { return Arrays.stream(r).anyMatch(roles::contains); }

    boolean onShift(LocalTime t) {
      return shiftStart.isBefore(shiftEnd)
          ? !t.isBefore(shiftStart) && !t.isAfter(shiftEnd)
          : !t.isBefore(shiftStart) || !t.isAfter(shiftEnd);        // overnight shift wraps midnight
    }
  }

  /**
   * Environment attributes. The client address comes from request.getRemoteAddr(), which Tomcat resolves from
   * X-Forwarded-For only for requests arriving via internal proxies (server.forward-headers-strategy=native).
   */
  record Env(String ip, boolean fromHospitalNetwork, boolean breakGlass) {
    static Env fromCurrentRequest(SecurityPolicyProperties props) {
      var attrs = RequestContextHolder.getRequestAttributes();
      if (!(attrs instanceof ServletRequestAttributes sra)) return new Env("unknown", false, false);
      HttpServletRequest req = sra.getRequest();
      String ip = req.getRemoteAddr();
      boolean trusted = props.trustedCidrs().stream().anyMatch(c -> matches(c, ip));
      return new Env(ip, trusted, "true".equalsIgnoreCase(req.getHeader("X-Break-Glass")));
    }

    private static boolean matches(String cidr, String ip) {
      try {
        return new IpAddressMatcher(cidr).matches(ip);
      } catch (IllegalArgumentException e) {
        return false;
      }
    }
  }
}
