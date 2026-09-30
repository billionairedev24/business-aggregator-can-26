# AWS network: one VPC with public (load balancers, NAT), private (EKS nodes) and isolated data subnets per zone.

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  azs = slice(data.aws_availability_zones.available.names, 0, var.zone_count)

  # /16 → private /20s at the bottom, public and data /24s near the top.
  private_cidrs = [for i, _ in local.azs : cidrsubnet(var.cidr, 4, i)]
  public_cidrs  = [for i, _ in local.azs : cidrsubnet(var.cidr, 8, 200 + i)]
  data_cidrs    = [for i, _ in local.azs : cidrsubnet(var.cidr, 8, 210 + i)]

  nat_count = var.high_availability_nat ? length(local.azs) : 1
}

resource "aws_vpc" "this" {
  cidr_block           = var.cidr
  enable_dns_hostnames = true
  enable_dns_support   = true
  tags                 = merge(var.context.tags, { Name = var.context.name })
}

# Lock down the default security group (no rules).
resource "aws_default_security_group" "this" {
  vpc_id = aws_vpc.this.id
  tags   = merge(var.context.tags, { Name = "${var.context.name}-default-deny" })
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = merge(var.context.tags, { Name = var.context.name })
}

resource "aws_subnet" "public" {
  count             = length(local.azs)
  vpc_id            = aws_vpc.this.id
  availability_zone = local.azs[count.index]
  cidr_block        = local.public_cidrs[count.index]
  tags = merge(var.context.tags, {
    Name                     = "${var.context.name}-public-${local.azs[count.index]}"
    "kubernetes.io/role/elb" = "1"
  })
}

resource "aws_subnet" "private" {
  count             = length(local.azs)
  vpc_id            = aws_vpc.this.id
  availability_zone = local.azs[count.index]
  cidr_block        = local.private_cidrs[count.index]
  tags = merge(var.context.tags, {
    Name                              = "${var.context.name}-private-${local.azs[count.index]}"
    "kubernetes.io/role/internal-elb" = "1"
  })
}

resource "aws_subnet" "data" {
  count             = length(local.azs)
  vpc_id            = aws_vpc.this.id
  availability_zone = local.azs[count.index]
  cidr_block        = local.data_cidrs[count.index]
  tags              = merge(var.context.tags, { Name = "${var.context.name}-data-${local.azs[count.index]}" })
}

resource "aws_eip" "nat" {
  count  = local.nat_count
  domain = "vpc"
  tags   = merge(var.context.tags, { Name = "${var.context.name}-nat-${count.index}" })
}

resource "aws_nat_gateway" "this" {
  count         = local.nat_count
  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id
  tags          = merge(var.context.tags, { Name = "${var.context.name}-${count.index}" })

  depends_on = [aws_internet_gateway.this]
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
  tags   = merge(var.context.tags, { Name = "${var.context.name}-public" })
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  count          = length(local.azs)
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table" "private" {
  count  = length(local.azs)
  vpc_id = aws_vpc.this.id
  tags   = merge(var.context.tags, { Name = "${var.context.name}-private-${local.azs[count.index]}" })
}

resource "aws_route" "private_nat" {
  count                  = length(local.azs)
  route_table_id         = aws_route_table.private[count.index].id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.this[var.high_availability_nat ? count.index : 0].id
}

resource "aws_route_table_association" "private" {
  count          = length(local.azs)
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private[count.index].id
}

# Data subnets have no route to the internet.
resource "aws_route_table" "data" {
  vpc_id = aws_vpc.this.id
  tags   = merge(var.context.tags, { Name = "${var.context.name}-data" })
}

resource "aws_route_table_association" "data" {
  count          = length(local.azs)
  subnet_id      = aws_subnet.data[count.index].id
  route_table_id = aws_route_table.data.id
}

# Gateway endpoint keeps S3 traffic (images layers, uploads) off the NAT.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${var.context.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = aws_route_table.private[*].id
  tags              = merge(var.context.tags, { Name = "${var.context.name}-s3" })
}

resource "aws_flow_log" "this" {
  count                = var.context.environment == "dev" ? 0 : 1
  vpc_id               = aws_vpc.this.id
  traffic_type         = "REJECT"
  log_destination_type = "cloud-watch-logs"
  log_destination      = aws_cloudwatch_log_group.flow[0].arn
  iam_role_arn         = aws_iam_role.flow[0].arn
  tags                 = var.context.tags
}

resource "aws_cloudwatch_log_group" "flow" {
  count             = var.context.environment == "dev" ? 0 : 1
  name              = "/northline/${var.context.environment}/vpc-flow-logs"
  retention_in_days = 90
  tags              = var.context.tags
}

resource "aws_iam_role" "flow" {
  count = var.context.environment == "dev" ? 0 : 1
  name  = "${var.context.name}-vpc-flow-logs"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "vpc-flow-logs.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
  tags = var.context.tags
}

resource "aws_iam_role_policy" "flow" {
  count = var.context.environment == "dev" ? 0 : 1
  name  = "write-flow-logs"
  role  = aws_iam_role.flow[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"]
      Resource = "${aws_cloudwatch_log_group.flow[0].arn}:*"
    }]
  })
}
