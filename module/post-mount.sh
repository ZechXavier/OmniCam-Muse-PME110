#!/system/bin/sh
set -eu
MODDIR=${0%/*}

# A/B test switch (v4.4.4-pme110.14): if the file skip_post_mount exists in the
# module directory, skip the libAlgoInterface.so bind-mount entirely. This tests
# whether the patched native library itself causes the POP HAL crash.
# Usage: su -c 'touch /data/adb/modules/ricoh_pop_pme110/skip_post_mount' && reboot
# To re-enable: su -c 'rm /data/adb/modules/ricoh_pop_pme110/skip_post_mount' && reboot
[ -f "$MODDIR/skip_post_mount" ] && exit 0

bind_library() {
    NAME=$1
    SOURCE="$MODDIR/system/odm/lib64/$NAME"
    TARGET="/odm/lib64/$NAME"
    test -f "$SOURCE"
    test -f "$TARGET"
    chown root:root "$SOURCE"
    chmod 0644 "$SOURCE"
    chcon u:object_r:same_process_hal_file:s0 "$SOURCE"
    mount --bind "$SOURCE" "$TARGET"
}

# Hybrid Mount creates the two new backend paths before this phase.
bind_library libBasicTonePhotoX9.so
bind_library libBasicTonePhotoX10.so
bind_library libBasicTonePhoto.so
bind_library libAlgoInterface.so
