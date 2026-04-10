# =============================================================================
# TranzFer MFT — OVA Virtual Appliance Builder (Packer)
# =============================================================================
# Builds a VirtualBox OVA from Ubuntu 24.04 with TranzFer MFT pre-installed.
#
# Usage:
#   packer init .
#   packer build -var "version=2.0.0" tranzfer-mft.pkr.hcl
#
# Output: output-ova/tranzfer-mft-VERSION.ova
# =============================================================================

packer {
  required_plugins {
    virtualbox = {
      version = ">= 1.0.0"
      source  = "github.com/hashicorp/virtualbox"
    }
    qemu = {
      version = ">= 1.0.0"
      source  = "github.com/hashicorp/qemu"
    }
  }
}

variable "version" {
  type        = string
  default     = "2.0.0"
  description = "TranzFer MFT version"
}

variable "iso_url" {
  type        = string
  default     = "https://releases.ubuntu.com/24.04/ubuntu-24.04-live-server-amd64.iso"
  description = "Ubuntu 24.04 server ISO URL"
}

variable "iso_checksum" {
  type        = string
  default     = "sha256:CHECKSUM_HERE"
  description = "SHA256 checksum of the Ubuntu ISO"
}

variable "disk_size" {
  type        = number
  default     = 50000
  description = "Disk size in MB (50GB default)"
}

variable "memory" {
  type        = number
  default     = 8192
  description = "VM memory in MB (8GB default)"
}

variable "cpus" {
  type        = number
  default     = 4
  description = "Number of virtual CPUs"
}

source "virtualbox-iso" "tranzfer-mft" {
  vm_name           = "tranzfer-mft-${var.version}"
  guest_os_type     = "Ubuntu_64"
  iso_url           = var.iso_url
  iso_checksum      = var.iso_checksum
  disk_size         = var.disk_size
  memory            = var.memory
  cpus              = var.cpus
  headless          = true
  ssh_username      = "tranzfer"
  ssh_password      = "tranzfer"
  ssh_timeout       = "30m"
  shutdown_command   = "echo 'tranzfer' | sudo -S shutdown -P now"
  output_directory   = "output-ova"
  format            = "ova"
  http_directory    = "http"

  boot_command = [
    "c<wait>",
    "linux /casper/vmlinuz --- autoinstall ds='nocloud-net;s=http://{{ .HTTPIP }}:{{ .HTTPPort }}/'<enter><wait>",
    "initrd /casper/initrd<enter><wait>",
    "boot<enter>"
  ]

  vboxmanage = [
    ["modifyvm", "{{.Name}}", "--nat-localhostreachable1", "on"],
  ]
}

build {
  sources = ["source.virtualbox-iso.tranzfer-mft"]

  # --- Copy TranzFer MFT deployment files ---
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
      "sudo usermod -aG docker tranzfer",
      "sudo systemctl enable docker",

      # Pre-pull images (if online build)
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

      # Configure firewall
      "sudo ufw default deny incoming",
      "sudo ufw default allow outgoing",
      "sudo ufw allow 22/tcp",
      "sudo ufw allow 8080/tcp",
      "sudo ufw allow 2222/tcp",
      "sudo ufw allow 21/tcp",
      "sudo ufw allow 990/tcp",
      "sudo ufw --force enable",

      # Clean up
      "sudo apt-get clean",
      "sudo rm -rf /tmp/* /var/tmp/*",
      "sudo truncate -s 0 /var/log/syslog /var/log/auth.log || true",
    ]
  }
}
