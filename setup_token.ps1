# Schwab Token Setup Script
# Run this from a PowerShell terminal: .\setup_token.ps1

Write-Host "=============================================" -ForegroundColor Cyan
Write-Host "   Charles Schwab Developer API Token Setup" -ForegroundColor Cyan
Write-Host "=============================================" -ForegroundColor Cyan

function Get-Env {
    $envVars = @{}
    if (Test-Path ".env") {
        Get-Content ".env" | ForEach-Object {
            $line = $_.Trim()
            if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
                $parts = $line.Split("=", 2)
                $key = $parts[0].Trim()
                $val = $parts[1].Trim().Trim('"').Trim("'")
                $envVars[$key] = $val
            }
        }
    }
    return $envVars
}

function Save-Env {
    param ($clientId, $clientSecret, $redirectUri)
    $content = @(
        "SCHWAB_CLIENT_ID=$clientId",
        "SCHWAB_CLIENT_SECRET=$clientSecret",
        "SCHWAB_REDIRECT_URI=$redirectUri"
    )
    $content | Out-File -FilePath ".env" -Encoding utf8
}

$envVars = Get-Env
$clientId = $envVars["SCHWAB_CLIENT_ID"]
$clientSecret = $envVars["SCHWAB_CLIENT_SECRET"]
$redirectUri = $envVars["SCHWAB_REDIRECT_URI"]

if ($clientId -and $clientSecret) {
    Write-Host "✅ Loaded Schwab credentials from .env" -ForegroundColor Green
    if ([string]::IsNullOrWhiteSpace($redirectUri)) {
        $redirectUri = "https://127.0.0.1"
    }
} else {
    $clientId = Read-Host -Prompt "Enter App Key (Client ID)"
    $clientSecret = Read-Host -Prompt "Enter App Secret (Client Secret)"
    $redirectUri = Read-Host -Prompt "Enter Redirect URI [default: https://127.0.0.1]"
    if ([string]::IsNullOrWhiteSpace($redirectUri)) {
        $redirectUri = "https://127.0.0.1"
    }
    
    $clientId = $clientId.Trim()
    $clientSecret = $clientSecret.Trim()
    $redirectUri = $redirectUri.Trim()
    
    if ($clientId -and $clientSecret) {
        Save-Env $clientId $clientSecret $redirectUri
        Write-Host "💾 Saved Schwab credentials to .env" -ForegroundColor Green
    } else {
        Write-Host "❌ App Key and Secret are required." -ForegroundColor Red
        exit 1
    }
}

# Generate Authorization URL
$authUrl = "https://api.schwabapi.com/v1/oauth/authorize?response_type=code&client_id=$($clientId)&redirect_uri=$([uri]::EscapeDataString($redirectUri))"

Write-Host "`nOpening your browser to authorize with Schwab..." -ForegroundColor Yellow
Start-Process $authUrl

Write-Host "`nOnce you log in and authorize, you will be redirected."
Write-Host "Copy the ENTIRE URL of the redirected page (e.g., https://127.0.0.1/?code=...)."
$redirectedUrl = Read-Host -Prompt "Paste the redirected URL here"

if ([string]::IsNullOrWhiteSpace($redirectedUrl)) {
    Write-Host "URL cannot be empty." -ForegroundColor Red
    exit 1
}

# Extract authorization code from URL
$code = $redirectedUrl
if ($redirectedUrl -match "code=([^&]+)") {
    $code = $Matches[1]
    # URL Decode the code
    $code = [uri]::UnescapeDataString($code)
}

# Remove trailing hash or slash if any
$code = $code.Trim().Replace("#", "").Split("?")[0]

Write-Host "`nExchanging code for tokens..." -ForegroundColor Yellow

$authBytes = [System.Text.Encoding]::UTF8.GetBytes("$($clientId):$($clientSecret)")
$authHeader = "Basic " + [Convert]::ToBase64String($authBytes)

$headers = @{
    "Authorization" = $authHeader
    "Content-Type" = "application/x-www-form-urlencoded"
}

$body = "grant_type=authorization_code&code=$($code)&redirect_uri=$([uri]::EscapeDataString($redirectUri))"

try {
    $response = Invoke-RestMethod -Uri "https://api.schwabapi.com/v1/oauth/token" -Method Post -Headers $headers -Body $body
    
    $expiresIn = 1800
    if ($response.expires_in) { $expiresIn = $response.expires_in }
    $expiresAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + ($expiresIn * 1000)

    $tokenJson = @{
        clientId = $clientId
        clientSecret = $clientSecret
        redirectUri = $redirectUri
        refreshToken = $response.refresh_token
        accessToken = $response.access_token
        expiresAt = $expiresAt
    } | ConvertTo-Json

    $tokenJson | Set-Content -Path "schwab_tokens.json" -Encoding utf8

    Write-Host "`n✅ SUCCESS! Schwab tokens saved to schwab_tokens.json." -ForegroundColor Green
    Write-Host "The Spring Boot skew-engine will automatically load it." -ForegroundColor Green
} catch {
    Write-Host "`n❌ FAILED to exchange code for tokens." -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    if ($_.ErrorDetails) {
        Write-Host $_.ErrorDetails.Message -ForegroundColor Red
    }
    exit 1
}
