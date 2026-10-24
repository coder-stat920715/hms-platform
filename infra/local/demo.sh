#!/usr/bin/env bash
# End-to-end smoke test through the gateway (http://localhost:8080). Prerequisites: docker compose up, all services
# running (pharmacy with SPRING_PROFILES_ACTIVE=dev for seed drugs), connectors registered.
set -euo pipefail
GW="${GW:-http://localhost:8080}"
KC="${KC:-http://localhost:8180}"

token() {  # username (password == username in the dev realm)
  curl -sf -d client_id=hms-web -d grant_type=password -d "username=$1" -d "password=$1" \
    "$KC/realms/hms/protocol/openid-connect/token" | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])'
}
jget() { python3 -c "import sys,json; d=json.load(sys.stdin); print(d$1)"; }

DOCTOR=aaaaaaaa-0000-0000-0000-000000000001
PATIENT=bbbbbbbb-0000-0000-0000-000000000001
START=$(date -u -d 'tomorrow 09:00' +%Y-%m-%dT%H:%M:%SZ)
START2=$(date -u -d 'tomorrow 09:30' +%Y-%m-%dT%H:%M:%SZ)
AMOX=11111111-1111-1111-1111-111111111111

echo "1. receptionist registers the patient (linked to the patient1 login)"
RT=$(token reception1)
curl -sf -X POST "$GW/api/patients" -H "Authorization: Bearer $RT" -H 'Content-Type: application/json' -d "{
  \"id\":\"$PATIENT\",\"fullName\":\"Pat Ient\",\"dateOfBirth\":\"1985-04-12\",\"department\":\"cardiology\",
  \"email\":\"patient1@hms.example.com\",\"phone\":\"+15550100\",\"channels\":[\"EMAIL\",\"SMS\"]}" | python3 -m json.tool

echo "2. doctor publishes two 30-minute slots"
DT=$(token doctor1)
curl -sf -X POST "$GW/api/appointments/slots" -H "Authorization: Bearer $DT" -H 'Content-Type: application/json' \
  -d "{\"doctorId\":\"$DOCTOR\",\"start\":\"$START\",\"slotMinutes\":30,\"count\":2}" | python3 -m json.tool

echo "3. booking WITHOUT pharmacy (saga: slot -> invoice -> confirm)"
A1=$(curl -sf -X POST "$GW/api/appointments" -H "Authorization: Bearer $RT" -H 'Content-Type: application/json' \
  -d "{\"patientId\":\"$PATIENT\",\"doctorId\":\"$DOCTOR\",\"slotStart\":\"$START\",\"fee\":120.00}" | jget "['id']")
echo "   appointment $A1"

echo "4. booking WITH a prescription (saga: slot -> invoice -> inventory -> confirm)"
A2=$(curl -sf -X POST "$GW/api/appointments" -H "Authorization: Bearer $RT" -H 'Content-Type: application/json' \
  -d "{\"patientId\":\"$PATIENT\",\"doctorId\":\"$DOCTOR\",\"slotStart\":\"$START2\",\"fee\":80.00,
       \"items\":[{\"drugId\":\"$AMOX\",\"quantity\":2}]}" | jget "['id']")
echo "   appointment $A2"

echo "5. waiting for the saga..."
sleep 8
PT=$(token patient1)
for id in "$A1" "$A2"; do
  curl -sf "$GW/api/appointments/$id" -H "Authorization: Bearer $PT" | python3 -c 'import sys,json; d=json.load(sys.stdin); print("  ", d["id"], d["status"], d.get("failureReason") or "")'
done

echo "6. patient searches the CQRS read model (own appointments only)"
curl -sf "$GW/api/appointments/search" -H "Authorization: Bearer $PT" | python3 -m json.tool

echo "7. ABAC: patient tries to read someone else's record -> expect 403"
curl -s -o /dev/null -w "   HTTP %{http_code}\n" "$GW/api/patients/$DOCTOR" -H "Authorization: Bearer $PT"
