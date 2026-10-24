package com.hms.common.events;

import java.util.UUID;

public record LowStock(UUID drugId, String name, int available) {}
