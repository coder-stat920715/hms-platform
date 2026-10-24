CREATE TABLE appointment (
  id             UUID           PRIMARY KEY,
  patient_id     UUID           NOT NULL,
  doctor_id      UUID           NOT NULL,
  slot_start     TIMESTAMPTZ    NOT NULL,
  fee            NUMERIC(12, 2) NOT NULL,
  status         VARCHAR(20)    NOT NULL,
  failure_reason VARCHAR(500),
  created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
  version        BIGINT         NOT NULL DEFAULT 0
);
CREATE INDEX idx_appointment_patient ON appointment (patient_id);

CREATE TABLE slot (
  id             UUID        PRIMARY KEY,
  doctor_id      UUID        NOT NULL,
  start_time     TIMESTAMPTZ NOT NULL,
  end_time       TIMESTAMPTZ NOT NULL,
  status         VARCHAR(20) NOT NULL,
  appointment_id UUID,
  version        BIGINT      NOT NULL DEFAULT 0,
  CONSTRAINT uq_slot_doctor_start UNIQUE (doctor_id, start_time)
);
CREATE INDEX idx_slot_appointment ON slot (appointment_id);

CREATE TABLE appointment_saga (
  appointment_id UUID         PRIMARY KEY,
  state          VARCHAR(30)  NOT NULL,
  invoice_id     UUID,
  needs_pharmacy BOOLEAN      NOT NULL,
  items_json     TEXT         NOT NULL,
  failure_reason VARCHAR(500),
  created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
  version        BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_saga_state_updated ON appointment_saga (state, updated_at);

-- Transactional outbox (read by Debezium via the WAL) and idempotent-consumer ledger
CREATE TABLE outbox (
  id            UUID         PRIMARY KEY,
  aggregatetype VARCHAR(100) NOT NULL,
  aggregateid   VARCHAR(100) NOT NULL,
  type          VARCHAR(100) NOT NULL,
  payload       TEXT         NOT NULL,
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
ALTER TABLE outbox REPLICA IDENTITY FULL;
CREATE PUBLICATION dbz_outbox FOR TABLE outbox;

CREATE TABLE processed_message (
  dedupe_key   VARCHAR(200) PRIMARY KEY,
  processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
