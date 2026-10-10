#!/system/bin/sh

SCRIPT_PATH="$0"
TMP_ROOT=/data/local/tmp
SELINUX_ENFORCE_PATH=/sys/fs/selinux/enforce
APD=
APK=
AP_PACKAGE=
AP_MODULE="$TMP_ROOT/yhroot_ap_kernelpatch.ko"

discover_apatch() {
  AP_PACKAGE=me.bmax.apatch
  APK=$(/system/bin/pm path "$AP_PACKAGE" 2>/dev/null | sed -n 's/^package://p' | grep '/base.apk$' | head -n 1)
  [ -f "$APK" ] || return 1

  APP_DIR=${APK%/base.apk}
  APD=$(find "$APP_DIR" -name libapd.so 2>/dev/null | head -n 1)
  [ -n "$APD" ] && [ -f "$APD" ]
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
  fail 'APatch package APK or libapd.so was not found'
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

echo "[*] APatch package: $AP_PACKAGE"
echo "[*] Kernel KMI: $KMI"
echo "[*] Loading matching KernelPatch asset"
"$APD" late-load --module "$AP_MODULE" --package-name "$AP_PACKAGE"
APD_RC=$?
if [ "$APD_RC" -ne 0 ]; then
  fail "APD late-load failed with exit code $APD_RC" "$APD_RC"
fi

if [ ! -e /sys/module/kernelpatch ]; then
  fail 'APD returned success but KernelPatch is not loaded'
fi

cleanup_tmp
echo '[+] APatch jailbreak activation complete; /data/local/tmp cleared'
