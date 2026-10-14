#!/system/bin/sh

# APatch/FolkPatch jailbreak activation.
#
# The privilege assistant already has a root shell when this script starts.
# Follow APD's jailbreak order: late-load first, then emulate a soft reboot so
# APD can run its post-fs-data/services stages and install sucompat.

DATA_APP_ROOT=/data/app
TMP_ROOT=/data/local/tmp
PERSIST_ROOT=/data/adb
APATCH_ROOT="$PERSIST_ROOT/ap"
APATCH_BIN_ROOT="$APATCH_ROOT/bin"
APATCH_LOG_ROOT="$APATCH_ROOT/log"
APATCH_PACKAGE_CONFIG="$APATCH_ROOT/package_config"
APATCH_SU_PATH="$APATCH_ROOT/su_path"
APATCH_SUCOMPAT="$APATCH_ROOT/sucompat"
APATCH_FPD="$APATCH_BIN_ROOT/fpd"
APATCH_KPATCH="$APATCH_BIN_ROOT/kpatch"
APATCH_SU_ENTRY=/dev/.yhroot_ap_su
APD_REAL="$APATCH_ROOT/.yhroot_apd"
APATCH_SU_WORKER="$APATCH_ROOT/.yhroot_su_worker.sh"
APATCH_SHELL_BRIDGE="$APATCH_ROOT/.yhroot_shell_bridge.sh"

# The exploit shell does not always inherit Android's normal PATH. APD's
# soft-reboot worker resolves `stop` and `start` by name, so keep the platform
# directories ahead of the APatch helpers throughout the whole script.
export PATH="/system/bin:/system/xbin:/system_ext/bin:/product/bin:/vendor/bin:/vendor/xbin:$APATCH_BIN_ROOT:${PATH:-}"

APD_SOURCE=
APD=
APK=
AP_PACKAGE=
# Keep the freshly extracted module under a private name. APD's soft-reboot
# preparation may truncate the conventional kernelpatch.ko path; reusing that
# file is what previously produced an all-zero ELF for late-load.
AP_MODULE="$APATCH_ROOT/.yhroot_kernelpatch.ko"
AP_MODULE_TMP="$TMP_ROOT/.yhroot_kernelpatch.ko"
CLEANUP_DONE=0
APD_FD=
KPATCH_FD=

cleanup_tmp() {
  [ "$CLEANUP_DONE" -eq 1 ] && return 0

  # The caller requires the whole temporary directory to be cleared. Run the
  # removal from / so deleting the running script does not change the cwd.
  (
    trap '' HUP INT TERM
    cd / 2>/dev/null || exit 0
    for ENTRY in \
      "$TMP_ROOT"/* \
      "$TMP_ROOT"/.[!.]* \
      "$TMP_ROOT"/..?*; do
      [ -e "$ENTRY" ] || [ -L "$ENTRY" ] || continue
      rm -rf "$ENTRY" 2>/dev/null || exit 1
    done

    for ENTRY in \
      "$TMP_ROOT"/* \
      "$TMP_ROOT"/.[!.]* \
      "$TMP_ROOT"/..?*; do
      [ -e "$ENTRY" ] || [ -L "$ENTRY" ] || continue
      exit 1
    done
  ) </dev/null >/dev/null 2>&1
  [ "$?" -eq 0 ] || return 1
  CLEANUP_DONE=1
  return 0
}

fail() {
  echo "[!] $1"
  # A preflight failure must leave the temporary root session available for
  # retry. The full directory is cleared once the runtime is staged, before
  # late-load changes the caller's SELinux access.
  exit "${2:-1}"
}

extract_apk_entry() {
  ENTRY="$1"
  DESTINATION="$2"

  if command -v unzip >/dev/null 2>&1; then
    unzip -p "$APK" "$ENTRY" > "$DESTINATION" 2>/dev/null
    [ $? -eq 0 ] && return 0
  fi
  if command -v toybox >/dev/null 2>&1; then
    toybox unzip -p "$APK" "$ENTRY" > "$DESTINATION" 2>/dev/null
    return $?
  fi
  return 1
}

discover_apatch() {
  APD_SOURCE=$(find "$DATA_APP_ROOT" -name libapd.so 2>/dev/null | head -n 1)
  [ -n "$APD_SOURCE" ] || return 1

  # libapd.so is normally extracted under <package-dir>/lib/<abi>/.
  # Derive base.apk from that directory instead of assuming a package name.
  APP_DIR=${APD_SOURCE%/lib/*/libapd.so}
  [ "$APP_DIR" != "$APD_SOURCE" ] || return 1
  APK="$APP_DIR/base.apk"
  [ -f "$APK" ] || return 1

  AP_PACKAGE=
  PACKAGE_LIST=
  if command -v cmd >/dev/null 2>&1; then
    PACKAGE_LIST=$(cmd package list packages -f 2>/dev/null)
  fi
  if [ -z "$PACKAGE_LIST" ] && command -v pm >/dev/null 2>&1; then
    PACKAGE_LIST=$(pm list packages -f 2>/dev/null)
  fi
  if [ -n "$PACKAGE_LIST" ]; then
    while IFS= read -r PACKAGE_LINE; do
      case "$PACKAGE_LINE" in
        package:"$APK"=*)
          AP_PACKAGE=${PACKAGE_LINE##*=}
          break
          ;;
      esac
    done <<EOF
$PACKAGE_LIST
EOF
  fi

  # The temporary root created by the exploit may not reach Android's package
  # Binder service. The installed APK directory still carries the package
  # name, so use it as the fallback.
  if [ -z "$AP_PACKAGE" ]; then
    APP_NAME=${APP_DIR##*/}
    AP_PACKAGE=${APP_NAME%%-*}
  fi

  [ -n "$AP_PACKAGE" ]
}

stage_apd() {
  mkdir -p "$PERSIST_ROOT" "$APATCH_ROOT" 2>/dev/null || return 1
  rm -f "$APD_REAL" 2>/dev/null
  cp -f "$APD_SOURCE" "$APD_REAL" 2>/dev/null || return 1
  chmod 755 "$APD_REAL" 2>/dev/null || return 1
  restorecon "$APD_REAL" 2>/dev/null

  # Keep official APD lifecycle commands native. Authorized su calls from
  # apps use a clean root worker, because the bundled LKM leaves the app's
  # seccomp filter attached and a fork reactivates it (SIGSYS in MT).
  rm -f "$APD" 2>/dev/null
  cat > "$APD" <<'APD_WRAPPER'
#!/system/bin/sh
REAL_APD=/data/adb/ap/.yhroot_apd
IPC=/dev/.yhroot_ap_ipc
SHELL_MODE=0
if [ "$1" = --yh-shell ]; then
  SHELL_MODE=1
  shift
fi
if [ "$SHELL_MODE" -eq 0 ]; then
case "${0##*/}" in magiskpolicy|resetprop) exec -a "${0##*/}" "$REAL_APD" "$@";; esac
case "$1" in
  --superkey|--superkey=*|module|post-fs-data|services|boot-completed|uid-listener|insmod|late-load|soft-reboot|resetprop|sepolicy|lua|kpm|config|--version) exec "$REAL_APD" "$@";;
  -s) case "$3" in module|post-fs-data|services|boot-completed|uid-listener|insmod|late-load|soft-reboot|resetprop|sepolicy|lua|kpm|config) exec "$REAL_APD" "$@";; esac;;
