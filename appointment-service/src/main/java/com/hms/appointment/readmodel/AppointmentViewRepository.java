package com.hms.appointment.readmodel;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface AppointmentViewRepository extends MongoRepository<AppointmentView, String> {}
