# Paste these into k8s/overlays/prod/kustomization.yaml (configMapGenerator) and external-secret.yaml
output "cluster_name" {
  value = module.eks.cluster_name
}

output "configure_kubectl" {
  value = "aws eks update-kubeconfig --region ${var.region} --name ${module.eks.cluster_name}"
}

output "ecr_repositories" {
  value = { for k, r in aws_ecr_repository.service : k => r.repository_url }
}

output "rds_endpoint" {
  value = "${aws_db_instance.postgres.address}:${aws_db_instance.postgres.port}"
}

output "redis_host" {
  value = aws_elasticache_replication_group.redis.primary_endpoint_address
}

output "kafka_bootstrap_servers_iam" {
  value = aws_msk_cluster.shopflow.bootstrap_brokers_sasl_iam
}

output "jwt_issuer_uri" {
  value = "https://cognito-idp.${var.region}.amazonaws.com/${aws_cognito_user_pool.shopflow.id}"
}

output "cognito_client_id" {
  value = aws_cognito_user_pool_client.web.id
}

output "external_secrets_role_arn" {
  value = module.irsa_external_secrets.iam_role_arn
}

output "kafka_clients_role_arn" {
  value = module.irsa_kafka_clients.iam_role_arn
}

output "lb_controller_role_arn" {
  value = module.irsa_lb_controller.iam_role_arn
}

output "github_actions_role_arn" {
  value = aws_iam_role.github_actions.arn
}

output "db_secret_name" {
  value = aws_secretsmanager_secret.db.name
}
