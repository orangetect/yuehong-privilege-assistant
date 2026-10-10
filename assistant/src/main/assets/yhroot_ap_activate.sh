#!/system/bin/sh

SCRIPT_PATH="$0"
DATA_APP_ROOT=/data/app
TMP_ROOT=/data/local/tmp
SELINUX_ENFORCE_PATH=/sys/fs/selinux/enforce
APD=
AP_PACKAGE=
AP_MODULE="$TMP_ROOT/yhroot_ap_kernelpatch.ko"

package_for_apk() {
  APK_PATH="$1"
  PACKAGE_LINE=$(cmd package list packages -f 2>/dev/null | grep -F "package:$APK_PATH=" | head -n 1)
  if [ -z "$PACKAGE_LINE" ]; then
    PACKAGE_LINE=$(pm list packages -f 2>/dev/null | grep -F "package:$APK_PATH=" | head -n 1)
  fi
  [ -n "$PACKAGE_LINE" ] && printf '%s\n' "${PACKAGE_LINE##*=}"
}

discover_apatch() {
  APD=$(find "$DATA_APP_ROOT" -name libapd.so 2>/dev/null | head -n 1)
  [ -n "$APD" ] || return 1

  APP_DIR=${APD%/lib/*/libapd.so}
  APK_PATH="$APP_DIR/base.apk"
  if [ -f "$APK_PATH" ]; then
    AP_PACKAGE=$(package_for_apk "$APK_PATH")
  fi
  if [ -z "$AP_PACKAGE" ]; then
    INSTALL_DIR=${APP_DIR##*/}
    AP_PACKAGE=${INSTALL_DIR%%-*}
  fi
}

force_stop_manager_if_activity_unavailable() {
  [ -n "$AP_PACKAGE" ] || return 0
  if command -v service >/dev/null 2>&1 && service check activity >/dev/null 2>&1; then
    return 0
  fi
  for PID in $(pidof "$AP_PACKAGE" 2>/dev/null); do
    case "$PID" in
      ''|1|$$) continue ;;
    esac
    kill -9 "$PID" 2>/dev/null
  done
}

cleanup_tmp() {
  CLEANUP_TMP_ROOT="$TMP_ROOT"
  (
    trap '' HUP
    cd /
    rm -rf \
      "$CLEANUP_TMP_ROOT"/* \
      "$CLEANUP_TMP_ROOT"/.[!.]* \
      "$CLEANUP_TMP_ROOT"/..?*
  ) </dev/null >/dev/null 2>&1
}

fail() {
  echo "[!] $1"
  if [ "$ORIGINAL_ENFORCE" = 1 ]; then
    echo 1 > "$SELINUX_ENFORCE_PATH" 2>/dev/null
  fi
  exit "${2:-1}"
}

ORIGINAL_ENFORCE=
if [ "$(id -u)" -ne 0 ]; then
  fail 'root is required'
fi

if ! discover_apatch; then
  fail 'APatch/FolkPatch APK or libapd.so was not found'
fi

chmod 755 "$APD" 2>/dev/null
if [ ! -x "$APD" ]; then
  fail "APD is not executable: $APD"
fi

KERNEL_RELEASE=$(uname -r)
KERNEL_VERSION=$(printf '%s\n' "$KERNEL_RELEASE" | cut -d- -f1 | cut -d. -f1-2)
ANDROID_VERSION=$(printf '%s\n' "$KERNEL_RELEASE" | grep -o 'android[0-9]*' | head -1)
if [ -z "$KERNEL_VERSION" ] || [ -z "$ANDROID_VERSION" ]; then
  fail "cannot determine KMI from kernel release: $KERNEL_RELEASE"
fi
KMI="${ANDROID_VERSION}-${KERNEL_VERSION}"
ASSET_PATH="assets/${KMI}_kernelpatch.ko"

mkdir -p "$TMP_ROOT" 2>/dev/null
if command -v unzip >/dev/null 2>&1; then
  unzip -p "$APK" "$ASSET_PATH" > "$AP_MODULE" 2>/dev/null
  EXTRACT_RC=$?
elif command -v toybox >/dev/null 2>&1; then
  toybox unzip -p "$APK" "$ASSET_PATH" > "$AP_MODULE" 2>/dev/null
  EXTRACT_RC=$?
else
  fail 'unzip is unavailable; cannot read the APatch APK asset'
fi

if [ "$EXTRACT_RC" -ne 0 ] || [ ! -s "$AP_MODULE" ]; then
  rm -f "$AP_MODULE"
  fail "matching APatch asset not found: $ASSET_PATH"
fi
chmod 600 "$AP_MODULE" 2>/dev/null

if [ ! -r "$SELINUX_ENFORCE_PATH" ] || [ ! -w "$SELINUX_ENFORCE_PATH" ]; then
  fail 'SELinux enforce control is unavailable'
fi
ORIGINAL_ENFORCE=$(cat "$SELINUX_ENFORCE_PATH" 2>/dev/null)
case "$ORIGINAL_ENFORCE" in
  0|1) ;;
  *) fail 'cannot read SELinux enforce state' ;;
esac
if [ "$ORIGINAL_ENFORCE" = 1 ]; then
  echo 0 > "$SELINUX_ENFORCE_PATH" 2>/dev/null || fail 'cannot temporarily set SELinux permissive'
fi

echo "[*] Kernel KMI: $KMI"
echo "[*] Loading matching KernelPatch asset"
if command -v service >/dev/null 2>&1 && service check activity >/dev/null 2>&1 && [ -n "$AP_PACKAGE" ]; then
  "$APD" late-load --module "$AP_MODULE" --package-name "$AP_PACKAGE"
else
  "$APD" late-load --module "$AP_MODULE"
fi
APD_RC=$?
if [ "$APD_RC" -ne 0 ]; then
  fail "APD late-load failed with exit code $APD_RC" "$APD_RC"
fi

if [ ! -e /sys/module/kernelpatch ]; then
  fail 'APD returned success but KernelPatch is not loaded'
fi

force_stop_manager_if_activity_unavailable
cleanup_tmp
echo '[+] APatch jailbreak activation complete; /data/local/tmp cleared'
