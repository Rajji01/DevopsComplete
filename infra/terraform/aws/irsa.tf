# IRSA = IAM Roles for Service Accounts: a pod presents its projected ServiceAccount token to STS
# and gets temporary AWS credentials for exactly the role annotated on that ServiceAccount.
# No access keys anywhere.

# 1. External Secrets Operator: read the DB secret
module "irsa_external_secrets" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version = "~> 5.0"

  role_name = "${var.name}-external-secrets"

  oidc_providers = {
    main = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["shopflow:external-secrets-sa"]
    }
  }

  role_policy_arns = {
    secrets = aws_iam_policy.read_db_secret.arn
  }
}

resource "aws_iam_policy" "read_db_secret" {
  name = "${var.name}-read-db-secret"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
      Resource = aws_secretsmanager_secret.db.arn
    }]
  })
}

# 2. order-service / notification-service: produce and consume on MSK with IAM auth
module "irsa_kafka_clients" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version = "~> 5.0"

  role_name = "${var.name}-kafka-clients"

  oidc_providers = {
    main = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["shopflow:order-service", "shopflow:notification-service"]
    }
  }

  role_policy_arns = {
    kafka = aws_iam_policy.kafka_clients.arn
  }
}

resource "aws_iam_policy" "kafka_clients" {
  name = "${var.name}-kafka-clients"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["kafka-cluster:Connect", "kafka-cluster:DescribeCluster"]
        Resource = aws_msk_cluster.shopflow.arn
      },
      {
        Effect = "Allow"
        Action = ["kafka-cluster:*Topic*", "kafka-cluster:WriteData", "kafka-cluster:ReadData"]
        # topic ARN format: arn:aws:kafka:region:account:topic/cluster-name/cluster-uuid/topic-name
        Resource = "arn:aws:kafka:${var.region}:${data.aws_caller_identity.current.account_id}:topic/${var.name}/*/orders.events*"
      },
      {
        Effect   = "Allow"
        Action   = ["kafka-cluster:AlterGroup", "kafka-cluster:DescribeGroup"]
        Resource = "arn:aws:kafka:${var.region}:${data.aws_caller_identity.current.account_id}:group/${var.name}/*/notification-service"
      }
    ]
  })
}

# 3. AWS Load Balancer Controller: creates the ALB for the Ingress
module "irsa_lb_controller" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version = "~> 5.0"

  role_name                              = "${var.name}-aws-load-balancer-controller"
  attach_load_balancer_controller_policy = true

  oidc_providers = {
    main = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["kube-system:aws-load-balancer-controller"]
    }
  }
}

data "aws_caller_identity" "current" {}
