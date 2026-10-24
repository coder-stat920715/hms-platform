resource "aws_security_group" "msk" {
  name   = "hms-msk"
  vpc_id = module.vpc.vpc_id
}
resource "aws_security_group" "redis" {
  name   = "hms-redis"
  vpc_id = module.vpc.vpc_id
}
resource "aws_security_group" "opensearch" {
  name   = "hms-opensearch"
  vpc_id = module.vpc.vpc_id
}

locals {
  clients = [aws_security_group.services.id, aws_security_group.gateway.id, module.eks.node_security_group_id]
}

resource "aws_vpc_security_group_ingress_rule" "pg" {
  for_each                     = toset(local.clients)
  security_group_id            = aws_security_group.data.id
  referenced_security_group_id = each.value
  from_port = 5432
  to_port   = 5432
  ip_protocol = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "kafka" {
  for_each                     = toset(local.clients)
  security_group_id            = aws_security_group.msk.id
  referenced_security_group_id = each.value
  from_port = 9098
  to_port   = 9098
  ip_protocol = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "redis" {
  for_each                     = toset(local.clients)
  security_group_id            = aws_security_group.redis.id
  referenced_security_group_id = each.value
  from_port = 6379
  to_port   = 6379
  ip_protocol = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "opensearch" {
  for_each                     = toset(local.clients)
  security_group_id            = aws_security_group.opensearch.id
  referenced_security_group_id = each.value
  from_port = 443
  to_port   = 443
  ip_protocol = "tcp"
}
