# ---------- Redis (gateway rate limiting) ----------
resource "aws_elasticache_subnet_group" "redis" {
  name       = "hms-redis"
  subnet_ids = module.vpc.database_subnets
}

resource "random_password" "redis" {
  length  = 40
  special = false
}

resource "aws_secretsmanager_secret" "redis" {
  name       = "hms/redis"
  kms_key_id = aws_kms_key.data.arn
}
resource "aws_secretsmanager_secret_version" "redis" {
  secret_id     = aws_secretsmanager_secret.redis.id
  secret_string = jsonencode({ password = random_password.redis.result })
}

resource "aws_elasticache_replication_group" "redis" {
  replication_group_id = "hms-redis"
  description          = "Gateway rate limiting"
  engine               = "redis"
  engine_version       = "7.1"
  node_type            = "cache.r6g.large"
  num_cache_clusters   = 3
  port                 = 6379

  automatic_failover_enabled = true
  multi_az_enabled           = true
  subnet_group_name          = aws_elasticache_subnet_group.redis.name
  security_group_ids         = [aws_security_group.redis.id]

  at_rest_encryption_enabled = true
  kms_key_id                 = aws_kms_key.data.arn
  transit_encryption_enabled = true
  auth_token                 = random_password.redis.result

  snapshot_retention_limit = 0        # rate-limit counters are disposable
}

# ---------- OpenSearch (CQRS read models) ----------
resource "aws_opensearch_domain" "read" {
  domain_name    = "hms-read"
  engine_version = "OpenSearch_2.15"

  cluster_config {
    instance_type            = "r6g.large.search"
    instance_count           = 3
    zone_awareness_enabled   = true
    zone_awareness_config { availability_zone_count = 3 }
    dedicated_master_enabled = true
    dedicated_master_type    = "m6g.large.search"
    dedicated_master_count   = 3
  }

  ebs_options {
    ebs_enabled = true
    volume_type = "gp3"
    volume_size = 200
  }

  vpc_options {
    subnet_ids         = module.vpc.database_subnets
    security_group_ids = [aws_security_group.opensearch.id]
  }

  encrypt_at_rest {

    enabled    = true

    kms_key_id = aws_kms_key.data.arn

  }
  node_to_node_encryption { enabled = true }

  domain_endpoint_options {
    enforce_https       = true
    tls_security_policy = "Policy-Min-TLS-1-2-2019-07"
  }

  advanced_security_options {
    enabled                        = true
    internal_user_database_enabled = false
    master_user_options { master_user_arn = aws_iam_role.opensearch_admin.arn }
  }

  log_publishing_options {
    log_type                 = "AUDIT_LOGS"
    cloudwatch_log_group_arn = aws_cloudwatch_log_group.os_audit.arn
  }
}
