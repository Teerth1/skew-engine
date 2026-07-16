# ==============================================================================
# Skew Engine — 1-Click Paper Trading Startup Script
# Run this from a PowerShell terminal: .\start_paper_trading.ps1
# ==============================================================================

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "   🚀 Starting Skew Engine for Live Paper Trading" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Check for .env file
if (-not (Test-Path ".env")) {
    Write-Host "⚠️  Warning: .env file not found! Make sure your API keys (Alpaca, Gemini, Schwab) are configured as environment variables or create a .env file." -ForegroundColor Yellow
} else {
    Write-Host "✅ Found .env configuration." -ForegroundColor Green
}

# 2. Start Docker containers (Postgres & Kafka)
Write-Host "`n📦 Starting PostgreSQL and Kafka via Docker Compose..." -ForegroundColor Yellow
try {
    docker compose up -d
    if ($LASTEXITCODE -ne 0) {
        throw "Docker compose failed."
    }
    Write-Host "✅ Docker containers started successfully." -ForegroundColor Green
} catch {
    Write-Host "❌ Error starting Docker containers. Please ensure Docker Desktop is running!" -ForegroundColor Red
    exit 1
}

# 3. Wait for database and broker to be ready
Write-Host "`n⏳ Waiting 10 seconds for Kafka and PostgreSQL to initialize..." -ForegroundColor Yellow
Start-Sleep -Seconds 10

# 4. Instructions for opening the dashboard
Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host "   🌐 Dashboard will be available at: http://localhost:8080" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "Once started, open your browser to http://localhost:8080 and click 'Start Stream'!" -ForegroundColor Cyan
Write-Host "`n🔥 Launching Skew Engine Spring Boot Application..." -ForegroundColor Yellow

# 5. Launch Spring Boot
.\mvnw.cmd spring-boot:run
