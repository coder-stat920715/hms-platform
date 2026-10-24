locals {
  databases = {
    patient     = { instance_class = "db.r6g.large",  storage = 200 }
    appointment = { instance_class = "db.r6g.large",  storage = 100 }
    billing     = { instance_class = "db.r6g.large",  storage = 100 }
    pharmacy    = { instance_class = "db.r6g.medium", storage = 100 }
    iam         = { instance_class = "db.t4g.medium", storage = 50 }
  }
}

resource "aws_db_subnet_group" "main" {
  name       = "hms"
  subnet_ids = module.vpc.database_subnets
}

resource "aws_db_parameter_group" "pg16" {
  name   = "hms-pg16"
  family = "postgres16"

  # Debezium / CDC
  parameter {
    name         = "rds.logical_replication"
    value        = "1"
    apply_method = "pending-reboot"
  }
  parameter {
    name         = "max_replication_slots"
    value        = "10"
    apply_method = "pending-reboot"
  }
  parameter {
    name         = "max_wal_senders"
    value        = "10"
    apply_method = "pending-reboot"
  }
  # Hardening and audit
  parameter {
    name         = "rds.force_ssl"
    value        = "1"
    apply_method = "immediate"
  }
  parameter {
    name         = "log_connections"
    value        = "1"
    apply_method = "immediate"
  }
  parameter {
    name         = "log_statement"
    value        = "ddl"
    apply_method = "immediate"
  }
  # Don't let a stuck slot fill the disk (value in MB; -1 disables the cap)
  parameter {
    name         = "max_slot_wal_keep_size"
    value        = "20480"
    apply_method = "pending-reboot"
  }
}

resource "aws_db_instance" "svc" {
  for_each = local.databases

  identifier     = "hms-${each.key}"
  engine         = "postgres"
  engine_version = "16.4"
  instance_class = each.value.instance_class

  db_name  = each.key
  username = "hms_admin"
  manage_master_user_password   = true                    # stored and rotated in Secrets Manager
  master_user_secret_kms_key_id = aws_kms_key.data.arn

  allocated_storage     = each.value.storage
  max_allocated_storage = each.value.storage * 4
  storage_type          = "gp3"
  storage_encrypted     = true
  kms_key_id            = aws_kms_key.data.arn

  multi_az               = true
  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.data.id]
  publicly_accessible    = false
  parameter_group_name   = aws_db_parameter_group.pg16.name

  backup_retention_period    = 14
  backup_window              = "02:00-03:00"
  maintenance_window         = "sun:03:30-sun:04:30"
  copy_tags_to_snapshot      = true
  deletion_protection        = true
  skip_final_snapshot        = false
  final_snapshot_identifier  = "hms-${each.key}-final"
  auto_minor_version_upgrade = true

  performance_insights_enabled          = true
  performance_insights_kms_key_id       = aws_kms_key.data.arn
  enabled_cloudwatch_logs_exports       = ["postgresql", "upgrade"]
  monitoring_interval                   = 60
  monitoring_role_arn                   = aws_iam_role.rds_monitoring.arn
  iam_database_authentication_enabled   = true
}

resource "aws_iam_role" "rds_monitoring" {
  name = "hms-rds-monitoring"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "monitoring.rds.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}
resource "aws_iam_role_policy_attachment" "rds_monitoring" {
  role       = aws_iam_role.rds_monitoring.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}
