# =============================================================================
# TranzFer MFT — AWS AMI Builder (Packer)
# =============================================================================
# Builds an Amazon Machine Image from Ubuntu 24.04 with TranzFer MFT.
#
# Usage:
#   packer init .
#   packer build -var "version=2.0.0" -var "region=us-east-1" aws-ami.pkr.hcl
#
# Prerequisites: AWS credentials configured (env vars, ~/.aws/credentials, or IAM role)
# =============================================================================

packer {
  required_plugins {
    amazon = {
      version = ">= 1.2.0"
      source  = "github.com/hashicorp/amazon"
    }
  }
}

variable "version" {
  type        = string
  default     = "2.0.0"
  description = "TranzFer MFT version"
}

variable "region" {
  type        = string
  default     = "us-east-1"
  description = "AWS region to build the AMI in"
}

variable "instance_type" {
  type        = string
  default     = "t3.xlarge"
  description = "EC2 instance type for the build (4 vCPU, 16GB RAM recommended)"
}

variable "ami_regions" {
  type        = list(string)
  default     = []
  description = "Additional regions to copy the AMI to"
}

source "amazon-ebs" "tranzfer-mft" {
  ami_name      = "tranzfer-mft-${var.version}-{{timestamp}}"
  instance_type = var.instance_type
  region        = var.region
  ami_regions   = var.ami_regions

  source_ami_filter {
    filters = {
      name                = "ubuntu/images/hvm-ssd/ubuntu-noble-24.04-amd64-server-*"
      root-device-type    = "ebs"
      virtualization-type = "hvm"
    }
    most_recent = true
    owners      = ["099720109477"] # Canonical
  }

  ssh_username = "ubuntu"

  # Use a 50GB root volume
  launch_block_device_mappings {
    device_name           = "/dev/sda1"
    volume_size           = 50
    volume_type           = "gp3"
    delete_on_termination = true
  }

  ami_description = "TranzFer MFT v${var.version} — Managed File Transfer Platform"

  tags = {
    Name        = "TranzFer MFT ${var.version}"
    Product     = "TranzFer MFT"
    Version     = var.version
    BuildDate   = "{{timestamp}}"
    OS          = "Ubuntu 24.04 LTS"
  }

  run_tags = {
    Name = "packer-tranzfer-mft-${var.version}"
  }
}

build {
  sources = ["source.amazon-ebs.tranzfer-mft"]

  # --- Copy deployment files ---
  provisioner "file" {
    source      = "../../docker-compose.yml"
    destination = "/tmp/docker-compose.yml"
  }

  provisioner "file" {
    source      = "../../spire"
    destination = "/tmp/spire"
  }

  provisioner "file" {
    source      = "../../scripts"
    destination = "/tmp/scripts"
  }

  provisioner "file" {
    source      = "../first-boot/first-boot-setup.sh"
    destination = "/tmp/first-boot-setup.sh"
  }

  provisioner "file" {
    source      = "../first-boot/tranzfer-mft.conf"
    destination = "/tmp/tranzfer-mft.conf"
  }

  # --- Install and configure ---
  provisioner "shell" {
    inline = [
      # Create application directory
      "sudo mkdir -p /opt/tranzfer-mft",
      "sudo cp /tmp/docker-compose.yml /opt/tranzfer-mft/",
      "sudo cp -r /tmp/spire /opt/tranzfer-mft/",
      "sudo cp -r /tmp/scripts /opt/tranzfer-mft/",
      "sudo cp /tmp/first-boot-setup.sh /opt/tranzfer-mft/",
      "sudo cp /tmp/tranzfer-mft.conf /opt/tranzfer-mft/",
      "sudo chmod +x /opt/tranzfer-mft/first-boot-setup.sh",
      "sudo chmod +x /opt/tranzfer-mft/scripts/*.sh 2>/dev/null || true",

      # Install Docker
      "curl -fsSL https://get.docker.com | sudo sh",
      "sudo usermod -aG docker ubuntu",
      "sudo systemctl enable docker",

      # Pre-pull images
      "sudo docker compose -f /opt/tranzfer-mft/docker-compose.yml pull || true",

      # Create data directories
      "sudo mkdir -p /data/storage/{hot,warm,cold,backup}",
      "sudo mkdir -p /data/partners /data/postgres /data/redis /data/rabbitmq",
      "sudo chown -R 1000:1000 /data/storage /data/partners",

      # Install first-boot systemd service
      "sudo bash -c 'cat > /etc/systemd/system/tranzfer-first-boot.service << EOF",
      "[Unit]",
      "Description=TranzFer MFT First Boot Setup",
      "After=network-online.target docker.service",
      "Wants=network-online.target",
      "ConditionPathExists=!/opt/tranzfer-mft/.installed",
      "",
      "[Service]",
      "Type=oneshot",
      "ExecStart=/opt/tranzfer-mft/first-boot-setup.sh",
      "RemainAfterExit=yes",
      "TimeoutStartSec=600",
      "",
      "[Install]",
      "WantedBy=multi-user.target",
      "EOF'",
      "sudo systemctl enable tranzfer-first-boot.service",

      # AWS-specific: enable SSM agent for remote management
      "sudo snap install amazon-ssm-agent --classic || true",
      "sudo systemctl enable snap.amazon-ssm-agent.amazon-ssm-agent.service || true",

      # Clean up
      "sudo apt-get clean",
      "sudo rm -rf /tmp/* /var/tmp/*",
    ]
  }
}
