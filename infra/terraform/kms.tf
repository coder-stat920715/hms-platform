resource "aws_kms_key" "data" {
  description             = "HMS data at rest (RDS, MSK, Redis, OpenSearch)"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}
resource "aws_kms_alias" "data" {
  name          = "alias/hms-data"
  target_key_id = aws_kms_key.data.key_id
}
