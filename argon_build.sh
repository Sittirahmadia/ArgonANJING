#!/data/data/com.termux/files/usr/bin/bash
# ================================================================
#   ARGON MOD — GitHub Actions Build via Termux
#   Push source ke GitHub + trigger + watch build otomatis
# ================================================================

R='\033[0;31m'
G='\033[0;32m'
Y='\033[1;33m'
C='\033[0;36m'
M='\033[0;35m'
W='\033[1;37m'
DIM='\033[2m'
BOLD='\033[1m'
NC='\033[0m'

println() { printf "%b\n" "$1"; }

# ── EDIT BAGIAN INI ───────────────────────────────────────────
GITHUB_USERNAME="Sittirahmadia/ArgonANJING/"
REPO_NAME="ArgonMod"               # nama repo yang akan dibuat/dipakai
BRANCH="main"
# ─────────────────────────────────────────────────────────────

PROJECT_ZIP="/sdcard/Download/ArgonANJING-main.zip"
WORK_DIR="$HOME/argon_build"
REPO_URL="https://github.com/Sittirahmadia/ArgonANJING.git"

check_deps() {
    println "\n${Y}  Cek dependencies...${NC}"
    for cmd in git gh unzip; do
        if ! command -v $cmd > /dev/null 2>&1; then
            println "  ${Y}Installing $cmd...${NC}"
            pkg install -y $cmd
        fi
        println "  ${G}✓ $cmd${NC}"
    done
}

setup_repo() {
    println "\n${Y}  Setup repo lokal...${NC}"

    # Extract ZIP
    rm -rf "$WORK_DIR"
    mkdir -p "$WORK_DIR"
    unzip -q "$PROJECT_ZIP" -d "$WORK_DIR"

    # Cari folder project di dalam ZIP
    PROJECT_DIR=$(find "$WORK_DIR" -maxdepth 1 -type d | grep -v "^$WORK_DIR$" | head -1)
    println "  ${G}✓ Extracted: $PROJECT_DIR${NC}"

    cd "$PROJECT_DIR"

    # Init git jika belum
    if [ ! -d ".git" ]; then
        git init
        git checkout -b main 2>/dev/null || true
    fi

    git config --global user.email "Sittirahmadia67@gmail.com"
    git config --global user.name "Sittirahmadia"
   
 # Add semua file
    git add -A
    git status --short | head -20
}

create_or_set_remote() {
    cd "$PROJECT_DIR"
    println "\n${Y}  Setup remote GitHub...${NC}"

    # Buat repo di GitHub jika belum ada
    if ! gh repo view "$GITHUB_USERNAME/$REPO_NAME" > /dev/null 2>&1; then
        println "  ${Y}  Membuat repo $REPO_NAME di GitHub...${NC}"
        gh repo create "$REPO_NAME" \
            --private \
            --description "Argon Fabric Client Mod (MC 1.21)" \
            --confirm 2>/dev/null \
        || gh repo create "$REPO_NAME" --private --description "Argon Fabric Client Mod" 2>/dev/null
        println "  ${G}✓ Repo dibuat: $REPO_URL${NC}"
    else
        println "  ${G}✓ Repo sudah ada: $REPO_URL${NC}"
    fi

    # Set remote
    git remote remove origin 2>/dev/null || true
    git remote add origin "$REPO_URL"
}

