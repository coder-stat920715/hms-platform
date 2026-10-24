package com.hms.appointment.service;

import com.hms.appointment.domain.Slot;
import com.hms.appointment.domain.SlotRepository;
import com.hms.common.error.BusinessValidationException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Doctor availability: consecutive bookable slots. */
@Service
public class SlotService {

  private final SlotRepository slots;

  public SlotService(SlotRepository slots) {
    this.slots = slots;
  }

  @Transactional
  public List<Slot> publish(UUID doctorId, Instant start, int slotMinutes, int count) {
    if (slotMinutes < 5 || slotMinutes > 240) throw new BusinessValidationException("slotMinutes must be 5..240");
    if (count < 1 || count > 100) throw new BusinessValidationException("count must be 1..100");
    List<Slot> created = new ArrayList<>();
    Instant cursor = start;
    for (int i = 0; i < count; i++) {
      Instant end = cursor.plus(Duration.ofMinutes(slotMinutes));
      created.add(new Slot(doctorId, cursor, end));
      cursor = end;
    }
    return slots.saveAll(created);
  }

  @Transactional(readOnly = true)
  public List<Slot> free(UUID doctorId, Instant from, Instant to) {
    return slots.findFree(doctorId, from, to);
  }
}
