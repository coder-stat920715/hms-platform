module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 5.8"

  name = "hms"
  cidr = var.vpc_cidr
  azs  = var.azs

  public_subnets   = ["10.20.0.0/24", "10.20.1.0/24", "10.20.2.0/24"]      # external ALB, NAT
  private_subnets  = ["10.20.10.0/24", "10.20.11.0/24", "10.20.12.0/24"]   # gateway, services, internal ALB
  database_subnets = ["10.20.20.0/24", "10.20.21.0/24", "10.20.22.0/24"]   # RDS, MSK, Redis (no internet route)

  enable_nat_gateway     = true
  one_nat_gateway_per_az = true       # HA: one NAT per AZ
  create_database_subnet_route_table = true

  enable_flow_log                      = true
  create_flow_log_cloudwatch_log_group = true
  create_flow_log_cloudwatch_iam_role  = true

  tags = { Project = "hms", DataClass = "PHI" }
}

# Keep AWS API traffic off the internet
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = module.vpc.vpc_id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = concat(module.vpc.private_route_table_ids, module.vpc.database_route_table_ids)
}
