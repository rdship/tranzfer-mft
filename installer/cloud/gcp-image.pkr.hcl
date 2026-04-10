# =============================================================================
# TranzFer MFT — GCP Image Builder (Packer)
# =============================================================================
# Builds a Google Compute Engine image from Ubuntu 24.04 with TranzFer MFT.
#
# Usage:
#   packer init .
#   packer build -var "version=2.0.0" \
#     -var "project_id=your-gcp-project" \
#     gcp-image.pkr.hcl
#
# Prerequisites: gcloud auth configured or GOOGLE_APPLICATION_CREDENTIALS set
# =============================================================================

packer {
  required_plugins {
    googlecompute = {
      version = ">= 1.1.0"
      source  = "github.com/hashicorp/googlecompute"
    }
  }
}

variable "version" {
  type        = string
  default     = "2.0.0"
  description = "TranzFer MFT version"
}

variable "project_id" {
  type        = string
  description = "GCP project ID"
}

variable "zone" {
  type        = string
  default     = "us-central1-a"
  description = "GCP zone for the build instance"
}

variable "machine_type" {
  type        = string
  default     = "e2-standard-4"
  description = "Machine type for the build (4 vCPU, 16GB RAM)"
}

source "googlecompute" "tranzfer-mft" {
  project_id   = var.project_id
  zone         = var.zone
  machine_type = var.machine_type

  # Ubuntu 24.04 LTS
  source_image_family = "ubuntu-2404-lts-amd64"
  source_image_project_id = ["ubuntu-os-cloud"]

  disk_size = 50
  disk_type = "pd-ssd"

  image_name        = "tranzfer-mft-${replace(var.version, ".", "-")}-{{timestamp}}"
  image_family      = "tranzfer-mft"
  image_description = "TranzFer MFT v${var.version} — Managed File Transfer Platform"

  image_labels = {
    product   = "tranzfer-mft"
    version   = replace(var.version, ".", "-")
    os        = "ubuntu-2404"
  }

  ssh_username = "ubuntu"

  metadata = {
    enable-oslogin = "false"
  }
}

build {
  sources = ["source.googlecompute.tranzfer-mft"]

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

      # GCP-specific: install ops agent for logging/monitoring
      "curl -sSO https://dl.google.com/cloudagents/add-google-cloud-ops-agent-repo.sh",
      "sudo bash add-google-cloud-ops-agent-repo.sh --also-install || true",
      "rm -f add-google-cloud-ops-agent-repo.sh",

      # Clean up
      "sudo apt-get clean",
      "sudo rm -rf /tmp/* /var/tmp/*",
    ]
  }
}
