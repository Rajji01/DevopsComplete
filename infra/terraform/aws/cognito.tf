# Amazon Cognito replaces Keycloak in prod: it issues the JWTs order-service validates.
# Groups map to the roles the app checks (JwtRolesConverter reads the "cognito:groups" claim).
resource "aws_cognito_user_pool" "shopflow" {
  name = var.name

  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]
  mfa_configuration        = "OPTIONAL"

  password_policy {
    minimum_length    = 12
    require_lowercase = true
    require_numbers   = true
    require_symbols   = false
    require_uppercase = true
  }

  account_recovery_setting {
    recovery_mechanism {
      name     = "verified_email"
      priority = 1
    }
  }
}

resource "aws_cognito_user_pool_client" "web" {
  name         = "${var.name}-web"
  user_pool_id = aws_cognito_user_pool.shopflow.id

  generate_secret = false # public client (SPA / mobile): PKCE instead of a secret

  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = ["openid", "email", "profile"]
  supported_identity_providers         = ["COGNITO"]
  callback_urls                        = ["https://${var.domain_name}/callback", "http://localhost:3000/callback"]
  logout_urls                          = ["https://${var.domain_name}/"]

  explicit_auth_flows = ["ALLOW_USER_SRP_AUTH", "ALLOW_REFRESH_TOKEN_AUTH"]

  access_token_validity  = 15 # minutes
  id_token_validity      = 15
  refresh_token_validity = 30 # days

  token_validity_units {
    access_token  = "minutes"
    id_token      = "minutes"
    refresh_token = "days"
  }
}

resource "aws_cognito_user_pool_domain" "shopflow" {
  domain       = "${var.name}-${random_id.cognito_domain.hex}" # hosted login page: https://<domain>.auth.<region>.amazoncognito.com
  user_pool_id = aws_cognito_user_pool.shopflow.id
}

resource "random_id" "cognito_domain" {
  byte_length = 3
}

resource "aws_cognito_user_group" "roles" {
  for_each = {
    customer = "Can place, read and cancel own orders"
    support  = "Read-only access to orders"
  }

  name         = each.key
  description  = each.value
  user_pool_id = aws_cognito_user_pool.shopflow.id
}
