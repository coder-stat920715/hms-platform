package com.hms.common.events;

import java.util.List;
import java.util.UUID;

public record AllocateInventory(UUID appointmentId, List<DrugItem> items) {}
