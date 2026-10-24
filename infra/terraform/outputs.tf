output "cloudfront_domain" { value = aws_cloudfront_distribution.api.domain_name }
output "external_alb_dns"  { value = aws_lb.external.dns_name }
output "internal_alb_dns"  { value = aws_lb.internal.dns_name }
output "vpc_id"            { value = module.vpc.vpc_id }
output "private_subnets"   { value = module.vpc.private_subnets }