esac
fi
# Preserve ordinary APD execution for sessions that have no app seccomp filter.
# Root Bash scripts must use APatch's su directly, rather than an app's shim.
if [ "${BASH_ENV:-}" != /data/adb/ap/.yhroot_bash_env ]; then
  export YH_AP_BASH_ENV_ORIGINAL="${BASH_ENV:-}"
fi
export BASH_ENV=/data/adb/ap/.yhroot_bash_env
SECCOMP_STATE=
while read -r FIELD VALUE REST; do
  if [ "$FIELD" = Seccomp: ]; then
    SECCOMP_STATE="$VALUE"
    break
  fi
done < /proc/self/status
# Restore stdin before exec: executing inside the redirected loop would pass
# /proc/self/status to the root shell instead of the caller's pipe or terminal.
if [ "$SECCOMP_STATE" = 0 ]; then
  [ "$SHELL_MODE" -eq 0 ] || exec /system/bin/.yhroot_ap_sh "$@"
  exec -a su "$REAL_APD" "$@"
fi
[ -f "$IPC/ready" ] || { echo 'APatch root worker is unavailable' >&2; exit 1; }
[ "$(/system/bin/id -u)" = 0 ] || exit 1
IFS= read -r WORKER < "$IPC/worker.pid" || exit 1
kill -0 "$WORKER" 2>/dev/null || exit 1
umask 077
SESSION=$(/system/bin/mktemp -d "$IPC/session.XXXXXXXX") || exit 1
IN_PID= OUT_PID= ERR_PID=
JOB_COMPLETED=0
cleanup() {
  if [ "$JOB_COMPLETED" -ne 1 ] && [ -f "$SESSION/handler" ]; then
    IFS= read -r JOB < "$SESSION/handler"
    case "$JOB" in ''|*[!0-9]*) ;; *) kill -TERM -- "-$JOB" 2>/dev/null;; esac
  fi
  [ -z "$IN_PID" ] || kill "$IN_PID" 2>/dev/null
  [ -z "$OUT_PID" ] || kill "$OUT_PID" 2>/dev/null
  [ -z "$ERR_PID" ] || kill "$ERR_PID" 2>/dev/null
  /system/bin/rm -rf "$SESSION"
}
trap 'cleanup' EXIT
trap 'exit 130' HUP INT TERM
/system/bin/mkfifo -m 600 "$SESSION/status" "$SESSION/stdin" "$SESSION/stdout" "$SESSION/stderr" || exit 1
echo $$ > "$SESSION/client"
[ "$SHELL_MODE" -eq 0 ] || : > "$SESSION/shell"
export -p > "$SESSION/env"
: > "$SESSION/args"
[ $# -eq 0 ] || /system/bin/printf '%s\0' "$@" > "$SESSION/args"
exec 3<&0 5<>"$SESSION/status" 6<>"$IPC/requests"
INPUT_FD=$(/system/bin/readlink "/proc/$$/fd/3")
OUTPUT_FD=$(/system/bin/readlink "/proc/$$/fd/1")
ERROR_FD=$(/system/bin/readlink "/proc/$$/fd/2")
DIRECT_IO=0
if [ -t 0 ] && [ -t 1 ] && [ -t 2 ]; then
  DIRECT_IO=1
else
  case "$INPUT_FD" in pipe:\[*\])
    case "$OUTPUT_FD" in pipe:\[*\])
      case "$ERROR_FD" in pipe:\[*\]) DIRECT_IO=1;; esac
      ;;
    esac
    ;;
  esac
