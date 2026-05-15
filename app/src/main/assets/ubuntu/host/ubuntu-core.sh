#!/system/bin/sh

ROOTFS=/data/local/ubuntu-22.04
BUSYBOX=/data/adb/magisk/busybox

write_resolv_conf() {
  mkdir -p "$ROOTFS/etc"
  rm -f "$ROOTFS/etc/resolv.conf"

  DNS1="$(getprop net.dns1)"
  DNS2="$(getprop net.dns2)"
  DNS3="$(getprop net.dns3)"
  DNS4="$(getprop net.dns4)"
  {
    [ -n "$DNS1" ] && echo "nameserver $DNS1"
    [ -n "$DNS2" ] && echo "nameserver $DNS2"
    [ -n "$DNS3" ] && echo "nameserver $DNS3"
    [ -n "$DNS4" ] && echo "nameserver $DNS4"
  } > "$ROOTFS/etc/resolv.conf"

  if [ ! -s "$ROOTFS/etc/resolv.conf" ]; then
    {
      echo "nameserver 1.1.1.1"
      echo "nameserver 8.8.8.8"
    } > "$ROOTFS/etc/resolv.conf"
  fi
}

ensure_mounts() {
  mkdir -p "$ROOTFS/proc" "$ROOTFS/sys" "$ROOTFS/dev" "$ROOTFS/dev/pts" \
    "$ROOTFS/mnt/shared" "$ROOTFS/run" "$ROOTFS/tmp"

  if ! grep -qs " $ROOTFS/proc " /proc/mounts; then
    /system/bin/mount -t proc proc "$ROOTFS/proc"
  fi

  if ! grep -qs " $ROOTFS/sys " /proc/mounts; then
    /system/bin/mount -t sysfs sysfs "$ROOTFS/sys"
  fi

  if ! grep -qs " $ROOTFS/dev " /proc/mounts; then
    /system/bin/mount -o bind /dev "$ROOTFS/dev"
  fi

  if ! grep -qs " $ROOTFS/dev/pts " /proc/mounts; then
    /system/bin/mount -t devpts devpts "$ROOTFS/dev/pts"
  fi

  if ! grep -qs " $ROOTFS/mnt/shared " /proc/mounts; then
    /system/bin/mount -o bind /storage/emulated/0 "$ROOTFS/mnt/shared"
  fi

  chmod 1777 "$ROOTFS/tmp"
  write_resolv_conf
}

run_in_ubuntu() {
  if [ "$#" -gt 0 ]; then
    exec "$BUSYBOX" chroot "$ROOTFS" /usr/bin/env -i \
      HOME=/root \
      LANG=zh_CN.UTF-8 \
      LC_ALL=zh_CN.UTF-8 \
      TERM="${TERM:-xterm-256color}" \
      PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
      /bin/bash -lc "$*"
  fi

  exec "$BUSYBOX" chroot "$ROOTFS" /usr/bin/env -i \
    HOME=/root \
    LANG=zh_CN.UTF-8 \
    LC_ALL=zh_CN.UTF-8 \
    TERM="${TERM:-xterm-256color}" \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    /bin/bash --login
}

stop_mounts() {
  for mp in \
    "$ROOTFS/mnt/shared" \
    "$ROOTFS/dev/pts" \
    "$ROOTFS/dev" \
    "$ROOTFS/sys" \
    "$ROOTFS/proc"
  do
    if grep -qs " $mp " /proc/mounts; then
      /system/bin/umount "$mp" 2>/dev/null || /system/bin/umount -l "$mp"
    fi
  done
}
