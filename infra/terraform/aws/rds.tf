# One PostgreSQL instance, three databases (one per service, created by the same init script
# as docker-compose). Multi-AZ, encrypted, automated backups, deletion protection.
resource "random_password" "db" {
  for_each = toset(["master", "orders", "inventory", "notifications", "payments"])
  length   = 32
  special  = false
}

resource "aws_security_group" "rds" {
  name_prefix = "${var.name}-rds-"
  vpc_id      = module.vpc.vpc_id

  ingress {
    description     = "PostgreSQL from EKS nodes only"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = local.cluster_sg_ids
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_db_parameter_group" "postgres" {
  name_prefix = "${var.name}-pg16-"
  family      = "postgres16"

  # 2 services x 10 pods x 5 connections = 100, plus notification-service and headroom
  parameter {
    name         = "max_connections"
    value        = "300"
    apply_method = "pending-reboot"
  }

  # log slow queries (ms) so p99 problems can be traced to SQL
  parameter {
    name  = "log_min_duration_statement"
    value = "500"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_db_instance" "postgres" {
  identifier = var.name

  engine         = "postgres"
  engine_version = "16"
  instance_class = var.db_instance_class

  allocated_storage     = 50
  max_allocated_storage = 200 # storage autoscaling
  storage_type          = "gp3"
  storage_encrypted     = true

  db_name  = "postgres"
  username = "shopflow_admin"
  password = random_password.db["master"].result

  db_subnet_group_name   = module.vpc.database_subnet_group_name
  vpc_security_group_ids = [aws_security_group.rds.id]
  parameter_group_name   = aws_db_parameter_group.postgres.name
  publicly_accessible    = false

  multi_az                = var.db_multi_az
  backup_retention_period = 7
  backup_window           = "20:00-21:00" # 01:30-02:30 IST
  maintenance_window      = "Sun:21:00-Sun:22:00"

  performance_insights_enabled = true
  monitoring_interval          = 60
  monitoring_role_arn          = aws_iam_role.rds_monitoring.arn

  deletion_protection       = var.environment == "prod"
  skip_final_snapshot       = var.environment != "prod"
  final_snapshot_identifier = "${var.name}-final"

  apply_immediately = var.environment != "prod"
}

resource "aws_iam_role" "rds_monitoring" {
  name = "${var.name}-rds-monitoring"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "monitoring.rds.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "rds_monitoring" {
  role       = aws_iam_role.rds_monitoring.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}

# The per-service passwords the ExternalSecret reads (k8s/overlays/prod/external-secret.yaml).
# The databases/users themselves are created by a one-off Job or psql against the RDS endpoint
# using the master credentials (same SQL as k8s/base/postgres/init-db.sh).
resource "aws_secretsmanager_secret" "db" {
  name                    = "${var.name}/${var.environment}/db"
  recovery_window_in_days = var.environment == "prod" ? 30 : 0
}

resource "aws_secretsmanager_secret_version" "db" {
  secret_id = aws_secretsmanager_secret.db.id
  secret_string = jsonencode({
    master        = random_password.db["master"].result
    orders        = random_password.db["orders"].result
    inventory     = random_password.db["inventory"].result
    notifications = random_password.db["notifications"].result
    payments      = random_password.db["payments"].result
    host          = aws_db_instance.postgres.address
    port          = aws_db_instance.postgres.port
  })
}