fi
if [ "$DIRECT_IO" -eq 1 ]; then
  # libsu sends commands and completion markers through the same input pipe.
  # An eager cat would consume the markers even when the command reads no
  # input, leaving the manager waiting forever. Reopen pipes and terminals
  # directly so only the requested command reads them. ADB sockets need FIFOs.
  : > "$SESSION/direct-io"
else
  /system/bin/cat <&3 >"$SESSION/stdin" &
  IN_PID=$!
  /system/bin/cat "$SESSION/stdout" &
  OUT_PID=$!
  /system/bin/cat "$SESSION/stderr" >&2 &
  ERR_PID=$!
fi
/system/bin/printf '%s\n' "$SESSION" >&6 || exit 1
IFS= read -r -t 5 ACK <&5 || exit 1
[ "$ACK" = ready ] || exit 1
IFS= read -r RC <&5 || exit 1
case "$RC" in ''|*[!0-9]*) exit 1;; esac
JOB_COMPLETED=1
[ -z "$OUT_PID" ] || wait "$OUT_PID"
[ -z "$ERR_PID" ] || wait "$ERR_PID"
exit "$RC"
APD_WRAPPER
  [ "$?" -eq 0 ] || return 1
  chmod 755 "$APD" 2>/dev/null || return 1
  restorecon "$APD" 2>/dev/null
  [ -x "$APD" ]
}

stage_su_worker() {
  rm -f "$APATCH_SU_WORKER" 2>/dev/null
  cat > "$APATCH_SU_WORKER" <<'SU_WORKER'
#!/system/bin/sh
IPC=/dev/.yhroot_ap_ipc
SELF=/data/adb/ap/.yhroot_su_worker.sh
if [ "$1" = su-entry ]; then
  export PATH=/system/bin:/system/xbin:/system_ext/bin:/product/bin:/vendor/bin
  ENTRY="$2"
  toybox nsenter -t 1 -m -- /system/bin/sh -c \
    '[ -x /system/bin/su ] && [ -x /system/bin/.yhroot_ap_sh ]' 2>/dev/null && exit 0

  mkdir -p "$ENTRY" || exit 1
  chmod 751 "$ENTRY" || exit 1
  chown 0:2000 "$ENTRY" || exit 1
  chcon u:object_r:system_file:s0 "$ENTRY" || exit 1
  cp /system/bin/sh "$ENTRY/.yhroot_ap_sh" || exit 1
  cp /data/adb/ap/.yhroot_shell_bridge.sh "$ENTRY/sh" || exit 1
  chmod 755 "$ENTRY/.yhroot_ap_sh" "$ENTRY/sh" || exit 1
  chown 0:2000 "$ENTRY/.yhroot_ap_sh" "$ENTRY/sh" || exit 1
  # Android uses shell_exec for adbd -> shell and app shell transitions.
  # A system_file label would leave ADB commands in the adbd domain.
  chcon u:object_r:shell_exec:s0 "$ENTRY/.yhroot_ap_sh" "$ENTRY/sh" || exit 1
  ln -sf /system/bin/sh "$ENTRY/su" || exit 1
  chcon -h u:object_r:system_file:s0 "$ENTRY/su" || exit 1

  # Use a tmpfs lower layer; /data is unsupported on some kernels.
  # Init's namespace makes the entry available to the restarted framework.
  # Both the overlay and backing directory disappear on a full reboot.
  toybox nsenter -t 1 -m -- /system/bin/sh -c '
    mount -t overlay overlay -o "ro,lowerdir=$1:/system/bin" /system/bin || exit
    [ -x /system/bin/su ]
  ' sh "$ENTRY"
  exit $?
fi
if [ "$1" = start ]; then
  export PATH=/system/bin:/system/xbin:/system_ext/bin:/product/bin:/vendor/bin
  IPC=/dev/.yhroot_ap_ipc
  umask 077
  mkdir -p "$IPC" || exit 1
  chmod 700 "$IPC" || exit 1
  if [ -f "$IPC/worker.pid" ]; then
    IFS= read -r OLD_WORKER < "$IPC/worker.pid"
    case "$OLD_WORKER" in ''|*[!0-9]*) ;; *) kill "$OLD_WORKER" 2>/dev/null;; esac
  fi
  rm -f "$IPC/ready" "$IPC/requests" "$IPC/started" || exit 1
  mkfifo -m 600 "$IPC/requests" "$IPC/started" || exit 1
  exec 7<>"$IPC/started" || exit 1
  setsid /system/bin/sh "$SELF" </dev/null >"$IPC/worker.log" 2>&1 &
  IFS= read -r -t 5 WORKER_PID <&7 || exit 1
  kill -0 "$WORKER_PID" 2>/dev/null || exit 1
  exit $?