push_and_trigger() {
    cd "$PROJECT_DIR"
    println "\n${Y}  Commit & push ke GitHub...${NC}"

    git add -A
    git commit -m "build: Argon Mod source + GitHub Actions workflow

- AutoDtapV2.java — ms-precision timing, burst place, anti-AC
- AnchorMacroV2.java — fixed interactBlock, totem slot, burst charge
- Workflow: handles lib/annotations, caches Minecraft/Loom assets
- Java 21 | Fabric 0.16.9 | MC 1.21" \
    2>/dev/null || git commit --allow-empty -m "trigger: rebuild"

    # Push
    if git push -u origin "$BRANCH" --force 2>/dev/null; then
        println "  ${G}✓ Push berhasil ke $REPO_URL${NC}"
    else
        println "  ${R}✗ Push gagal — cek gh auth status${NC}"
        return 1
    fi

    # Tunggu GitHub Action ter-trigger
    println "\n  ${Y}Menunggu GitHub Actions ter-trigger...${NC}"
    sleep 5

    # Cari run ID terbaru
    RUN_ID=$(gh run list \
        -R "$GITHUB_USERNAME/$REPO_NAME" \
        --limit 1 \
        --json databaseId \
        -q '.[0].databaseId' 2>/dev/null)

    if [ -z "$RUN_ID" ]; then
        println "  ${Y}  Trigger manual workflow...${NC}"
        gh workflow run build.yml \
            -R "$GITHUB_USERNAME/$REPO_NAME" \
            --ref "$BRANCH" 2>/dev/null
        sleep 5
        RUN_ID=$(gh run list \
            -R "$GITHUB_USERNAME/$REPO_NAME" \
            --limit 1 \
            --json databaseId \
            -q '.[0].databaseId' 2>/dev/null)
    fi

    println "  ${G}✓ Run ID: $RUN_ID${NC}"
    println "  ${C}  URL: https://github.com/$GITHUB_USERNAME/$REPO_NAME/actions/runs/$RUN_ID${NC}"

    watch_build "$RUN_ID"
}

watch_build() {
    RUN_ID="$1"
    println "\n${M}${BOLD}  ════════════════════════════════════${NC}"
    println "${M}${BOLD}  👀 Watching Build #$RUN_ID...${NC}"
    println "${M}${BOLD}  ════════════════════════════════════${NC}"
    println "  ${DIM}Ctrl+C untuk stop watching (build tetap jalan di GitHub)${NC}\n"

    gh run watch "$RUN_ID" \
        -R "$GITHUB_USERNAME/$REPO_NAME" \
        --exit-status 2>/dev/null &
    WATCH_PID=$!

    # Progress ticker di samping
    _dots=0
    while kill -0 $WATCH_PID 2>/dev/null; do
        sleep 3
        _dots=$(( (_dots + 1) % 4 ))
        _bar=""
        _i=0
        while [ $_i -lt $_dots ]; do
            _bar="${_bar}."
            _i=$(( _i + 1 ))
        done
        printf "\r  ${DIM}Building%s   ${NC}" "$_bar"
    done
    println ""

    wait $WATCH_PID
    BUILD_STATUS=$?

    println ""
    if [ $BUILD_STATUS -eq 0 ]; then
        println "${G}${BOLD}  ╔══════════════════════════════════════╗${NC}"
        println "${G}${BOLD}  ║   ✅  BUILD SUCCESS!                 ║${NC}"
        println "${G}${BOLD}  ╚══════════════════════════════════════╝${NC}"
        download_artifacts "$RUN_ID"
    else
        println "${R}${BOLD}  ╔══════════════════════════════════════╗${NC}"
        println "${R}${BOLD}  ║   ❌  BUILD FAILED                   ║${NC}"
        println "${R}${BOLD}  ╚══════════════════════════════════════╝${NC}"
        println "\n${Y}  Log error:${NC}"
        gh run view "$RUN_ID" \
            --log-failed \
            -R "$GITHUB_USERNAME/$REPO_NAME" 2>/dev/null | tail -40
    fi
}

download_artifacts() {
    RUN_ID="$1"
    println "\n${Y}  📥 Download artifact JAR...${NC}"

    ARTIFACT_DIR="/sdcard/Download/argon_artifacts"
    mkdir -p "$ARTIFACT_DIR"

    gh run download "$RUN_ID" \
        -R "$GITHUB_USERNAME/$REPO_NAME" \
        -D "$ARTIFACT_DIR" 2>/dev/null

    if ls "$ARTIFACT_DIR"/**/*.jar > /dev/null 2>&1; then
        println "  ${G}✓ JAR tersimpan di:${NC}"
        find "$ARTIFACT_DIR" -name "*.jar" | while read f; do
            println "  ${C}  $f${NC}  ${DIM}($(du -h "$f" | cut -f1))${NC}"
        done
    else
        println "  ${Y}  Artifact belum ready atau download gagal${NC}"
        println "  ${DIM}  Download manual: https://github.com/$GITHUB_USERNAME/$REPO_NAME/actions/runs/$RUN_ID${NC}"
    fi
}

