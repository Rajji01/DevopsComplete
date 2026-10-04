# Public hostname + TLS certificate for the ALB. Optional: set hosted_zone_id when you own the domain
# in Route 53; otherwise the Ingress annotation needs a certificate you created by hand.
variable "hosted_zone_id" {
  description = "Route 53 hosted zone that owns domain_name (empty = skip DNS + ACM)"
  type        = string
  default     = ""
}

locals {
  create_dns = var.hosted_zone_id != ""
}

resource "aws_acm_certificate" "api" {
  count = local.create_dns ? 1 : 0

  domain_name       = var.domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true # a renewed cert is issued before the old one is removed from the ALB
  }
}

# DNS validation records: ACM proves you control the domain by looking these up
resource "aws_route53_record" "api_validation" {
  for_each = local.create_dns ? {
    for dvo in aws_acm_certificate.api[0].domain_validation_options : dvo.domain_name => {
      name   = dvo.resource_record_name
      record = dvo.resource_record_value
      type   = dvo.resource_record_type
    }
  } : {}

  zone_id         = var.hosted_zone_id
  name            = each.value.name
  type            = each.value.type
  records         = [each.value.record]
  ttl             = 60
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "api" {
  count = local.create_dns ? 1 : 0

  certificate_arn         = aws_acm_certificate.api[0].arn
  validation_record_fqdns = [for r in aws_route53_record.api_validation : r.fqdn]
}

# The ALB itself is created by the AWS Load Balancer Controller from the Ingress, so its DNS name is
# only known afterwards. external-dns (Helm) watches the Ingress and writes this record automatically;
# the manual alternative is: aws elbv2 describe-load-balancers -> aws_route53_record alias.

output "acm_certificate_arn" {
  description = "Paste into the Ingress annotation alb.ingress.kubernetes.io/certificate-arn"
  value       = local.create_dns ? aws_acm_certificate_validation.api[0].certificate_arn : "set hosted_zone_id to create one"
}
