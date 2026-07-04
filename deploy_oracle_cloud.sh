#!/bin/bash
set -e

echo "======================================================"
echo "      Skew Engine - Oracle Cloud Auto-Installer       "
echo "======================================================"

# 1. Update package manager & install dependencies
echo "--> Updating system packages..."
sudo apt-get update -y && sudo apt-get upgrade -y
sudo apt-get install -y curl git ufw

# 2. Install Docker & Docker Compose if not installed
if ! command -v docker &> /dev/null; then
    echo "--> Installing Docker..."
    curl -fsSL https://get.docker.com -o get-docker.sh
    sudo sh get-docker.sh
    rm get-docker.sh
    sudo usermod -aG docker $USER
fi

# 3. Configure Ubuntu UFW Firewall to allow web dashboard (port 8080)
echo "--> Opening firewall port 8080..."
sudo ufw allow 22/tcp
sudo ufw allow 8080/tcp
sudo ufw --force enable

# Note: In Oracle Cloud Console, remember to also add an Ingress Rule for Port 8080
# under Virtual Cloud Network -> VCN -> Security Lists!

# 4. Build and start containers
echo "--> Building and starting Skew Engine production stack..."
sudo docker compose -f docker-compose.prod.yml up -d --build

echo "======================================================"
echo "✅ Skew Engine deployed successfully!"
echo "Check logs using: sudo docker compose -f docker-compose.prod.yml logs -f app"
echo "Access dashboard at: http://$(curl -s ifconfig.me):8080"
echo "======================================================"
