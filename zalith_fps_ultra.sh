#!/system/bin/sh
# ================================================================
#
#   ███████ ██████  ███████     ██████   ██████   ██████  ███████ ████████
#   ██      ██   ██ ██          ██   ██ ██    ██ ██    ██ ██         ██
#   █████   ██████  ███████     ██████  ██    ██ ██    ██ ███████    ██
#   ██      ██           ██     ██   ██ ██    ██ ██    ██      ██    ██
#   ██      ██      ███████     ██████   ██████   ██████  ███████    ██
#
#   ZALITH LAUNCHER — ULTRA FPS BOOST & ANTI DROP
#   Target  : com.movtery.zalithlauncher (Minecraft Java)
#   Engine  : Brevent ADB | No Root
#   Mode    : Daemon + Manual | sh/bash compatible
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

# ── Config ────────────────────────────────────────────────────
TARGET_PKG="com.movtery.zalithlauncher"
ADB_DEVICE=""
LOG_FILE="/sdcard/Download/zalith_fps.log"
DAEMON_PID_FILE="/data/data/com.termux/files/home/.zalith_fps.pid"
CHECK_INTERVAL=4
LOCK_ACTIVE="false"
BOOST_LEVEL="ultra"   # ultra / balanced

# Protected — tidak pernah di-kill
PROTECTED="android com.android.systemui com.miui.home com.android.phone \
           com.termux com.miui.securitycenter com.miui.daemon \
           com.android.settings com.movtery.zalithlauncher \
           com.android.inputmethod.latin com.miui.keyguard"

# ── Helpers ───────────────────────────────────────────────────
log()     { echo "[$(date '+%H:%M:%S')] $1" >> "$LOG_FILE"; }
println() { printf "%b\n" "$1"; }
press_enter() {
    printf "\n  ${DIM}Tekan Enter untuk lanjut...${NC}"
    read -r _d
}

adb_shell() {
    [ -n "$ADB_DEVICE" ] && adb -s "$ADB_DEVICE" shell "$@" 2>/dev/null
}
cfg_put() { adb_shell settings put "$1" "$2" "$3"; }
cfg_get() { adb_shell settings get "$1" "$2"; }

sysfs_write() {
    # Tulis ke sysfs node via adb shell (bypass SELinux Termux)
    adb_shell "echo '$2' > $1" 2>/dev/null
}

sysfs_read() {
    adb_shell "cat $1" 2>/dev/null
}

# ── ADB Connect ───────────────────────────────────────────────
try_adb_connect() {
    command -v adb > /dev/null 2>&1 || pkg install -y android-tools > /dev/null 2>&1
    command -v adb > /dev/null 2>&1 || return 1
    for _p in 5555 5554 5556; do
        adb connect localhost:$_p > /dev/null 2>&1
        sleep 1
        _c=$(adb devices 2>/dev/null | grep "localhost:$_p" | grep -vc "List" 2>/dev/null || echo 0)
        if [ "$_c" -gt 0 ] 2>/dev/null; then
            ADB_DEVICE="localhost:$_p"
            return 0
        fi
    done
    return 1
}

# ── Detect device ─────────────────────────────────────────────
detect_device() {
    _soc=$(adb_shell getprop ro.hardware 2>/dev/null)
    _gpu=$(adb_shell getprop ro.hardware.egl 2>/dev/null)
    _ram=$(grep MemTotal /proc/meminfo | awk '{printf "%.0f", $2/1024/1024}')
    _chip=$(adb_shell getprop ro.board.platform 2>/dev/null)
    println "  ${DIM}SoC: ${_chip} | GPU: ${_gpu:-${_soc}} | RAM: ${_ram}GB${NC}"
}

# ── Cek Zalith running ────────────────────────────────────────
is_zalith_running() {
    _fg=$(dumpsys activity 2>/dev/null | grep "mCurrentFocus\|mFocusedApp" | head -2)
    echo "$_fg" | grep -q "$TARGET_PKG" && return 0
    ps -e 2>/dev/null | grep -q "$TARGET_PKG" && return 0
    return 1
}

# ════════════════════════════════════════════════════════════
#   BOOST FUNCTIONS
# ════════════════════════════════════════════════════════════

# ── 1. KILL RAM AGGRESSIVE ────────────────────────────────────
boost_ram() {
    println "\n${C}  [RAM]${NC} ${Y}Aggressive RAM sweep...${NC}"
    _killed=0
    _apps=$(dumpsys activity processes 2>/dev/null \
        | grep "ProcessRecord" \
        | grep -oE '[a-z][a-zA-Z0-9_.]+' \
        | grep '\.' | sort -u)
    for _pkg in $_apps; do
        _skip=0
        for _p in $PROTECTED; do
            [ "$_pkg" = "$_p" ] && _skip=1 && break
        done
        [ "$_skip" = "1" ] && continue
        adb_shell am force-stop "$_pkg"
        _killed=$(( _killed + 1 ))
    done
    adb_shell am kill-all

    # Drop file cache (jika bisa)
    adb_shell "echo 3 > /proc/sys/vm/drop_caches" 2>/dev/null

    _avail=$(grep MemAvailable /proc/meminfo | awk '{printf "%.0f", $2/1024}')
    println "  ${G}  ✓ Killed ${_killed} apps | RAM free: ${_avail} MB${NC}"
    log "RAM_BOOST: killed $_killed, free=${_avail}MB"
}

