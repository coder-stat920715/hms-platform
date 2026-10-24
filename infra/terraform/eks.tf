module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 20.24"

  cluster_name    = "hms"
  cluster_version = "1.30"

  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnets

  cluster_endpoint_public_access  = false          # API reachable only from the VPC / VPN
  cluster_endpoint_private_access = true
  enable_irsa                     = true

  cluster_encryption_config = { resources = ["secrets"] }   # envelope encryption (KMS key created by module)

  cluster_enabled_log_types = ["api", "audit", "authenticator"]

  cluster_addons = {
    coredns                = {}
    kube-proxy             = {}
    vpc-cni                = { configuration_values = jsonencode({ enableNetworkPolicy = "true" }) }
    eks-pod-identity-agent = {}
    aws-ebs-csi-driver     = { service_account_role_arn = module.ebs_csi_irsa.iam_role_arn }
  }

  eks_managed_node_groups = {
    apps = {
      instance_types = ["m6i.xlarge"]
      min_size       = 3
      max_size       = 12
      desired_size   = 3
      ami_type       = "AL2023_x86_64_STANDARD"
      labels         = { workload = "apps" }
      metadata_options = { http_tokens = "required", http_put_response_hop_limit = 1 }   # IMDSv2 only
    }
  }

  # Let the ALBs from Part 3 reach pods (target_type = "ip")
  node_security_group_additional_rules = {
    from_gateway_alb = {
      type                     = "ingress"
      protocol                 = "tcp"
      from_port                = 8080
      to_port                  = 8080
      source_security_group_id = aws_security_group.external_alb.id
    }
    from_internal_alb = {
      type                     = "ingress"
      protocol                 = "tcp"
      from_port                = 8080
      to_port                  = 8080
      source_security_group_id = aws_security_group.internal_alb.id
    }
  }
}

module "ebs_csi_irsa" {
  source    = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version   = "~> 5.44"
  role_name = "hms-ebs-csi"
  attach_ebs_csi_policy = true
  oidc_providers = {
    main = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["kube-system:ebs-csi-controller-sa"]
    }
  }
}

# AWS Load Balancer Controller: needed for TargetGroupBinding (registers pod IPs in the Part 3 target groups)
module "lbc_irsa" {
  source    = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version   = "~> 5.44"
  role_name = "hms-aws-lbc"
  attach_load_balancer_controller_policy                          = true
  attach_load_balancer_controller_targetgroup_binding_only_policy = true
  oidc_providers = {
    main = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["kube-system:aws-load-balancer-controller"]
    }
  }
}

# Per-service IRSA roles (least privilege), e.g. notification-service may call SES + SNS only
module "irsa_notification" {
  source    = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version   = "~> 5.44"
  role_name = "hms-notification"
  role_policy_arns = { ses_sns = aws_iam_policy.notification.arn }
  oidc_providers = {
    main = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["hms:notification-service"]
    }
  }
}

resource "aws_iam_policy" "notification" {
  name = "hms-notification-send"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = ["ses:SendEmail", "sns:Publish"], Resource = "*" }]
  })
}
