CREATE TABLE patient (
  id            UUID         PRIMARY KEY,
  national_id   VARCHAR(512),
  full_name     VARCHAR(200) NOT NULL,
  date_of_birth DATE         NOT NULL,
  department    VARCHAR(100),
  sensitivity   VARCHAR(20)  NOT NULL DEFAULT 'NORMAL',
  email         VARCHAR(200),
  phone         VARCHAR(50),
  locale        VARCHAR(20)  NOT NULL DEFAULT 'en',
  channels      VARCHAR(100) NOT NULL DEFAULT 'EMAIL',
  push_endpoint_arn VARCHAR(512),
  version       BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE patient_care_team (
  patient_id UUID NOT NULL REFERENCES patient (id) ON DELETE CASCADE,
  user_id    UUID NOT NULL,
  PRIMARY KEY (patient_id, user_id)
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
