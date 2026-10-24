-- Development seed data (only loaded with the "dev" profile)
INSERT INTO drug_stock (drug_id, name, on_hand, reserved, reorder_at) VALUES
  ('11111111-1111-1111-1111-111111111111', 'Amoxicillin 500mg', 200, 0, 30),
  ('22222222-2222-2222-2222-222222222222', 'Paracetamol 500mg', 500, 0, 100),
  ('33333333-3333-3333-3333-333333333333', 'Insulin glargine', 15, 0, 10);