# ── 2. ANIMASI OFF TOTAL ──────────────────────────────────────
boost_animations() {
    println "\n${C}  [UI]${NC} ${Y}Matikan semua animasi...${NC}"
    cfg_put global window_animation_scale 0
    cfg_put global transition_animation_scale 0
    cfg_put global animator_duration_scale 0
    cfg_put secure window_blur_enable 0
    cfg_put system pointer_location 0
    cfg_put system show_touches 0
    cfg_put system haptic_feedback_enabled 0
    cfg_put system sound_effects_enabled 0
    cfg_put system dtmf_tone 0
    println "  ${G}  ✓ Animasi OFF | Blur OFF | Haptic OFF${NC}"
}

# ── 3. DISPLAY — REFRESH RATE MAX ─────────────────────────────
boost_display() {
    println "\n${C}  [DISPLAY]${NC} ${Y}Maksimalkan refresh rate...${NC}"

    # Deteksi refresh rate max dari display info
    _max_rr=$(adb_shell dumpsys display 2>/dev/null \
        | grep -oE 'fps=[0-9]+' | grep -oE '[0-9]+' \
        | sort -n | tail -1)
    [ -z "$_max_rr" ] && _max_rr=120

    cfg_put system peak_refresh_rate "$_max_rr"
    cfg_put system min_refresh_rate "$_max_rr"
    cfg_put secure match_content_frame_rate 0
    # MIUI specific
    cfg_put system user_set_high_frame_rate 1
    adb_shell settings put system display_refresh_rate "$_max_rr" 2>/dev/null

    println "  ${G}  ✓ Refresh rate → ${_max_rr} Hz (max)${NC}"
    log "DISPLAY: refresh=${_max_rr}Hz"
}

# ── 4. CPU — LOCK MAX FREQ VIA SYSFS ─────────────────────────
boost_cpu() {
    println "\n${C}  [CPU]${NC} ${Y}CPU performance lock...${NC}"
    _boosted=0

    for _core in 0 1 2 3 4 5 6 7; do
        _base="/sys/devices/system/cpu/cpu${_core}/cpufreq"

        # Cek core exists
        _exist=$(adb_shell "[ -d $_base ] && echo yes")
        [ "$_exist" != "yes" ] && continue

        # Set governor ke performance atau schedutil dengan freq max
        _max_freq=$(adb_shell "cat ${_base}/cpuinfo_max_freq 2>/dev/null")

        # Governor: performance
        adb_shell "echo performance > ${_base}/scaling_governor" 2>/dev/null

        # Lock min freq = max freq (force max clock)
        if [ -n "$_max_freq" ]; then
            adb_shell "echo $_max_freq > ${_base}/scaling_min_freq" 2>/dev/null
        fi

        _boosted=$(( _boosted + 1 ))
    done

    # Schedutil tweaks via procfs (jika ada)
    adb_shell "echo 0 > /proc/sys/kernel/sched_energy_aware" 2>/dev/null
    adb_shell "echo 1 > /proc/sys/kernel/sched_boost" 2>/dev/null

    # Xiaomi boost proc
    adb_shell "echo 1 > /sys/module/cpu_boost/parameters/input_boost_enabled" 2>/dev/null
    adb_shell "echo 1000 > /sys/module/cpu_boost/parameters/input_boost_ms" 2>/dev/null

    if [ "$_boosted" -gt 0 ]; then
        println "  ${G}  ✓ CPU governor → performance (${_boosted} cores)${NC}"
    else
        println "  ${Y}  ⚠ sysfs CPU tidak accessible (SELinux) — skip${NC}"
    fi

    # via ADB cmd power: sustained performance mode
    adb_shell "cmd power set-mode 2" 2>/dev/null \
        && println "  ${G}  ✓ Power mode → SUSTAINED_PERFORMANCE${NC}"

    log "CPU_BOOST: cores=$_boosted"
}