fi
if [ "$1" = session ]; then
  SESSION="$2"
  exec 5<>"$SESSION/status" || exit 1
  # The live client owns cleanup. Only reclaim its files here when it was
  # killed without running its trap; two simultaneous removals race on toybox.
  trap 'RC=$?; echo "$RC" >&5; kill -0 "$CLIENT" 2>/dev/null || /system/bin/rm -rf "$SESSION" 2>/dev/null' EXIT
  trap 'trap - EXIT; exit 130' INT
  trap 'trap - EXIT; exit 143' HUP TERM
  echo $$ > "$SESSION/handler"
  echo ready >&5
  IFS= read -r CLIENT < "$SESSION/client" || exit 1
  case "$CLIENT" in ''|*[!0-9]*) exit 1;; esac
  [ -d "/proc/$CLIENT" ] || exit 1
  exec 4<&-
  if [ -f "$SESSION/direct-io" ]; then
    exec 0<"/proc/$CLIENT/fd/3" 1>"/proc/$CLIENT/fd/1" 2>"/proc/$CLIENT/fd/2" || exit 1
  else
    exec 0<"$SESSION/stdin" 1>"$SESSION/stdout" 2>"$SESSION/stderr" || exit 1
  fi
  cd "/proc/$CLIENT/cwd" || exit 1
  set --
  while IFS= read -r -d '' ARG; do set -- "$@" "$ARG"; done < "$SESSION/args"
  if [ -f "$SESSION/shell" ]; then
    /system/bin/toybox nsenter -t "$CLIENT" -m -- /system/bin/sh -c '
      . "$1"
      shift
      exec /system/bin/.yhroot_ap_sh "$@"
    ' sh "$SESSION/env" "$@"
  else
  /system/bin/toybox nsenter -t "$CLIENT" -m -- /system/bin/sh -c '
    . "$1"
    shift
    exec -a su /data/adb/ap/.yhroot_apd "$@"
  ' su "$SESSION/env" "$@"
  fi
  exit $?
fi
if [ "$1" = boot-completed ]; then
  # soft-reboot is detached. Wait for a new, fully booted framework before
  # running the remaining official module stage and package UID listener.
  IFS= read -r OLD_ZYGOTE < "$IPC/zygote.pid" || exit 1
  ATTEMPT=0
  while [ "$ATTEMPT" -lt 180 ]; do
    NEW_ZYGOTE=$(pidof zygote64 2>/dev/null)
    if [ -n "$NEW_ZYGOTE" ] && [ "$NEW_ZYGOTE" != "$OLD_ZYGOTE" ] &&
       [ "$(getprop sys.boot_completed)" = 1 ]; then
      /data/adb/apd --superkey su boot-completed || exit $?
      /system/bin/su -c id || exit $?
      [ -f /data/adb/ap/jailbreak ] || exit 1
      echo '[+] APatch runtime and boot-completed stage ready'
      exit 0
    fi
    sleep 1
    ATTEMPT=$((ATTEMPT + 1))
  done
  echo '[!] APatch framework startup did not finish within 180 seconds'
  exit 1
