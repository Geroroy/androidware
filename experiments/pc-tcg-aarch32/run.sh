#!/usr/bin/env bash
# PC-side experiment (x86_64 Linux host, QEMU TCG): boot an AArch64 Linux guest and run a 32-bit ARM
# (AArch32) static binary inside it, proving that a 64-bit guest OS under QEMU TCG executes 32-bit
# user space regardless of whether the HOST CPU implements AArch32. Also measures TCG overhead.
#
# Needs: qemu-system-aarch64, qemu-user (qemu-arm/qemu-aarch64), gcc, gcc-arm-linux-gnueabihf,
#        gcc-aarch64-linux-gnu, cpio, curl. Downloads an Ubuntu arm64 kernel (GPL-2.0) from ports.ubuntu.com.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; W="${WORK:-$HERE/work}"; mkdir -p "$W"; cd "$W"
KVER="${KVER:-6.8.0-71}"; KDEB="linux-image-${KVER}-generic_${KVER}.$(echo $KVER | cut -d- -f2)_arm64.deb"
[ -f "$KDEB" ] || curl -sS -o "$KDEB" "https://ports.ubuntu.com/ubuntu-ports/pool/main/l/linux-signed/$KDEB"
[ -d kdeb ] || { mkdir kdeb && dpkg-deb -x "$KDEB" kdeb; }
KERNEL=$(ls kdeb/boot/vmlinuz-*)
gcc -O2 -o bench-x86_64 "$HERE/bench.c" -lm
arm-linux-gnueabihf-gcc -O2 -static -march=armv7-a -mfpu=neon -mfloat-abi=hard -o bench-arm32 "$HERE/bench.c" -lm
aarch64-linux-gnu-gcc -O2 -static -o bench-arm64 "$HERE/bench.c" -lm
aarch64-linux-gnu-gcc -O2 -static -o init "$HERE/init.c"
rm -rf initrd && mkdir -p initrd/proc && cp init initrd/init && cp bench-arm32 initrd/bench-arm32-glibc && cp bench-arm64 initrd/bench-arm64-glibc
(cd initrd && find . | cpio -o -H newc 2>/dev/null | gzip -1 > ../initrd.img)
echo "### host native x86_64";        ./bench-x86_64
echo "### qemu-arm user-mode (arm32)"; qemu-arm ./bench-arm32
echo "### qemu-aarch64 user-mode";     qemu-aarch64 ./bench-arm64
for cpu in ${CPUS:-cortex-a57 cortex-a76 max}; do
  echo "### qemu-system-aarch64 -cpu $cpu (TCG), guest kernel $KVER"
  timeout 900 qemu-system-aarch64 -M virt -cpu "$cpu" -smp 1 -m 512 -nographic -kernel "$KERNEL" -initrd initrd.img \
     -append "console=ttyAMA0 rdinit=/init loglevel=3" -no-reboot 2>&1 | grep -E "^===|int_ms|exit status|Illegal|Kernel panic"
done