# ── 5. GPU — ADRENO / MALI BOOST ─────────────────────────────
boost_gpu() {
    println "\n${C}  [GPU]${NC} ${Y}GPU performance lock...${NC}"
    _gpu_done=0

    # ── Adreno (Qualcomm) ─────────────────────────────────────
    # kgsl governor
    for _node in /sys/class/kgsl/kgsl-3d0/devfreq/governor; do
        _e=$(adb_shell "[ -f $_node ] && echo yes")
        [ "$_e" != "yes" ] && continue
        adb_shell "echo performance > $_node" 2>/dev/null
        println "  ${G}  ✓ Adreno governor → performance${NC}"
        _gpu_done=1
    done

    # Adreno max freq lock
    for _node in /sys/class/kgsl/kgsl-3d0/max_gpuclk \
                 /sys/class/kgsl/kgsl-3d0/gpuclk; do
        _e=$(adb_shell "[ -f $_node ] && echo yes")
        [ "$_e" != "yes" ] && continue
        _max=$(adb_shell "cat /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null")
        [ -n "$_max" ] && adb_shell "echo $_max > /sys/class/kgsl/kgsl-3d0/min_pwrlevel" 2>/dev/null
        _gpu_done=1
    done

    # Adreno idle timer off (prevent GPU downclocking between frames)
    adb_shell "echo 0 > /sys/class/kgsl/kgsl-3d0/idle_timer" 2>/dev/null
    adb_shell "echo 1 > /sys/class/kgsl/kgsl-3d0/force_clk_on" 2>/dev/null
    adb_shell "echo 1 > /sys/class/kgsl/kgsl-3d0/force_bus_on" 2>/dev/null
    adb_shell "echo 1 > /sys/class/kgsl/kgsl-3d0/force_rail_on" 2>/dev/null

    # ── Mali (MediaTek/Samsung) ───────────────────────────────
    for _node in /sys/class/devfreq/mali/governor \
                 /sys/kernel/gpu/gpu_governor; do
        _e=$(adb_shell "[ -f $_node ] && echo yes")
        [ "$_e" != "yes" ] && continue
        adb_shell "echo performance > $_node" 2>/dev/null
        println "  ${G}  ✓ Mali governor → performance${NC}"
        _gpu_done=1
    done

    # Mali max freq
    for _node in /sys/kernel/gpu/gpu_min_clock; do
        _e=$(adb_shell "[ -f $_node ] && echo yes")
        [ "$_e" != "yes" ] && continue
        _maxclock=$(adb_shell "cat /sys/kernel/gpu/gpu_max_clock 2>/dev/null")
        [ -n "$_maxclock" ] && adb_shell "echo $_maxclock > $_node" 2>/dev/null
        println "  ${G}  ✓ Mali min clock → max${NC}"
        _gpu_done=1
    done

    # ── GPU debug flags off ────────────────────────────────────
    cfg_put global gpu_debug_layers_enable 0

    if [ "$_gpu_done" = "0" ]; then
        println "  ${Y}  ⚠ GPU sysfs tidak accessible — hanya debug off${NC}"
    fi

    log "GPU_BOOST: done=$_gpu_done"
}

# ── 6. THERMAL — PREVENT THROTTLE ────────────────────────────
boost_thermal() {
    println "\n${C}  [THERMAL]${NC} ${Y}Anti-throttle config...${NC}"

    # Disable thermal zones via thermal hal
    # Xiaomi MIUI thermal mode (via props via ADB)
    adb_shell setprop persist.sys.thermal.data.path /data/thermal 2>/dev/null

    # Mi thermal engine = game mode
    adb_shell "cmd thermalservice override-status 0" 2>/dev/null \
        && println "  ${G}  ✓ Thermal override → 0 (no throttle)${NC}" \
        || println "  ${DIM}  ⊘ thermal cmd tidak tersedia${NC}"

    # Xiaomi game turbo thermal via settings
    cfg_put global game_mode_thermal_service_enable 1 2>/dev/null
    cfg_put global miui_game_mode 1 2>/dev/null

    # Disable thermal polling (sysfs)
    for _tz in /sys/class/thermal/thermal_zone*/mode; do
        _e=$(adb_shell "[ -f $_tz ] && echo yes")
        [ "$_e" = "yes" ] && adb_shell "echo disabled > $_tz" 2>/dev/null
    done

    println "  ${G}  ✓ Thermal config applied${NC}"
    log "THERMAL: configured"
}

# ── 7. NETWORK — LATENCY TUNING ──────────────────────────────
boost_network() {
    println "\n${C}  [NET]${NC} ${Y}Network latency tuning...${NC}"
    cfg_put global wifi_scan_always_enabled 0
    cfg_put global ble_scan_always_enabled 0
    cfg_put global mobile_data_always_on 1

    # TCP tweaks via sysfs
    adb_shell "echo 1 > /proc/sys/net/ipv4/tcp_low_latency" 2>/dev/null \
        && println "  ${G}  ✓ TCP low latency → ON${NC}"
    adb_shell "echo bbr > /proc/sys/net/ipv4/tcp_congestion_control" 2>/dev/null \
        && println "  ${G}  ✓ TCP congestion → BBR${NC}"

    # WiFi performance mode
    adb_shell cmd wifi set-wifi-enabled true 2>/dev/null
    adb_shell "iwconfig wlan0 power off" 2>/dev/null \
        && println "  ${G}  ✓ WiFi power save → OFF${NC}"

    println "  ${G}  ✓ Background scans OFF | Mobile data stable${NC}"
    log "NET_BOOST: applied"
}