# ── Menu ──────────────────────────────────────────────────────
show_menu() {
    clear
    println "${M}${BOLD}"
    println "  ╔══════════════════════════════════════════════╗"
    println "  ║   🔨  ARGON MOD — GitHub Actions Build      ║"
    println "  ║   Fabric 1.21 | Java 21 | via Termux        ║"
    println "  ╚══════════════════════════════════════════════╝"
    println "${NC}"

    # Status
    printf "  GitHub  : "
    if gh auth status > /dev/null 2>&1; then
        _user=$(gh api user -q '.login' 2>/dev/null)
        println "${G}✓ Logged in sebagai $_user${NC}"
    else
        println "${R}✗ Belum login — pilih [L]${NC}"
    fi

    printf "  ZIP     : "
    [ -f "$PROJECT_ZIP" ] \
        && println "${G}✓ $PROJECT_ZIP${NC}" \
        || println "${R}✗ Tidak ditemukan${NC}"

    printf "  Repo    : ${C}$REPO_URL${NC}\n"
    println ""

    println "  ${W}${BOLD}MENU:${NC}"
    println "  ${DIM}────────────────────────────────────────${NC}"
    println "  ${M}[1]${NC} 🚀 Full Auto — extract + push + build + download"
    println "  ${M}[2]${NC} 👀 Watch run terbaru"
    println "  ${M}[3]${NC} 📥 Download artifact terbaru"
    println "  ${M}[4]${NC} 📋 Lihat semua runs"
    println "  ${M}[5]${NC} 🔁 Trigger rebuild (tanpa push)"
    println "  ${M}[L]${NC} 🔐 Login GitHub"
    println "  ${R}[0]${NC} ❎ Keluar"
    println "  ${DIM}────────────────────────────────────────${NC}"
    printf "  ${W}Pilih: ${NC}"
    read -r _choice

    case "$_choice" in
        1)
            check_deps
            if ! gh auth status > /dev/null 2>&1; then
                println "\n${R}  Login GitHub dulu! Pilih [L]${NC}"
                press_enter; return
            fi
            if [ ! -f "$PROJECT_ZIP" ]; then
                println "\n${R}  ZIP tidak ditemukan: $PROJECT_ZIP${NC}"
                printf "  ${Y}Masukkan path ZIP: ${NC}"
                read -r PROJECT_ZIP
            fi
            setup_repo
            create_or_set_remote
            push_and_trigger
            ;;
        2)
            RUN_ID=$(gh run list -R "$GITHUB_USERNAME/$REPO_NAME" \
                --limit 1 --json databaseId -q '.[0].databaseId' 2>/dev/null)
            [ -n "$RUN_ID" ] && watch_build "$RUN_ID" \
                || println "${R}  Tidak ada run aktif${NC}"
            ;;
        3)
            RUN_ID=$(gh run list -R "$GITHUB_USERNAME/$REPO_NAME" \
                --limit 1 --json databaseId -q '.[0].databaseId' 2>/dev/null)
            [ -n "$RUN_ID" ] && download_artifacts "$RUN_ID" \
                || println "${R}  Tidak ada run${NC}"
            ;;
        4)
            println "\n${W}  Semua runs:${NC}"
            gh run list -R "$GITHUB_USERNAME/$REPO_NAME" --limit 10
            ;;
        5)
            println "\n  ${Y}Trigger rebuild...${NC}"
            gh workflow run build.yml \
                -R "$GITHUB_USERNAME/$REPO_NAME" \
                --ref "$BRANCH" \
                && println "  ${G}✓ Triggered!${NC}" \
                || println "  ${R}✗ Gagal${NC}"
            sleep 3
            RUN_ID=$(gh run list -R "$GITHUB_USERNAME/$REPO_NAME" \
                --limit 1 --json databaseId -q '.[0].databaseId' 2>/dev/null)
            [ -n "$RUN_ID" ] && watch_build "$RUN_ID"
            ;;
        l|L)
            gh auth login
            ;;
        0)
            println "\n  ${G}Bye!${NC}\n"; exit 0 ;;
        *)
            println "  ${R}Tidak valid${NC}" ;;
    esac

    printf "\n  ${DIM}Tekan Enter...${NC}"; read -r _d
    show_menu
}

show_menu
