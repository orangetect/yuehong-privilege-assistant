#!/system/bin/sh

SCRIPT_PATH="$0"
DATA_APP_ROOT=/data/app
TMP_ROOT=/data/local/tmp
PERSIST_ROOT=/data/adb
APATCH_ROOT="$PERSIST_ROOT/ap"
APATCH_BIN_ROOT="$APATCH_ROOT/bin"
APATCH_LOG_ROOT="$APATCH_ROOT/log"
APATCH_PACKAGE_CONFIG="$APATCH_ROOT/package_config"
APATCH_SU_PATH="$APATCH_ROOT/su_path"
JAILBREAK_MARKER="$PERSIST_ROOT/ap/jailbreak"
SELINUX_ENFORCE_PATH=/sys/fs/selinux/enforce
APD=
APD_SOURCE=
APK=
AP_PACKAGE=
AP_MODULE="$TMP_ROOT/yhroot_ap_kernelpatch.ko"

discover_apatch() {
  APD_SOURCE=$(find "$DATA_APP_ROOT" -name libapd.so 2>/dev/null | head -n 1)
  [ -n "$APD_SOURCE" ] || return 1

  APP_DIR=${APD_SOURCE%/lib/*/libapd.so}
  APK_PATH="$APP_DIR/base.apk"
  APK="$APK_PATH"
  INSTALL_DIR=${APP_DIR##*/}
  AP_PACKAGE=${INSTALL_DIR%%-*}
}