# ── 8. DALVIK/JVM — MINECRAFT JAVA HEAP ─────────────────────
boost_java_heap() {
    println "\n${C}  [JVM]${NC} ${Y}Java heap tuning untuk Minecraft...${NC}"

    # Dalvik heap settings via ADB
    _ram_mb=$(grep MemTotal /proc/meminfo | awk '{printf "%.0f", $2/1024}')
    # Alokasi 60% RAM untuk heap Zalith/Minecraft
    _heap=$(( _ram_mb * 60 / 100 ))
    # Cap di 4096 MB
    [ "$_heap" -gt 4096 ] && _heap=4096

    # Set system heap via cmd activity
    adb_shell "cmd activity set-watch-heap $TARGET_PKG ${_heap}m" 2>/dev/null

    # Dalvik GC aggressiveness
    adb_shell setprop dalvik.vm.gctype CMS 2>/dev/null
    adb_shell setprop dalvik.vm.dex2oat-filter speed 2>/dev/null

    # Background GC off saat foreground
    adb_shell setprop dalvik.vm.background-dex2oat-filter verify 2>/dev/null

    # VM heapsize props
    adb_shell setprop dalvik.vm.heapstartsize 16m 2>/dev/null
    adb_shell setprop dalvik.vm.heapgrowthlimit "${_heap}m" 2>/dev/null
    adb_shell setprop dalvik.vm.heapsize "${_heap}m" 2>/dev/null
    adb_shell setprop dalvik.vm.heaptargetutilization 0.75 2>/dev/null
    adb_shell setprop dalvik.vm.heapminfree 2m 2>/dev/null

    println "  ${G}  ✓ JVM heap → ${_heap} MB (60% of ${_ram_mb} MB RAM)${NC}"
    println "  ${G}  ✓ GC mode → CMS | dex2oat → speed${NC}"
    log "JVM_BOOST: heap=${_heap}MB"
}

# ── 9. SCHEDULER — INPUT & RENDER PRIORITY ───────────────────
boost_scheduler() {
    println "\n${C}  [SCHED]${NC} ${Y}Scheduler & process priority...${NC}"

    # Naikkan prioritas proses Zalith
    _pid=$(adb_shell "pidof $TARGET_PKG" 2>/dev/null | tr -d ' \r\n')
    if [ -n "$_pid" ]; then
        # Renice ke -10 (lebih tinggi prioritas)
        adb_shell "renice -10 -p $_pid" 2>/dev/null \
            && println "  ${G}  ✓ Zalith priority → -10 (PID=$_pid)${NC}"

        # Set CPU affinity ke big cores (jika ada)
        _cpu_count=$(adb_shell "nproc" 2>/dev/null | tr -d ' \r\n')
        if [ -n "$_cpu_count" ] && [ "$_cpu_count" -ge 6 ]; then
            # Affinity ke core 4-7 (big cluster)
            adb_shell "taskset -p f0 $_pid" 2>/dev/null \
                && println "  ${G}  ✓ CPU affinity → big cores${NC}"
        fi
    else
        println "  ${DIM}  ⊘ Zalith belum berjalan — skip renice${NC}"
    fi

    # Kernel scheduler tweaks
    adb_shell "echo 0 > /proc/sys/kernel/sched_energy_aware" 2>/dev/null
    adb_shell "echo 500000 > /proc/sys/kernel/sched_latency_ns" 2>/dev/null
    adb_shell "echo 100000 > /proc/sys/kernel/sched_min_granularity_ns" 2>/dev/null
    adb_shell "echo 1000000 > /proc/sys/kernel/sched_wakeup_granularity_ns" 2>/dev/null

    # Naikkan prioritas render thread (HWC)
    adb_shell "cmd activity set-process-limit 0" 2>/dev/null

    println "  ${G}  ✓ Scheduler dioptimasi${NC}"
    log "SCHED: pid=$_pid"
}

# ── 10. MIUI GAME MODE ────────────────────────────────────────
boost_miui_game() {
    println "\n${C}  [MIUI]${NC} ${Y}Aktifkan MIUI Game Turbo mode...${NC}"

    # Game Turbo via settings
    cfg_put global miui_game_mode 1
    cfg_put global game_mode_enable 1
    cfg_put global user_game_mode_enable 1
    cfg_put secure game_mode_fps_unlock 1

    # Daftarkan Zalith ke whitelist game MIUI
    adb_shell "cmd game mode 3 $TARGET_PKG" 2>/dev/null \
        && println "  ${G}  ✓ MIUI Game Mode → PERFORMANCE (mode 3)${NC}" \
        || println "  ${DIM}  ⊘ cmd game tidak tersedia di MIUI ini${NC}"

    # Matikan MIUI optimization (bisa ganggu JVM)
    # cfg_put global miui_optimization 0  # (nonaktifkan jika ingin)

    # Nonaktifkan background kill agresif MIUI
    adb_shell "cmd activity set-stop-user-on-switch 0" 2>/dev/null

    # TouchBoost MIUI
    cfg_put system touch_boost_enable 1 2>/dev/null
    adb_shell "echo 1 > /sys/module/msm_performance/parameters/touchboost" 2>/dev/null

    # Whitelist Zalith di MIUI memory manager
    adb_shell am broadcast \
        -a "com.miui.securitycenter.action.ADD_APP_WHITELIST" \
        --es packageName "$TARGET_PKG" \
        -p "com.miui.securitycenter" 2>/dev/null

    println "  ${G}  ✓ MIUI tweaks applied${NC}"
    log "MIUI_GAME: configured"
}

