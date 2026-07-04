#!/bin/bash
# build-apk.sh — Build APK helper for OBD2-LPG-DataLoggerAndroid
# Usage:
#   build-apk.sh local     — Build locally via proot Ubuntu (slow, offline)
#   build-apk.sh github    — Push to GitHub, trigger Actions, download APK
#   build-apk.sh status    — Check latest GitHub Actions run status
#   build-apk.sh download  — Download APK from latest successful run

set -e

REPO_DIR="$HOME/OBD2-LPG-DataLoggerAndroid"
PROOT_SDK="/root/android-sdk"
PROOT_GRADLE_CACHE="/root/.gradle"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log() { echo -e "${CYAN}[BUILD]${NC} $1"; }
ok() { echo -e "${GREEN}[OK]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
err() { echo -e "${RED}[ERROR]${NC} $1"; }

cmd_local() {
    log "Building APK locally via proot Ubuntu..."
    log "This uses QEMU x86_64 emulation — expect ~10-20 min first build"
    
    # Setup environment inside proot
    proot-distro login ubuntu -- bash -c "
        export ANDROID_HOME=$PROOT_SDK
        export ANDROID_SDK_ROOT=\$ANDROID_HOME
        export GRADLE_USER_HOME=$PROOT_GRADLE_CACHE
        export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
        export PATH=\$ANDROID_HOME/cmdline-tools/latest/bin:\$PATH
        
        # Check SDK is installed
        if [ ! -d \$ANDROID_HOME/platforms/android-35 ]; then
            echo 'Installing SDK components...'
            yes | sdkmanager 'platform-tools' 'build-tools;35.0.0' 'platforms;android-35' 'build-tools;34.0.0' 'platforms;android-34'
        fi
        
        cd $REPO_DIR
        chmod +x gradlew
        
        echo 'Building debug APK...'
        ./gradlew assembleDebug --no-daemon 2>&1
        
        if [ -f app/build/outputs/apk/debug/app-debug.apk ]; then
            echo
            echo '=== BUILD SUCCESS ==='
            ls -lh app/build/outputs/apk/debug/app-debug.apk
            # Copy to accessible location
            cp app/build/outputs/apk/debug/app-debug.apk $REPO_DIR/
            echo 'APK copied to: $REPO_DIR/app-debug.apk'
        else
            echo '=== BUILD FAILED ==='
            exit 1
        fi
    "
}

cmd_github() {
    log "Pushing to GitHub and triggering CI build..."
    
    cd "$REPO_DIR"
    
    # Check for changes
    if [ -n "$(git status --porcelain)" ]; then
        log "Committing local changes..."
        git add -A
        git commit -m "build: trigger CI build from Termux"
    fi
    
    # Push
    log "Pushing to GitHub..."
    git push origin main 2>&1
    
    ok "Pushed! GitHub Actions will build the APK automatically."
    log "Check status: build-apk.sh status"
    log "Download APK: build-apk.sh download"
}

cmd_status() {
    log "Checking latest GitHub Actions run..."
    
    cd "$REPO_DIR"
    
    # Use gh CLI if available, otherwise use curl
    if command -v gh &>/dev/null; then
        gh run list --limit 3
    else
        # Fallback to curl
        REPO=$(git remote get-url origin | sed 's|.*github.com[:/]||;s|\.git$||')
        curl -s "https://api.github.com/repos/$REPO/actions/runs?per_page=3" | \
            python3 -c "
import sys, json
data = json.load(sys.stdin)
for run in data.get('workflow_runs', []):
    status = run['status']
    conclusion = run.get('conclusion', 'in_progress')
    name = run['name']
    created = run['created_at']
    url = run['html_url']
    print(f'{name}: {status}/{conclusion} — {created}')
    print(f'  {url}')
" 2>/dev/null || echo "Install 'gh' CLI for better experience: pkg install gh"
    fi
}

DOWNLOAD_DIR="/sdcard/Download/obd2datalogger"

cmd_download() {
    log "Downloading APK from latest successful build..."
    
    cd "$REPO_DIR"
    mkdir -p "$DOWNLOAD_DIR"
    
    if command -v gh &>/dev/null; then
        # Find latest successful run
        RUN_ID=$(gh run list --limit 1 --status success --json databaseId -q '.[0].databaseId' 2>/dev/null)
        if [ -n "$RUN_ID" ]; then
            gh run download "$RUN_ID" -n OBD2LPGLogger-debug -D "$DOWNLOAD_DIR/"
            ok "APK downloaded to $DOWNLOAD_DIR/"
            ls -lh "$DOWNLOAD_DIR"/*.apk 2>/dev/null
        else
            err "No successful runs found"
        fi
    else
        warn "gh CLI not found. Install it: pkg install gh"
        log "Or download manually from: https://github.com/patnawa/OBD2-LPG-DataLoggerAndroid/actions"
    fi
}

# Main
case "${1:-help}" in
    local)    cmd_local ;;
    github)   cmd_github ;;
    status)   cmd_status ;;
    download) cmd_download ;;
    *)
        echo "OBD2 APK Builder — Termux Build Helper"
        echo
        echo "Usage: build-apk.sh <command>"
        echo
        echo "Commands:"
        echo "  local      Build locally via proot (slow, ~10-20 min first time)"
        echo "  github     Push to GitHub, trigger CI build (fast, ~3-5 min)"
        echo "  status     Check latest GitHub Actions run status"
        echo "  download   Download APK from latest successful CI build"
        echo
        echo "Recommended workflow:"
        echo "  1. Edit code in Termux or Android Studio"
        echo "  2. Run: build-apk.sh github"
        echo "  3. Wait ~3-5 min, then: build-apk.sh download"
        echo "  4. Install: adb install /sdcard/Download/obd2datalogger/app-debug.apk"
        ;;
esac