cleanup_tmp() {
  CLEANUP_TMP_ROOT="$TMP_ROOT"
  (
    trap '' HUP
    cd /
    rm -f "$AP_MODULE"
    rm -rf \
      "$CLEANUP_TMP_ROOT"/* \
      "$CLEANUP_TMP_ROOT"/.[!.]* \
      "$CLEANUP_TMP_ROOT"/..?*
  ) </dev/null >/dev/null 2>&1
}

stage_apd() {
  mkdir -p "$PERSIST_ROOT" 2>/dev/null || return 1
  rm -f "$APD" 2>/dev/null
  cp "$APD_SOURCE" "$APD" 2>/dev/null || return 1
  chmod 755 "$APD" 2>/dev/null
  restorecon "$APD" 2>/dev/null
  [ -x "$APD" ]
}

extract_apk_entry() {
  ENTRY="$1"
  DESTINATION="$2"
  if command -v unzip >/dev/null 2>&1; then
    unzip -p "$APK" "$ENTRY" > "$DESTINATION" 2>/dev/null
    return $?
  fi
  if command -v toybox >/dev/null 2>&1; then
    toybox unzip -p "$APK" "$ENTRY" > "$DESTINATION" 2>/dev/null
    return $?
  fi
  return 1
}

stage_apatch_tooling() {
  TOOL_NAME="$1"
  TOOL_DEST="$APATCH_BIN_ROOT/$TOOL_NAME"
  SOURCE_NAME="lib${TOOL_NAME}.so"
  SOURCE_PATH=${APD_SOURCE%/*}/$SOURCE_NAME

  if [ -s "$SOURCE_PATH" ]; then
    rm -f "$TOOL_DEST" 2>/dev/null
    cp -f "$SOURCE_PATH" "$TOOL_DEST" 2>/dev/null || return 1
  else
    TOOL_TEMP="$APATCH_ROOT/.yhroot_${TOOL_NAME}"
    rm -f "$TOOL_TEMP" 2>/dev/null
    EXTRACTED=0
    for ENTRY in \
      "lib/arm64-v8a/$SOURCE_NAME" \
      "lib/arm64/$SOURCE_NAME" \
      "lib/aarch64/$SOURCE_NAME"; do
      extract_apk_entry "$ENTRY" "$TOOL_TEMP"
      if [ -s "$TOOL_TEMP" ]; then
        EXTRACTED=1
        break
      fi
      rm -f "$TOOL_TEMP" 2>/dev/null
    done
    if [ "$EXTRACTED" -eq 1 ]; then
      rm -f "$TOOL_DEST" 2>/dev/null
      mv "$TOOL_TEMP" "$TOOL_DEST" 2>/dev/null || return 1
    else
      rm -f "$TOOL_TEMP" 2>/dev/null
      return 1
    fi
  fi

  chmod 755 "$TOOL_DEST" 2>/dev/null || return 1
  restorecon "$TOOL_DEST" 2>/dev/null
  [ -x "$TOOL_DEST" ]
}

stage_apatch_runtime() {
  mkdir -p "$APATCH_BIN_ROOT" "$APATCH_LOG_ROOT" 2>/dev/null || return 1

  # APD is kept at the official persistent path. Do not replace an existing
  # executable after late-load because the manager may already have it open.
  if [ ! -x "$APD" ]; then
    cp -f "$APD_SOURCE" "$APD" 2>/dev/null || return 1
    chmod 755 "$APD" 2>/dev/null || return 1
  fi
  rm -f "$APATCH_BIN_ROOT/apd" 2>/dev/null
  ln -s "$APD" "$APATCH_BIN_ROOT/apd" 2>/dev/null || return 1

  # APatch reads this file during post-fs-data and uses it to reset the
  # KernelPatch su interception path. Keep the legacy /system/bin/su path so
  # normal adb shells and file managers can invoke su after /data/local/tmp is
  # cleared.
  touch "$APATCH_PACKAGE_CONFIG" "$APATCH_SU_PATH" 2>/dev/null || return 1
  printf '%s\n' '/system/bin/su' > "$APATCH_SU_PATH" 2>/dev/null || return 1

  # These are the same persistent helper locations used by APatch's manager.
  # Missing optional helpers are handled by the caller so su-path setup can
  # still complete on split APK layouts.
  rm -f "$APATCH_BIN_ROOT/magiskpolicy" "$APATCH_BIN_ROOT/resetprop" 2>/dev/null
  ln -s "$APD" "$APATCH_BIN_ROOT/magiskpolicy" 2>/dev/null || return 1
  ln -s "$APD" "$APATCH_BIN_ROOT/resetprop" 2>/dev/null || return 1
  stage_apatch_tooling busybox || true
  stage_apatch_tooling kptools || true

  restorecon "$APD" "$APATCH_ROOT" "$APATCH_BIN_ROOT" \
    "$APATCH_BIN_ROOT/apd" "$APATCH_BIN_ROOT/magiskpolicy" \
    "$APATCH_BIN_ROOT/resetprop" "$APATCH_PACKAGE_CONFIG" \
    "$APATCH_SU_PATH" 2>/dev/null
  [ -x "$APD" ] && [ -x "$APATCH_BIN_ROOT/apd" ]
}

run_apd() {
  if [ -x /system/bin/runcon ]; then
    /system/bin/runcon u:r:shell:s0 "$APD" "$@"
  else
    "$APD" "$@"
  fi
}

run_apd_root() {
  "$APD" "$@"
}

fail() {
  echo "[!] $1"
  rm -f "$AP_MODULE" 2>/dev/null
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

KERNEL_RELEASE=$(uname -r)
KERNEL_VERSION=$(printf '%s\n' "$KERNEL_RELEASE" | cut -d- -f1 | cut -d. -f1-2)
ANDROID_VERSION=$(printf '%s\n' "$KERNEL_RELEASE" | grep -o 'android[0-9]*' | head -1)
if [ -z "$KERNEL_VERSION" ] || [ -z "$ANDROID_VERSION" ]; then
  fail "cannot determine KMI from kernel release: $KERNEL_RELEASE"
fi
KMI="${ANDROID_VERSION}-${KERNEL_VERSION}"
ASSET_PATH="assets/${KMI}_kernelpatch.ko"

mkdir -p "$TMP_ROOT" 2>/dev/null
APD="$PERSIST_ROOT/apd"
stage_apd || fail "APD is not executable: $APD"
stage_apatch_runtime || fail 'cannot initialize APatch su runtime'

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
echo "[*] APatch package: $AP_PACKAGE"
echo "[*] Loading matching KernelPatch asset"
echo "[*] Running APD late-load from the existing root session"
run_apd late-load --module "$AP_MODULE" --package-name "$AP_PACKAGE"
APD_RC=$?
if [ "$APD_RC" -ne 0 ]; then
  fail "APD late-load failed with exit code $APD_RC" "$APD_RC"
fi

if [ ! -e /sys/module/kernelpatch ]; then
  fail 'APD returned success but KernelPatch is not loaded'
fi

stage_apatch_runtime || fail 'cannot restore APatch su runtime after late-load'
echo '[*] Initializing APatch su path through post-fs-data'
run_apd_root --superkey su post-fs-data
POST_FS_RC=$?
if [ "$POST_FS_RC" -ne 0 ]; then
  fail "APD post-fs-data failed with exit code $POST_FS_RC" "$POST_FS_RC"
fi

if [ ! -e "$JAILBREAK_MARKER" ]; then
  fail "APD returned success but jailbreak marker is missing: $JAILBREAK_MARKER"
fi

cleanup_tmp
echo '[+] APatch jailbreak activation complete; /data/local/tmp cleared'