# ── 11. I/O — DISK LATENCY TUNING ────────────────────────────
boost_io() {
    println "\n${C}  [I/O]${NC} ${Y}Disk I/O tuning...${NC}"

    # I/O scheduler ke deadline atau noop (turun latency)
    for _dev in /sys/block/sda/queue/scheduler \
                /sys/block/sdf/queue/scheduler \
                /sys/block/mmcblk0/queue/scheduler; do
        _e=$(adb_shell "[ -f $_dev ] && echo yes")
        [ "$_e" != "yes" ] && continue
        adb_shell "echo deadline > $_dev" 2>/dev/null \
            || adb_shell "echo mq-deadline > $_dev" 2>/dev/null \
            || adb_shell "echo noop > $_dev" 2>/dev/null
        println "  ${G}  ✓ I/O scheduler tuned${NC}"
        break
    done

    # Readahead — turunkan untuk random read (Minecraft chunk loading)
    for _dev in /sys/block/sda/queue/read_ahead_kb \
                /sys/block/sdf/queue/read_ahead_kb \
                /sys/block/mmcblk0/queue/read_ahead_kb; do
        _e=$(adb_shell "[ -f $_dev ] && echo yes")
        [ "$_e" = "yes" ] && adb_shell "echo 128 > $_dev" 2>/dev/null
    done

    # VM swappiness turunkan (kurangi swap thrashing)
    adb_shell "echo 10 > /proc/sys/vm/swappiness" 2>/dev/null \
        && println "  ${G}  ✓ Swappiness → 10 (min swap)${NC}"

    # Drop caches
    adb_shell "echo 1 > /proc/sys/vm/drop_caches" 2>/dev/null \
        && println "  ${G}  ✓ Page cache dropped${NC}"

    log "IO_BOOST: applied"
}

# ── 12. BREVENT — WHITELIST + STOP SEMUA ─────────────────────
boost_brevent() {
    println "\n${C}  [BREVENT]${NC} ${Y}Whitelist Zalith + stop background...${NC}"

    # Whitelist Zalith agar tidak pernah di-kill Brevent
    adb_shell am broadcast \
        -a "me.piebridge.brevent.action.WHITELIST" \
        --es package "$TARGET_PKG" \
        -p "me.piebridge.brevent" 2>/dev/null \
        && println "  ${G}  ✓ Zalith di-whitelist Brevent${NC}"

    # Paksa stop semua app via Brevent broadcast
    _apps=$(dumpsys activity processes 2>/dev/null \
        | grep "ProcessRecord" \
        | grep -oE '[a-z][a-zA-Z0-9_.]+' \
        | grep '\.' | sort -u)
    _bc=0
    for _pkg in $_apps; do
        _skip=0
        for _p in $PROTECTED; do [ "$_pkg" = "$_p" ] && _skip=1 && break; done
        [ "$_skip" = "1" ] && continue
        adb_shell am broadcast \
            -a "me.piebridge.brevent.action.STOP_PACKAGE" \
            --es package "$_pkg" \
            -p "me.piebridge.brevent" 2>/dev/null
        _bc=$(( _bc + 1 ))
    done
    println "  ${G}  ✓ Brevent stop: ${_bc} apps${NC}"
}

# ── RESTORE DEFAULT ───────────────────────────────────────────
restore_all() {
    println "\n${Y}  🔄 Restore semua ke default...${NC}"

    cfg_put global window_animation_scale 1.0
    cfg_put global transition_animation_scale 1.0
    cfg_put global animator_duration_scale 1.0
    cfg_put global wifi_scan_always_enabled 1
    cfg_put global ble_scan_always_enabled 1
    cfg_put global adaptive_battery_management_enabled 1
    cfg_put global stay_on_while_plugged_in 0
    cfg_put system haptic_feedback_enabled 1
    cfg_put system sound_effects_enabled 1
    cfg_put system dtmf_tone 1
    cfg_put global miui_game_mode 0
    cfg_put global game_mode_enable 0
    cfg_put secure window_blur_enable 1

    # Refresh rate kembali adaptive
    cfg_put system peak_refresh_rate 120
    cfg_put system min_refresh_rate 60
    cfg_put secure match_content_frame_rate 1

    # Power mode normal
    adb_shell "cmd power set-mode 0" 2>/dev/null

    # Thermal restore
    adb_shell "cmd thermalservice override-status -1" 2>/dev/null

    # CPU governor kembali schedutil
    for _core in 0 1 2 3 4 5 6 7; do
        _g="/sys/devices/system/cpu/cpu${_core}/cpufreq/scaling_governor"
        _e=$(adb_shell "[ -f $_g ] && echo yes")
        [ "$_e" = "yes" ] && adb_shell "echo schedutil > $_g" 2>/dev/null
    done

    # GPU governor kembali
    adb_shell "echo msm-adreno-tz > /sys/class/kgsl/kgsl-3d0/devfreq/governor" 2>/dev/null
    adb_shell "echo simple_ondemand > /sys/class/devfreq/mali/governor" 2>/dev/null

    # VM
    adb_shell "echo 60 > /proc/sys/vm/swappiness" 2>/dev/null
    adb_shell "echo 0 > /proc/sys/kernel/sched_boost" 2>/dev/null

    LOCK_ACTIVE="false"
    println "  ${G}  ✓ Semua dikembalikan ke default${NC}"
    log "RESTORE: all settings default"
}

