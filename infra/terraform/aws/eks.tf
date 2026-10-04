# EKS with a managed node group, IRSA enabled, and the add-ons the platform needs.
module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 21.0"

  name               = var.name
  kubernetes_version = var.kubernetes_version

  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnets

  endpoint_public_access  = true # kubectl from CI / laptops; restrict with endpoint_public_access_cidrs in a real org
  endpoint_private_access = true

  enable_irsa = true # pods get IAM roles through their ServiceAccount (External Secrets, LB controller)

  # whoever runs terraform becomes cluster admin; add teammates via access_entries
  enable_cluster_creator_admin_permissions = true

  addons = {
    coredns                = {}
    kube-proxy             = {}
    vpc-cni                = {}
    eks-pod-identity-agent = {}
    aws-ebs-csi-driver     = {} # PersistentVolumes on EBS
    metrics-server         = {} # needed by the HPAs
  }

  eks_managed_node_groups = {
    general = {
      instance_types = var.node_instance_types
      capacity_type  = "ON_DEMAND" # SPOT for dev/stateless workloads cuts ~70% of the node cost

      min_size     = var.node_group_size.min
      desired_size = var.node_group_size.desired
      max_size     = var.node_group_size.max

      labels = {
        workload = "general"
      }
    }
  }

  tags = {
    "karpenter.sh/discovery" = var.name
  }
}

# Lets the EKS nodes/pods reach the managed services (used as the source in their security groups)
locals {
  cluster_sg_ids = [module.eks.node_security_group_id]
}
