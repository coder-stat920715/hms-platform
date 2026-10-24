CREATE TABLE drug_stock (
  drug_id            UUID         PRIMARY KEY,
  name               VARCHAR(200) NOT NULL,
  on_hand            INTEGER      NOT NULL CHECK (on_hand >= 0),
  reserved           INTEGER      NOT NULL DEFAULT 0 CHECK (reserved >= 0),
  reorder_at         INTEGER      NOT NULL DEFAULT 20,
  low_stock_alerted  BOOLEAN      NOT NULL DEFAULT FALSE,
  version            BIGINT       NOT NULL DEFAULT 0,
  CONSTRAINT chk_reserved_le_on_hand CHECK (reserved <= on_hand)
);

CREATE TABLE allocation (
  id             UUID        PRIMARY KEY,
  appointment_id UUID        NOT NULL,
  drug_id        UUID        NOT NULL REFERENCES drug_stock (drug_id),
  quantity       INTEGER     NOT NULL CHECK (quantity > 0),
  status         VARCHAR(20) NOT NULL,
  CONSTRAINT uq_allocation UNIQUE (appointment_id, drug_id)
);
CREATE INDEX idx_allocation_appointment ON allocation (appointment_id);

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