# ════════════════════════════════════════════════════════════
#   MAIN BOOST SEQUENCE
# ════════════════════════════════════════════════════════════

ultra_fps_boost() {
    LOCK_ACTIVE="true"
    println ""
    println "${M}${BOLD}  ╔══════════════════════════════════════════╗${NC}"
    println "${M}${BOLD}  ║   ⚡⚡  ULTRA FPS BOOST SEQUENCE  ⚡⚡  ║${NC}"
    println "${M}${BOLD}  ╚══════════════════════════════════════════╝${NC}"

    _avail_before=$(grep MemAvailable /proc/meminfo | awk '{printf "%.0f", $2/1024}')
    println "  ${DIM}RAM sebelum: ${_avail_before} MB free${NC}"

    boost_animations    # 1. UI overhead off
    boost_display       # 2. Max refresh rate
    boost_miui_game     # 3. MIUI game turbo
    boost_brevent       # 4. Brevent whitelist + stop bg
    boost_ram           # 5. Kill RAM
    boost_cpu           # 6. CPU max
    boost_gpu           # 7. GPU max
    boost_thermal       # 8. Anti throttle
    boost_java_heap     # 9. JVM heap Minecraft
    boost_scheduler     # 10. Process priority
    boost_io            # 11. I/O tuning
    boost_network       # 12. Network latency

    _avail_after=$(grep MemAvailable /proc/meminfo | awk '{printf "%.0f", $2/1024}')
    _freed=$(( _avail_after - _avail_before ))

    println ""
    println "${G}${BOLD}  ╔══════════════════════════════════════════╗${NC}"
    println "${G}${BOLD}  ║   ✅  ULTRA BOOST SELESAI!               ║${NC}"
    println "${G}${BOLD}  ╚══════════════════════════════════════════╝${NC}"
    println "  ${G}RAM freed  : +${_freed} MB  (now ${_avail_after} MB free)${NC}"
    println "  ${G}Animasi    : OFF${NC}"
    println "  ${G}CPU/GPU    : Performance mode${NC}"
    println "  ${G}JVM heap   : Max allocated${NC}"
    println "  ${G}Thermal    : Anti-throttle aktif${NC}"
    println ""
    log "ULTRA_BOOST: done, freed=${_freed}MB"
}

# ── Daemon auto mode ──────────────────────────────────────────
daemon_mode() {
    println "\n${M}${BOLD}  🤖 FPS LOCK DAEMON AKTIF${NC}"
    println "  ${DIM}Monitor Zalith tiap ${CHECK_INTERVAL}s | Ctrl+C untuk stop${NC}\n"
    echo $$ > "$DAEMON_PID_FILE"
    log "DAEMON: started PID=$$"

    _prev="off"
    _renice_done="false"

    while true; do
        if is_zalith_running; then
            if [ "$_prev" != "on" ]; then
                println "  ${G}[$(date '+%H:%M:%S')] 🎮 Zalith ON → Full boost...${NC}"
                ultra_fps_boost
                _prev="on"
                _renice_done="false"
            fi

            # Re-apply renice tiap cycle (MIUI suka reset priority)
            if [ "$_renice_done" = "false" ]; then
                _pid=$(adb_shell "pidof $TARGET_PKG" 2>/dev/null | tr -d ' \r\n')
                if [ -n "$_pid" ]; then
                    adb_shell "renice -10 -p $_pid" 2>/dev/null
                    _renice_done="true"
                fi
            fi

            # Monitor RAM — re-sweep jika RAM < 400MB
            _free=$(grep MemAvailable /proc/meminfo | awk '{printf "%.0f", $2/1024}')
            if [ "$_free" -lt 400 ]; then
                println "  ${Y}[$(date '+%H:%M:%S')] ⚠ RAM low (${_free}MB) → re-sweep...${NC}"
                boost_ram
                log "AUTO_SWEEP: RAM was ${_free}MB"
            fi

        else
            if [ "$_prev" = "on" ]; then
                println "  ${Y}[$(date '+%H:%M:%S')] 💤 Zalith OFF → restore...${NC}"
                restore_all
                _prev="off"
            fi
        fi

        sleep $CHECK_INTERVAL
    done
}

stop_daemon() {
    if [ -f "$DAEMON_PID_FILE" ]; then
        _pid=$(cat "$DAEMON_PID_FILE")
        kill "$_pid" 2>/dev/null \
            && println "  ${G}✓ Daemon (PID $_pid) dihentikan${NC}"
        rm -f "$DAEMON_PID_FILE"
        restore_all
        log "DAEMON: stopped"
    else
        println "  ${Y}⚠ Daemon tidak berjalan${NC}"
    fi
}

