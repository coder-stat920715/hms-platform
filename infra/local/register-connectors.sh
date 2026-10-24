#!/usr/bin/env bash
# Registers one Debezium outbox connector per service database. Run after `docker compose up` AND after the
# services have started once (their Flyway migrations create the outbox table and publication).
set -euo pipefail
CONNECT_URL="${CONNECT_URL:-http://localhost:8083}"
DIR="$(cd "$(dirname "$0")" && pwd)"

for db in iam patient appointment billing pharmacy; do
  body=$(sed -e "s/__NAME__/${db}/g" -e "s/__DB__/${db}/g" "$DIR/connector-template.json")
  echo "Registering connector for ${db}..."
  curl -sf -X PUT -H 'Content-Type: application/json' \
       --data "$(echo "$body" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["config"]))')" \
       "${CONNECT_URL}/connectors/${db}-outbox/config" > /dev/null && echo "  ok"
done
curl -s "${CONNECT_URL}/connectors?expand=status" | python3 -m json.tool | head -40
