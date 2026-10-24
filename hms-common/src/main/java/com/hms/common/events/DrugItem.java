package com.hms.common.events;

import java.util.UUID;

public record DrugItem(UUID drugId, int quantity) {}
