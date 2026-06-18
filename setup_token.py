"""
Charles Schwab Developer API Token Setup Script
===============================================
This script guides you through the Schwab OAuth2 flow and saves the tokens to schwab_tokens.json.
It reads App Key/Secret from a local .env file first, or prompts you and saves them to .env.
No external packages are needed (standard library only).

Just run: python setup_token.py
"""
import urllib.parse
import urllib.request
import webbrowser
import base64
import json
import time
import sys
import os

def load_env():
    env = {}
    if os.path.exists(".env"):
        with open(".env", "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    env[k.strip()] = v.strip().strip('"').strip("'")
    return env

def save_env(client_id, client_secret, redirect_uri):
    with open(".env", "w", encoding="utf-8") as f:
        f.write(f"SCHWAB_CLIENT_ID={client_id}\n")
        f.write(f"SCHWAB_CLIENT_SECRET={client_secret}\n")
        f.write(f"SCHWAB_REDIRECT_URI={redirect_uri}\n")

def main():
    print("=============================================")
    print("   Charles Schwab Developer API Token Setup")
    print("=============================================")

    env = load_env()
    client_id = env.get("SCHWAB_CLIENT_ID")
    client_secret = env.get("SCHWAB_CLIENT_SECRET")
    redirect_uri = env.get("SCHWAB_REDIRECT_URI")

    if client_id and client_secret:
        print("✅ Loaded Schwab credentials from .env")
        if not redirect_uri:
            redirect_uri = "https://127.0.0.1"
    else:
        client_id = input("Enter App Key (Client ID): ").strip()
        client_secret = input("Enter App Secret (Client Secret): ").strip()
        redirect_uri = input("Enter Redirect URI [default: https://127.0.0.1]: ").strip()
        if not redirect_uri:
            redirect_uri = "https://127.0.0.1"
        
        if client_id and client_secret:
            save_env(client_id, client_secret, redirect_uri)
            print("💾 Saved Schwab credentials to .env")
        else:
            print("❌ App Key and Secret are required.")
            sys.exit(1)

    # Generate auth URL
    auth_url = f"https://api.schwabapi.com/v1/oauth/authorize?response_type=code&client_id={client_id}&redirect_uri={urllib.parse.quote(redirect_uri)}"

    print("\nOpening browser to authorize with Schwab...")
    webbrowser.open(auth_url)

    print("\nOnce you log in and authorize, you will be redirected.")
    print("Copy the ENTIRE URL of the redirected page (e.g. https://127.0.0.1/?code=...).")
    redirected_url = input("Paste the redirected URL here: ").strip()

    # Extract code
    code = redirected_url
    if "code=" in redirected_url:
        parsed = urllib.parse.urlparse(redirected_url)
        query = urllib.parse.parse_qs(parsed.query)
        if "code" in query:
            code = query["code"][0]

    # Clean the code parameter (sometimes has trailing # or state)
    if "&" in code:
        code = code.split("&")[0]

    print("\nExchanging code for tokens...")
    
    # Schwab requires Basic Auth header with base64 encoded client_id:client_secret
    auth_str = f"{client_id}:{client_secret}"
    auth_bytes = auth_str.encode('utf-8')
    auth_b64 = base64.b64encode(auth_bytes).decode('utf-8')

    headers = {
        "Authorization": f"Basic {auth_b64}",
        "Content-Type": "application/x-www-form-urlencoded",
        "User-Agent": "Mozilla/5.0"
    }

    body = {
        "grant_type": "authorization_code",
        "code": code,
        "redirect_uri": redirect_uri
    }

    data_bytes = urllib.parse.urlencode(body).encode('utf-8')

    try:
        req = urllib.request.Request(
            "https://api.schwabapi.com/v1/oauth/token",
            data=data_bytes,
            headers=headers,
            method='POST'
        )
        
        with urllib.request.urlopen(req) as response:
            res_body = response.read().decode('utf-8')
            data = json.loads(res_body)
            expires_in = data.get("expires_in", 1800)
            expires_at = int(time.time() * 1000) + (expires_in * 1000)

            token_data = {
                "clientId": client_id,
                "clientSecret": client_secret,
                "redirectUri": redirect_uri,
                "refreshToken": data.get("refresh_token"),
                "accessToken": data.get("access_token"),
                "expiresAt": expires_at
            }

            with open("schwab_tokens.json", "w") as f:
                json.dump(token_data, f, indent=2)

            print("\n✅ SUCCESS! Schwab tokens saved to schwab_tokens.json.")
            print("The Spring Boot skew-engine will automatically load it.")
            
    except urllib.error.HTTPError as e:
        print(f"\n❌ FAILED: HTTP {e.code}")
        print(e.read().decode('utf-8'))
    except Exception as e:
        print(f"\n❌ Error connecting to Schwab API: {e}")

if __name__ == "__main__":
    main()
