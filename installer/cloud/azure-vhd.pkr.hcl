# =============================================================================
# TranzFer MFT — Azure VHD Image Builder (Packer)
# =============================================================================
# Builds an Azure Managed Image from Ubuntu 24.04 with TranzFer MFT.
#
# Usage:
#   packer init .
#   packer build -var "version=2.0.0" \
#     -var "subscription_id=YOUR_SUB_ID" \
#     -var "resource_group=tranzfer-images" \
#     azure-vhd.pkr.hcl
#
# Prerequisites: Azure CLI authenticated (az login) or service principal credentials
# =============================================================================

packer {
  required_plugins {
    azure = {
      version = ">= 2.0.0"
      source  = "github.com/hashicorp/azure"
    }
  }
}

variable "version" {
  type        = string
  default     = "2.0.0"
  description = "TranzFer MFT version"
}

variable "subscription_id" {
  type        = string
  description = "Azure subscription ID"
}

variable "resource_group" {
  type        = string
  default     = "tranzfer-images"
  description = "Resource group for the managed image"
}

variable "location" {
  type        = string
  default     = "eastus"
  description = "Azure region"
}

variable "vm_size" {
  type        = string
  default     = "Standard_D4s_v3"
  description = "VM size for the build (4 vCPU, 16GB RAM)"
}

source "azure-arm" "tranzfer-mft" {
  subscription_id = var.subscription_id
  location        = var.location
  vm_size         = var.vm_size

  # Ubuntu 24.04 LTS from Canonical
  image_publisher = "Canonical"
  image_offer     = "ubuntu-24_04-lts"
  image_sku       = "server"

  os_type         = "Linux"
  os_disk_size_gb = 50

  # Output as managed image
  managed_image_name                = "tranzfer-mft-${var.version}"
  managed_image_resource_group_name = var.resource_group

  azure_tags = {
    Product   = "TranzFer MFT"
    Version   = var.version
    BuildDate = "{{timestamp}}"
    OS        = "Ubuntu 24.04 LTS"
  }
}

build {
  sources = ["source.azure-arm.tranzfer-mft"]

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
      "sudo usermod -aG docker ${var.image_publisher == \"Canonical\" ? \"ubuntu\" : \"azureuser\"}",
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

      # Azure-specific: ensure waagent is configured for generalization
      "sudo waagent -deprovision+user -force",

      # Clean up
      "sudo apt-get clean",
      "sudo rm -rf /tmp/* /var/tmp/*",
    ]
  }
}
