package com.hms.appointment.api;

import com.hms.appointment.readmodel.AppointmentView;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Query side of CQRS: reads only the denormalised Mongo view, never the write database. */
@RestController
@RequestMapping("/api/appointments/search")
public class AppointmentSearchController {

  private final MongoTemplate mongo;

  public AppointmentSearchController(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  @PreAuthorize("hasAnyRole('RECEPTIONIST','DOCTOR','NURSE','PATIENT','ADMIN')")
  @GetMapping
  public List<AppointmentView> search(@RequestParam(required = false) String patientId,
                                      @RequestParam(required = false) String doctorId,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "50") int limit,
                                      Authentication auth) {
    var criteria = new Criteria();
    // Patients can only ever see their own appointments, whatever they ask for.
    if (AppointmentController.isPatient(auth)) patientId = auth.getName();
    if (patientId != null) criteria = criteria.and("patientId").is(patientId);
    if (doctorId != null) criteria = criteria.and("doctorId").is(doctorId);
    if (status != null) criteria = criteria.and("status").is(status);
    var query = new Query(criteria).with(Sort.by(Sort.Direction.DESC, "slot")).limit(Math.min(Math.max(limit, 1), 200));
    return mongo.find(query, AppointmentView.class);
  }
}
