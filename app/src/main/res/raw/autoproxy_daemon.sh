#!/system/bin/sh
# AutoProxyLSP root control daemon: foreground polling + proxy start/grace/idle/stop.
# Runs as a native root process (immune to HyperOS app freezing/killing).

PIDFILE=/data/local/tmp/autoproxy_lsp.pid
if [ -f "$PIDFILE" ]; then
  oldpid=$(cat "$PIDFILE" 2>/dev/null)
  if [ -n "$oldpid" ] && kill -0 "$oldpid" 2>/dev/null; then exit 0; fi
fi
echo $$ > "$PIDFILE"

if [ "$(getprop sys.boot_completed)" != "1" ]; then
  while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 5; done
  sleep 15
fi

CONF=/data/data/com.autoproxy.lsp/files/autoproxy.conf
STATE=/data/local/tmp/autoproxy_lsp.state
OURS=/data/local/tmp/autoproxy_ours
LOG=/data/data/com.autoproxy.lsp/files/run.log
WD=com.autoproxy.lsp/com.autoproxy.lsp.WatchdogService
CMFA=com.github.metacubex.clash.meta/com.github.kr328.clash.ExternalControlActivity
START=com.github.metacubex.clash.meta.action.START_CLASH
STOP=com.github.metacubex.clash.meta.action.STOP_CLASH

ever=0
ingrace=0
deadline=0
lastbytes=-1
lasttime=0
rate=0
ours=$(cat "$OURS" 2>/dev/null)
[ -n "$ours" ] || ours=0
fg=""
prev=""

log() { echo "$(date '+%m-%d %H:%M:%S') [I] $1" >> "$LOG" 2>/dev/null; }

label() {
  case "$1" in
    com.google.android.apps.bard) echo "Gemini" ;;
    com.openai.chatgpt) echo "ChatGPT" ;;
    *) echo "$1" ;;
  esac
}

vpn_up() { ip addr show tun0 >/dev/null 2>&1; }

start_proxy() {
  log "尝试通过 Root 启动代理"
  am start -n "$CMFA" -a "$START" >/dev/null 2>&1
  ours=1; echo 1 > "$OURS"
  log "代理已启动（Root）"
}

stop_proxy() {
  log "后台空闲已确认，自动关闭代理"
  am start -n "$CMFA" -a "$STOP" >/dev/null 2>&1
  ours=0; echo 0 > "$OURS"
  log "代理已关闭"
}

read_conf() {
  enabled=1; targets="com.google.android.apps.bard com.openai.chatgpt"; grace=60; idle=20; onlyours=1
  if [ -f "$CONF" ]; then
    v=$(grep '^enabled=' "$CONF" 2>/dev/null | cut -d= -f2); [ -n "$v" ] && enabled="$v"
    v=$(grep '^targets=' "$CONF" 2>/dev/null | cut -d= -f2); [ -n "$v" ] && targets="$v"
    v=$(grep '^grace=' "$CONF" 2>/dev/null | cut -d= -f2); [ -n "$v" ] && grace="$v"
    v=$(grep '^idle=' "$CONF" 2>/dev/null | cut -d= -f2); [ -n "$v" ] && idle="$v"
    v=$(grep '^onlyours=' "$CONF" 2>/dev/null | cut -d= -f2); [ -n "$v" ] && onlyours="$v"
  fi
}

detect() {
  out=$(dumpsys activity activities 2>/dev/null)
  focused=$(echo "$out" | grep "topDisplayFocusedRootTask=")
  fg=""
  for tg in $targets; do
    if echo "$out" | grep -qE "ResumedActivity: ActivityRecord\{[^ ]+ u[0-9]+ $tg/"; then
      fg="$fg $tg"
    elif echo "$focused" | grep -qE "I=$tg/|A=[0-9]+:$tg}"; then
      fg="$fg $tg"
    fi
  done
  fg=$(echo $fg)
}

sample_traffic() {
  now=$1
  bytes=$(grep 'tun0:' /proc/net/dev 2>/dev/null | awk '{print $2 + $10}')
  [ -n "$bytes" ] || bytes=0
  if [ "$lastbytes" -ge 0 ] && [ "$now" -gt "$lasttime" ]; then
    dt=$((now - lasttime))
    delta=$((bytes - lastbytes))
    [ "$delta" -lt 0 ] && delta=0
    rate=$((delta / 1024 / dt))
  else
    rate=0
  fi
  lastbytes=$bytes
  lasttime=$now
}

write_state() {
  p=0; vpn_up && p=1
  rem=0
  [ "$ingrace" = "1" ] && rem=$((deadline - $(date +%s)))
  [ "$rem" -lt 0 ] && rem=0
  {
    echo "fg=$fg"
    echo "proxy=$p"
    echo "ingrace=$ingrace"
    echo "rate=$rate"
    echo "remain=$rem"
  } > "$STATE" 2>/dev/null
}

log "Root 守护已启动（前台轮询 2s）"
n=0
while true; do
  n=$((n + 1))
  if [ $((n % 3)) -eq 0 ]; then
    am start-foreground-service -n "$WD" >/dev/null 2>&1
  fi
  read_conf
  now=$(date +%s)
  if [ "$enabled" != "1" ]; then
    fg=""; prev=""; write_state; sleep 2; continue
  fi
  detect
  if [ "$fg" != "$prev" ]; then
    for p in $fg; do
      case " $prev " in *" $p "*) ;; *) log "$(label "$p") 进入前台" ;; esac
    done
    for p in $prev; do
      case " $fg " in *" $p "*) ;; *) log "$(label "$p") 退到后台" ;; esac
    done
    prev="$fg"
  fi
  if [ -n "$fg" ]; then
    ever=1; ingrace=0
    vpn_up || start_proxy
  else
    if [ "$ever" = "1" ] && [ "$ingrace" = "0" ]; then
      ingrace=1; deadline=$((now + grace)); lastbytes=-1; rate=0
      log "全部监控应用已退到后台，开始 ${grace}s 宽限期"
    fi
    if [ "$ingrace" = "1" ]; then
      sample_traffic "$now"
      [ "$rate" -gt "$idle" ] && deadline=$((now + 30))
      if [ "$now" -ge "$deadline" ]; then
        ingrace=0; ever=0
        if [ "$ours" = "1" ] || [ "$onlyours" != "1" ]; then
          stop_proxy
        else
          log "代理非模块启动，保持运行"
        fi
      fi
    fi
  fi
  write_state
  sleep 2
done