fi
umask 077
[ "$(id -u)" = 0 ] || exit 1
grep -q 'Seccomp:[[:space:]]*0' /proc/self/status || exit 1
exec 4<>"$IPC/requests" || exit 1
echo $$ > "$IPC/worker.pid"
pidof zygote64 > "$IPC/zygote.pid"
echo ready > "$IPC/ready"
echo $$ > "$IPC/started"
while IFS= read -r SESSION <&4; do
  if [ "$SESSION" = boot-completed ]; then
    /system/bin/setsid /system/bin/sh "$SELF" boot-completed < /dev/null > /data/adb/ap/.yhroot_activation.log 2>&1 &
    continue
  fi
  case "$SESSION" in "$IPC"/session.*) ;; *) continue;; esac
  NAME=${SESSION#"$IPC/"}
  case "$NAME" in */*) continue;; esac
  [ -d "$SESSION" ] && [ -p "$SESSION/status" ] || continue
  /system/bin/setsid /system/bin/sh "$SELF" session "$SESSION" < /dev/null > /dev/null 2>>"$IPC/worker.log" &
done
SU_WORKER
  [ "$?" -eq 0 ] || return 1
  chmod 700 "$APATCH_SU_WORKER" 2>/dev/null || return 1
  cat > "$APATCH_SHELL_BRIDGE" <<'SHELL_BRIDGE'
#!/system/bin/.yhroot_ap_sh
# APatch's manager starts a direct shell through truncate rather than apd.
# Only an already authorized root process with an app seccomp filter uses
# the clean worker. System processes and unprivileged apps keep native sh.
ROOT_UID= SECCOMP_STATE=
while read -r FIELD VALUE REST; do
  case "$FIELD" in
    Uid:) ROOT_UID="$VALUE";;
    Seccomp:) SECCOMP_STATE="$VALUE";;
  esac
done < /proc/self/status
if [ "$ROOT_UID" = 0 ] && [ "$SECCOMP_STATE" = 2 ]; then
  exec /system/bin/.yhroot_ap_sh /data/adb/apd --yh-shell "$@"
fi
exec /system/bin/.yhroot_ap_sh "$@"
SHELL_BRIDGE
  [ "$?" -eq 0 ] || return 1
  chmod 755 "$APATCH_SHELL_BRIDGE" 2>/dev/null || return 1
  cat > "$APATCH_ROOT/.yhroot_bash_env" <<'BASH_ENV_SCRIPT'
# Only root Bash sessions use this compatibility entry. Keep any existing
# caller startup file, then use the real APatch su command with intact argv.
if [ -n "${YH_AP_BASH_ENV_ORIGINAL:-}" ] &&
   [ "$YH_AP_BASH_ENV_ORIGINAL" != /data/adb/ap/.yhroot_bash_env ] &&
   [ -f "$YH_AP_BASH_ENV_ORIGINAL" ]; then
  . "$YH_AP_BASH_ENV_ORIGINAL"
fi
su() { /system/bin/su "$@"; }
export -f su
BASH_ENV_SCRIPT
  [ "$?" -eq 0 ] || return 1
  chmod 600 "$APATCH_ROOT/.yhroot_bash_env" 2>/dev/null || return 1
  restorecon "$APATCH_SU_WORKER" "$APATCH_SHELL_BRIDGE" "$APATCH_ROOT/.yhroot_bash_env" 2>/dev/null
  return 0
}

start_su_worker() {
  # Start from APatch's domain. Keep deferred heredocs out of the exploit
  # shell: it loses permission to create their temporary files after policy load.
  run_kpatch_fd su su -Z u:r:magisk:s0 \
    -c "/system/bin/toybox nsenter -t 1 -m -- /system/bin/sh '$APATCH_SU_WORKER' start"
}

stage_apatch_tooling() {
  TOOL_NAME="$1"
  SOURCE_NAME="lib${TOOL_NAME}.so"
  TOOL_DEST="$APATCH_BIN_ROOT/$TOOL_NAME"
  TOOL_TEMP=
  SOURCE_PATH=${APD_SOURCE%/*}/$SOURCE_NAME

  if [ ! -s "$SOURCE_PATH" ]; then
    TOOL_TEMP="$APATCH_ROOT/.yhroot_${TOOL_NAME}"
    rm -f "$TOOL_TEMP" 2>/dev/null
    EXTRACTED=0
    for ENTRY in \
      "lib/arm64-v8a/$SOURCE_NAME" \
      "lib/arm64/$SOURCE_NAME" \
      "lib/aarch64/$SOURCE_NAME"; do
      extract_apk_entry "$ENTRY" "$TOOL_TEMP"
      if [ -s "$TOOL_TEMP" ]; then
        SOURCE_PATH="$TOOL_TEMP"
        EXTRACTED=1
        break
      fi
      rm -f "$TOOL_TEMP" 2>/dev/null
    done
    [ "$EXTRACTED" -eq 1 ] || return 1
  fi

  rm -f "$TOOL_DEST" 2>/dev/null
  cp -f "$SOURCE_PATH" "$TOOL_DEST" 2>/dev/null || return 1
  [ -z "$TOOL_TEMP" ] || rm -f "$TOOL_TEMP" 2>/dev/null
  chmod 755 "$TOOL_DEST" 2>/dev/null || return 1
  restorecon "$TOOL_DEST" 2>/dev/null
  [ -x "$TOOL_DEST" ]
}

stage_apatch_service() {
  SERVICE_ENTRY="$1"
  SERVICE_DEST="$2"
  SERVICE_TEMP="$APATCH_ROOT/.yhroot_service"
  rm -f "$SERVICE_TEMP" "$SERVICE_DEST" 2>/dev/null
  extract_apk_entry "$SERVICE_ENTRY" "$SERVICE_TEMP" || return 1
  [ -s "$SERVICE_TEMP" ] || return 1
  cp -f "$SERVICE_TEMP" "$SERVICE_DEST" 2>/dev/null || return 1
  rm -f "$SERVICE_TEMP" 2>/dev/null
  chmod 755 "$SERVICE_DEST" 2>/dev/null || return 1
  restorecon "$SERVICE_DEST" 2>/dev/null
  [ -x "$SERVICE_DEST" ]
}

stage_apatch_runtime() {
  mkdir -p "$APATCH_BIN_ROOT" "$APATCH_LOG_ROOT" 2>/dev/null || return 1

  # APD dispatches its behavior from argv[0]; these are the paths used by the
  # normal APatch installation and by post-fs-data/module scripts.
  rm -f "$APATCH_BIN_ROOT/apd" "$APATCH_BIN_ROOT/magiskpolicy" \
    "$APATCH_BIN_ROOT/resetprop" "$APATCH_BIN_ROOT/su" 2>/dev/null
  ln -s "$APD" "$APATCH_BIN_ROOT/apd" 2>/dev/null || return 1
  # Module scripts already run as root and include this directory in PATH.
  # APD's su dispatcher preserves their normal su command-line arguments.
  ln -s "$APD" "$APATCH_BIN_ROOT/su" 2>/dev/null || return 1
  # APatch's normal installation copies the standalone policy helper. Use the
  # APD dispatcher only on builds that do not ship libmagiskpolicy.so.
  if ! stage_apatch_tooling magiskpolicy; then
    ln -s "$APD" "$APATCH_BIN_ROOT/magiskpolicy" 2>/dev/null || return 1
  fi
  # APatch's normal install places the standalone libresetprop.so here. APD's
  # resetprop dispatcher is only a fallback; using it as the primary helper
  # leaves post-fs-data property restoration incomplete on FolkPatch.
  if ! stage_apatch_tooling resetprop; then
    ln -s "$APD" "$APATCH_BIN_ROOT/resetprop" 2>/dev/null || return 1
  fi
  # Keep existing manager authorization unchanged. APatch's PkgConfig and APD
  # use the same CSV header; initialize only a missing or zero-byte snapshot.
  touch "$APATCH_PACKAGE_CONFIG" "$APATCH_SU_PATH" "$APATCH_SUCOMPAT" 2>/dev/null || return 1
  if [ ! -s "$APATCH_PACKAGE_CONFIG" ]; then
    printf '%s\n' 'pkg,exclude,allow,uid,to_uid,sctx' > "$APATCH_PACKAGE_CONFIG" 2>/dev/null || return 1
  fi
  # Always rewrite these values. A previous interrupted activation can leave
  # a zero-filled file of the correct length, which APD treats as an invalid
  # su path and then disables the normal shell entry point.
  printf '%s\n' '/system/bin/su' > "$APATCH_SU_PATH" 2>/dev/null || return 1

  # The helpers are optional for late-load itself but required by the module
  # lifecycle on APatch/FolkPatch builds.
  stage_apatch_tooling busybox || return 1
  stage_apatch_tooling kptools || return 1
  # The official APatch application keeps libkpatch.so available for the
  # native resetSuPath() call that switches the KernelPatch entry path.
  stage_apatch_tooling kpatch || return 1

  # FolkPatch ships this additional service; official APatch does not. Its
  # absence must not make APatch's standard runtime initialization fail.
  stage_apatch_service 'assets/Service/fpd' "$APATCH_FPD" || :

  restorecon -R "$APATCH_ROOT" "$APD" 2>/dev/null
  [ -x "$APATCH_BIN_ROOT/apd" ]
}

run_apd() {
  PATH="/system/bin:/system/xbin:/system_ext/bin:/product/bin:/vendor/bin:/vendor/xbin:$APATCH_BIN_ROOT" \
    "$APD" "$@"
}

open_apd_fd() {
  # APD restores SELinux enforcing during late-load. Keep an open executable
  # descriptor so the following soft-reboot call does not depend on a path
  # that the pre-transition shell domain can no longer traverse.
  eval "exec 9<\"$APD_REAL\"" 2>/dev/null || return 1
  APD_FD=9
  return 0
}

open_kpatch_fd() {
  # APD applies its live SELinux policy during late-load. Keep the official
  # userspace KernelPatch client open before that transition so the native
  # su-path reset remains callable from the existing root shell.
  eval "exec 8<\"$APATCH_KPATCH\"" 2>/dev/null || return 1
  KPATCH_FD=8
  return 0
}

run_apd_fd() {
  [ -n "$APD_FD" ] || return 1
  # Pass PATH on the exec itself. APD's soft-reboot child resolves `stop` and
  # `start` after the live SELinux policy is installed; relying only on the
  # parent shell environment is unreliable on some Android shells.
  PATH="/system/bin:/system/xbin:/system_ext/bin:/product/bin:/vendor/bin:/vendor/xbin:$APATCH_BIN_ROOT" \
    "/proc/self/fd/$APD_FD" "$@"
}

run_kpatch_fd() {
  [ -n "$KPATCH_FD" ] || return 1
  "/proc/self/fd/$KPATCH_FD" "$@"
}

ensure_su_entry() {
  # Mount from APatch's domain; overlayfs stores the mounting credentials.
  # The helper was staged before late-load, so no temporary heredoc is needed.
  run_kpatch_fd su su -Z u:r:magisk:s0 \
    -c "/system/bin/sh '$APATCH_SU_WORKER' su-entry '$APATCH_SU_ENTRY'"
}

if [ "$(id -u 2>/dev/null)" != 0 ]; then
  fail 'root is required'
fi

if ! discover_apatch; then
  fail 'APatch/FolkPatch APK, libapd.so, or its package name was not found'
fi

KERNEL_RELEASE=$(uname -r 2>/dev/null)
KERNEL_VERSION=$(printf '%s\n' "$KERNEL_RELEASE" | cut -d- -f1 | cut -d. -f1-2)
ANDROID_VERSION=$(printf '%s\n' "$KERNEL_RELEASE" | grep -o 'android[0-9]*' | head -n 1)
if [ -z "$KERNEL_VERSION" ] || [ -z "$ANDROID_VERSION" ]; then
  fail "cannot determine KMI from kernel release: $KERNEL_RELEASE"
fi
KMI="${ANDROID_VERSION}-${KERNEL_VERSION}"
ASSET_PATH="assets/${KMI}_kernelpatch.ko"

mkdir -p "$TMP_ROOT" 2>/dev/null || fail 'cannot access /data/local/tmp'
APD="$PERSIST_ROOT/apd"
stage_apd || fail "APD is not executable: $APD"
stage_apatch_runtime || fail 'cannot initialize APatch runtime'
stage_su_worker || fail 'cannot prepare APatch root worker'

# This is the environment used by the normal APatch service scripts. In
# particular, post-fs-data scripts invoke resetprop and fpd by basename.
export BUSYBOX="$APATCH_BIN_ROOT/busybox"
export PATH="$PATH:/system_ext/bin:/vendor/bin:$APATCH_BIN_ROOT"

# Always extract a fresh asset. A previous interrupted lifecycle can leave a
# file with a non-zero length but an all-zero body at the conventional path.
rm -f "$AP_MODULE" "$AP_MODULE_TMP" 2>/dev/null
extract_apk_entry "$ASSET_PATH" "$AP_MODULE_TMP"
if [ ! -s "$AP_MODULE_TMP" ]; then
  rm -f "$AP_MODULE_TMP" 2>/dev/null
  fail "matching APatch asset not found: $ASSET_PATH"
fi
MODULE_MAGIC=$(od -An -tx1 -N4 "$AP_MODULE_TMP" 2>/dev/null | tr -d ' \n\r')
[ "$MODULE_MAGIC" = '7f454c46' ] || {
  rm -f "$AP_MODULE_TMP" 2>/dev/null
  fail "matching APatch asset is not a valid ELF module: $ASSET_PATH"
}
cp -f "$AP_MODULE_TMP" "$AP_MODULE" 2>/dev/null || \
  fail "cannot stage KernelPatch module: $AP_MODULE"
chmod 600 "$AP_MODULE" 2>/dev/null || fail 'cannot set KernelPatch module permissions'
restorecon "$AP_MODULE" 2>/dev/null

echo "[*] Kernel KMI: $KMI"
echo "[*] APatch package: $AP_PACKAGE"

# Remove a marker left by an earlier interrupted attempt. APD creates the
# marker as part of its normal late-load/soft-reboot lifecycle.
rm -f "$APATCH_ROOT/jailbreak" 2>/dev/null || fail 'cannot reset stale jailbreak marker'

# APD post-fs-data uses the conventional persistent module path when the
# soft-reboot event is emulated after late-load.
cp -f "$AP_MODULE" "$APATCH_ROOT/kernelpatch.ko" 2>/dev/null || \
  fail 'cannot restore APatch KernelPatch module path'
chmod 600 "$APATCH_ROOT/kernelpatch.ko" 2>/dev/null || \
  fail 'cannot set APatch KernelPatch module permissions'
restorecon "$APATCH_ROOT/kernelpatch.ko" 2>/dev/null

# Keep APD executable across the SELinux transition. The descriptor is used
# only for the official soft-reboot command after late-load.
open_apd_fd || fail 'cannot keep APD executable across the SELinux transition'
open_kpatch_fd || fail 'cannot keep KernelPatch userspace tool executable across the SELinux transition'

echo '[*] Clearing /data/local/tmp before KernelPatch activation'
cleanup_tmp || fail 'cannot clear /data/local/tmp before KernelPatch activation'
echo '[*] /data/local/tmp cleared'

echo "[*] Completing APD late-load for $ASSET_PATH"

# Do not use Magica's ADB-property bridge here. The privilege payload already
# gives APD a root session, while production builds commonly reject the
# temporary service.adb.root/service.adb.tcp.port writes.
APD_OUTPUT=$(run_apd late-load --module "$AP_MODULE" \
  --package-name "$AP_PACKAGE" 2>&1)
APD_RC=$?
if [ -n "$APD_OUTPUT" ]; then
  # printf resolves to /system/bin/printf on this device and can become
  # inaccessible immediately after APD applies its live SELinux policy.
  echo "$APD_OUTPUT"
fi
if [ "$APD_RC" -ne 0 ]; then
  fail "APD late-load failed with exit code $APD_RC" "$APD_RC"
fi

# APD reports completion after loading KernelPatch, applying the live policy,
# and writing the jailbreak marker. The marker itself may be unreadable from
# this shell after the policy transition, so validate APD's completion output.
case "$APD_OUTPUT" in
  *'late-load complete:'*) ;;
  *) fail 'APD returned success without confirming late-load completion' ;;
esac
if [ ! -e /sys/module/kernelpatch ]; then
  fail 'APD returned success but KernelPatch is not loaded'
fi
IFS= read -r ENFORCE_STATE < /sys/fs/selinux/enforce 2>/dev/null
if [ "$ENFORCE_STATE" != 1 ]; then
  fail "APD returned success but SELinux is not enforcing (state=$ENFORCE_STATE)"
fi

# Match APatch's installation-time resetSuPath() sequence.  KernelPatch starts
# with /system/bin/kp; APatch changes it to /system/bin/su through the native
# sumgr interface.  Merely writing /data/adb/ap/su_path does not update the
# live kernel hook, which leaves both adb and file managers reporting
# "su: inaccessible or not found" after late-load.
if [ -n "$KPATCH_FD" ]; then
  KPATCH_RESET_OUTPUT=$(run_kpatch_fd su sumgr reset /system/bin/kp 2>&1)
  KPATCH_RESET_RC=$?
  [ -z "$KPATCH_RESET_OUTPUT" ] || echo "$KPATCH_RESET_OUTPUT"
  if [ "$KPATCH_RESET_RC" -ne 0 ]; then
    fail "KernelPatch su path reset to /system/bin/kp failed with exit code $KPATCH_RESET_RC" "$KPATCH_RESET_RC"
  fi
  KPATCH_RESET_OUTPUT=$(run_kpatch_fd su sumgr reset /system/bin/su 2>&1)
  KPATCH_RESET_RC=$?
  [ -z "$KPATCH_RESET_OUTPUT" ] || echo "$KPATCH_RESET_OUTPUT"
  if [ "$KPATCH_RESET_RC" -ne 0 ]; then
    fail "KernelPatch su path reset to /system/bin/su failed with exit code $KPATCH_RESET_RC" "$KPATCH_RESET_RC"
  fi
else
  fail 'KernelPatch userspace tool is unavailable; cannot reset the su entry path'
fi

ensure_su_entry || fail 'cannot prepare the ordinary APatch su entry'
start_su_worker || fail 'cannot start the clean APatch root worker'

# A working execve redirect alone is insufficient: Android's shell and root
# applications first probe the su path. Some bundled KernelPatch LKMs compile
# out that probe hook for stability. Test the ordinary shell call from APatch's
# own privileged context, so this result is not affected by the exploit domain.
# Do not use `env su` for the probe: env bypasses the very path check that fails.
SU_CHECK_OUTPUT=$(run_kpatch_fd su su -Z u:r:magisk:s0 \
  -c '/system/bin/sh -c "/system/bin/su -c id"' 2>&1)
SU_CHECK_RC=$?
if [ "$SU_CHECK_RC" -ne 0 ]; then
  [ -z "$SU_CHECK_OUTPUT" ] || echo "$SU_CHECK_OUTPUT"
  fail 'KernelPatch loaded, but ordinary /system/bin/su is unavailable; jailbreak activation is incomplete'
fi
case "$SU_CHECK_OUTPUT" in
  *'uid=0('*|*'uid=0 '*) ;;
  *)
    [ -z "$SU_CHECK_OUTPUT" ] || echo "$SU_CHECK_OUTPUT"
    fail 'ordinary APatch su did not return uid=0; jailbreak activation is incomplete'
    ;;
esac

# The official APatch application calls resetSuPath() during its process
# startup. APD normally restarts that process through ActivityManager; a
# payload-root shell can lack the activity Binder service, so give the
# manager one explicit restart attempt from APD's privileged SELinux domain.
# This is best-effort: the soft-reboot worker remains the authoritative
# lifecycle path on devices where ActivityManager is unavailable.
if [ -n "$AP_PACKAGE" ] && [ -x /system/bin/runcon ] && [ -x /system/bin/am ]; then
  for MANAGER_CONTEXT in u:r:magisk:s0 u:r:su:s0; do
    MANAGER_OUTPUT=$(/system/bin/runcon "$MANAGER_CONTEXT" /system/bin/am force-stop "$AP_PACKAGE" 2>&1)
    MANAGER_RC=$?
    [ -z "$MANAGER_OUTPUT" ] || echo "$MANAGER_OUTPUT"
    if [ "$MANAGER_RC" -eq 0 ]; then
      MANAGER_OUTPUT=$(/system/bin/runcon "$MANAGER_CONTEXT" /system/bin/am start -n "$AP_PACKAGE/me.bmax.apatch.ui.MainActivity" 2>&1)
      MANAGER_RC=$?
      [ -z "$MANAGER_OUTPUT" ] || echo "$MANAGER_OUTPUT"
      [ "$MANAGER_RC" -eq 0 ] && break
    fi
  done
fi

echo '[*] Running APD soft-reboot to finish jailbreak activation'
# The temporary root shell is already uid 0, but it is not automatically in
# APD's privileged profile. Supplying the built-in `su` superkey is the same
# authorization used by the successful official root-shell flow and is needed
# for APD to execute the framework stop/start pair.
SOFT_REBOOT_LOG="$APATCH_LOG_ROOT/yhroot_soft_reboot.log"
rm -f "$SOFT_REBOOT_LOG" 2>/dev/null
(
  run_apd_fd --superkey su soft-reboot
  SOFT_REBOOT_RC=$?
  echo "[soft-reboot] exit=$SOFT_REBOOT_RC"
  exit "$SOFT_REBOOT_RC"
) >"$SOFT_REBOOT_LOG" 2>&1 &
SOFT_REBOOT_PID=$!
# Do not wait on APD's stdout pipe: its stop/start worker can keep that pipe
# open while the framework restarts, which used to strand the activation shell
# before it could queue the post-reboot stage. A short probe still catches an
# immediate APD rejection; an alive process means the request was accepted.
/system/bin/sleep 1
if /system/bin/kill -0 "$SOFT_REBOOT_PID" 2>/dev/null; then
  SOFT_REBOOT_RC=0
else
  wait "$SOFT_REBOOT_PID"
  SOFT_REBOOT_RC=$?
fi
[ -f "$SOFT_REBOOT_LOG" ] && /system/bin/cat "$SOFT_REBOOT_LOG"
if [ "$SOFT_REBOOT_RC" -ne 0 ]; then
  fail "APD soft-reboot failed with exit code $SOFT_REBOOT_RC" "$SOFT_REBOOT_RC"
fi

# APD's soft-reboot command daemonizes before it stops and starts Android. Its
# return only confirms that the official worker was accepted; the worker then
# runs post-fs-data and services, where APD applies su_path and sucompat. The
# ordinary su entry was checked in APatch's privileged context before starting
# the worker. /data/local/tmp was cleared before late-load and remains empty.
run_kpatch_fd su su -Z u:r:magisk:s0 \
  -c 'echo boot-completed > /dev/.yhroot_ap_ipc/requests' || \
  fail 'cannot schedule the APatch boot-completed stage'
echo '[+] APD soft-reboot started; /data/local/tmp cleared'
exit 0
