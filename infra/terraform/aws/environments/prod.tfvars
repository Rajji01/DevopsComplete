# Production: the defaults in variables.tf are already prod-sized; this file only makes them explicit.
environment         = "prod"
node_instance_types = ["t3.large"]
node_group_size     = { min = 3, desired = 3, max = 6 }
db_instance_class   = "db.t4g.medium"
db_multi_az         = true
cache_node_type     = "cache.t4g.small"
kafka_instance_type = "kafka.m5.large"
domain_name         = "shop.example.com"
