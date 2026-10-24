-- Recipient directory: contact data only, projected from patient events (no clinical data).
CREATE TABLE recipient (
  patient_id        UUID         PRIMARY KEY,
  full_name         VARCHAR(200) NOT NULL,
  email             VARCHAR(200),
  phone             VARCHAR(50),
  locale            VARCHAR(20)  NOT NULL DEFAULT 'en',
  channels          VARCHAR(100) NOT NULL DEFAULT 'EMAIL',
  push_endpoint_arn VARCHAR(512),
  updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

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