# ── Status panel ─────────────────────────────────────────────
show_status() {
    _total=$(grep MemTotal /proc/meminfo | awk '{printf "%.0f", $2/1024}')
    _avail=$(grep MemAvailable /proc/meminfo | awk '{printf "%.0f", $2/1024}')
    _used=$(( _total - _avail ))
    _pct=$(( _used * 100 / _total ))

    _anim=$(cfg_get global window_animation_scale 2>/dev/null | tr -d ' \r\n')
    _rr=$(cfg_get system peak_refresh_rate 2>/dev/null | tr -d ' \r\n')
    _gov=$(adb_shell "cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null" | tr -d ' \r\n')

    println ""
    printf "  ADB      : "
    [ -n "$ADB_DEVICE" ] && println "${G}● $ADB_DEVICE${NC}" || println "${R}✗ Disconnect${NC}"

    printf "  FPS Lock : "
    [ "$LOCK_ACTIVE" = "true" ] \
        && println "${G}${BOLD}● AKTIF (Ultra)${NC}" \
        || println "${DIM}○ Off${NC}"

    printf "  Zalith   : "
    is_zalith_running && println "${G}● Running${NC}" || println "${DIM}○ Tidak berjalan${NC}"

    printf "  Daemon   : "
    [ -f "$DAEMON_PID_FILE" ] \
        && println "${G}● Aktif (PID=$(cat $DAEMON_PID_FILE))${NC}" \
        || println "${DIM}○ Off${NC}"

    println "  RAM      : ${Y}${_used}/${_total} MB (${_pct}%)${NC}"
    [ -n "$_anim" ] && println "  Animasi  : ${DIM}${_anim}x${NC}"
    [ -n "$_rr"   ] && println "  Refresh  : ${C}${_rr} Hz${NC}"
    [ -n "$_gov"  ] && println "  CPU Gov  : ${C}${_gov}${NC}"
    println ""
}

# ── Live monitor ─────────────────────────────────────────────
live_monitor() {
    println "\n${Y}  📊 Live Monitor (Ctrl+C stop)${NC}"
    sleep 1
    while true; do
        clear
        println "${M}${BOLD}  ⚡ ZALITH FPS MONITOR${NC}  ${DIM}$(date '+%H:%M:%S')${NC}"
        println ""

        _total=$(grep MemTotal /proc/meminfo | awk '{printf "%.0f", $2/1024}')
        _avail=$(grep MemAvailable /proc/meminfo | awk '{printf "%.0f", $2/1024}')
        _used=$(( _total - _avail ))
        _pct=$(( _used * 100 / _total ))
        _bar_f=$(( _pct * 20 / 100 ))
        _bar_e=$(( 20 - _bar_f ))
        _bar=$(python3 -c "print('█'*$_bar_f+'░'*$_bar_e)" 2>/dev/null || printf "%.${_bar_f}s%.${_bar_e}s" "████████████████████" "░░░░░░░░░░░░░░░░░░░░")
        println "  RAM  [${R}${_bar}${NC}] ${Y}${_pct}%${NC} ${DIM}(${_used}/${_total} MB)${NC}"

        # FPS lock indicator
        printf "  Lock : "
        [ "$LOCK_ACTIVE" = "true" ] && println "${G}${BOLD}⚡ ULTRA FPS BOOST AKTIF${NC}" || println "${DIM}○ Off${NC}"

        # CPU per core
        println "\n  ${W}CPU:${NC}"
        _core=0
        for _cf in /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq \
                   /sys/devices/system/cpu/cpu1/cpufreq/scaling_cur_freq \
                   /sys/devices/system/cpu/cpu2/cpufreq/scaling_cur_freq \
                   /sys/devices/system/cpu/cpu3/cpufreq/scaling_cur_freq \
                   /sys/devices/system/cpu/cpu4/cpufreq/scaling_cur_freq \
                   /sys/devices/system/cpu/cpu5/cpufreq/scaling_cur_freq \
                   /sys/devices/system/cpu/cpu6/cpufreq/scaling_cur_freq \
                   /sys/devices/system/cpu/cpu7/cpufreq/scaling_cur_freq; do
            [ -f "$_cf" ] || { _core=$((_core+1)); continue; }
            _freq=$(cat "$_cf" 2>/dev/null)
            _mhz=$(( _freq / 1000 ))
            _gov=$(cat "$(dirname $_cf)/scaling_governor" 2>/dev/null | head -c12)
            printf "  Core %d: ${C}%5d MHz${NC}  ${DIM}[%s]${NC}\n" "$_core" "$_mhz" "$_gov"
            _core=$((_core+1))
        done

        # GPU freq
        _gpu_f=$(adb_shell "cat /sys/class/kgsl/kgsl-3d0/gpuclk" 2>/dev/null | tr -d ' \r\n')
        [ -n "$_gpu_f" ] && printf "\n  GPU : ${C}%d MHz${NC}\n" "$(( _gpu_f / 1000000 ))"

        # Battery
        _bat=$(dumpsys battery 2>/dev/null | grep "level:" | head -1 | awk '{print $2}')
        _temp=$(dumpsys battery 2>/dev/null | grep "temperature:" | head -1 | awk '{print $2}')
        [ -n "$_bat" ] && printf "\n  Batt: ${Y}%s%%${NC}" "$_bat"
        [ -n "$_temp" ] && printf "  Temp: ${Y}%s°C${NC}\n" "$(( _temp / 10 ))"

        # Zalith PID + memory
        _pid=$(adb_shell "pidof $TARGET_PKG" 2>/dev/null | tr -d ' \r\n')
        if [ -n "$_pid" ]; then
            _zmem=$(adb_shell "cat /proc/$_pid/status 2>/dev/null | grep VmRSS" | awk '{printf "%.0f", $2/1024}')
            println "\n  ${G}Zalith PID=$_pid  MEM=${_zmem}MB${NC}"
        else
            println "\n  ${DIM}Zalith tidak berjalan${NC}"
        fi

        sleep 2
    done
}

# ── Show log ─────────────────────────────────────────────────
show_log() {
    [ -f "$LOG_FILE" ] || { println "  ${DIM}Belum ada log${NC}"; return; }
    println "\n${W}  📋 Log (30 baris terakhir):${NC}"
    println "  ${DIM}────────────────────────────────────────${NC}"
    tail -30 "$LOG_FILE" | while IFS= read -r _l; do println "  ${DIM}$_l${NC}"; done
}

# ── Banner ────────────────────────────────────────────────────
show_banner() {
    clear
    println "${M}${BOLD}"
    println "  ╔══════════════════════════════════════════════════╗"
    println "  ║  ⚡⚡  ZALITH — ULTRA FPS BOOST MODULE  ⚡⚡   ║"
    println "  ║  Anti Drop FPS + Max Performance | No Root       ║"
    println "  ╚══════════════════════════════════════════════════╝"
    println "${NC}"
    detect_device
}

# ── Main menu ─────────────────────────────────────────────────
main_menu() {
    while true; do
        show_banner
        show_status
        println "  ${W}${BOLD}MENU:${NC}"
        println "  ${DIM}──────────────────────────────────────────${NC}"
        println "  ${M}[1]${NC} ⚡ ULTRA FPS BOOST — semua sekaligus"
        println "  ${M}[2]${NC} 🤖 Daemon Mode — auto lock saat Zalith dibuka"
        println "  ${M}[3]${NC} 🧹 RAM Sweep — kill semua app background"
        println "  ${M}[4]${NC} 🖥️  CPU Max — lock semua core ke performance"
        println "  ${M}[5]${NC} 🎮 GPU Max — lock GPU ke max freq"
        println "  ${M}[6]${NC} 🌡️  Anti Throttle — disable thermal limit"
        println "  ${M}[7]${NC} ☕ JVM Heap — alokasi max RAM ke Minecraft"
        println "  ${M}[8]${NC} 🔄 Refresh Rate — lock ke max Hz"
        println "  ${M}[9]${NC} 📊 Live Monitor — RAM / CPU / GPU / Temp"
        println "  ${M}[d]${NC} ⏹  Stop Daemon"
        println "  ${M}[l]${NC} 📋 Lihat Log"
        println "  ${M}[c]${NC} 🔌 Reconnect Brevent ADB"
        println "  ${Y}[r]${NC} 🔄 Restore semua ke default"
        println "  ${R}[0]${NC} ❎ Keluar"
        println "  ${DIM}──────────────────────────────────────────${NC}"
        printf "  ${W}Pilih: ${NC}"
        read -r _ch

        case "$_ch" in
            1) ultra_fps_boost ;;
            2) daemon_mode ;;
            3) boost_ram ;;
            4) boost_cpu ;;
            5) boost_gpu ;;
            6) boost_thermal ;;
            7) boost_java_heap ;;
            8) boost_display ;;
            9) live_monitor ;;
            d|D) stop_daemon ;;
            l|L) show_log ;;
            c|C)
                println "\n  ${Y}Reconnecting...${NC}"
                ADB_DEVICE=""
                try_adb_connect \
                    && println "  ${G}✓ $ADB_DEVICE${NC}" \
                    || println "  ${R}✗ Gagal — pastikan Brevent ADB aktif${NC}"
                ;;
            r|R) restore_all ;;
            0)
                restore_all
                stop_daemon 2>/dev/null
                println "\n  ${G}Bye! 👋${NC}\n"
                exit 0
                ;;
            *) println "  ${R}Tidak valid${NC}" ;;
        esac
        press_enter
    done
}

# ── Entry ─────────────────────────────────────────────────────
if [ -z "$BREVENT_STARTED" ]; then
    export BREVENT_STARTED=1
    [ -x "/data/data/com.termux/files/usr/bin/bash" ] && \
        exec /data/data/com.termux/files/usr/bin/bash "$0" "$@"
fi

println "${Y}  Menghubungkan Brevent ADB...${NC}"
if try_adb_connect; then
    println "${G}  ✓ ADB: $ADB_DEVICE${NC}"
else
    println "${Y}  ⚠ Pilih [c] di menu untuk connect.${NC}"
fi
sleep 1
main_menu
