# PC experiment: AArch32 user space inside an AArch64 QEMU/TCG guest

Date: 2026-11-05. Host: x86_64 Linux container, Intel Xeon @ 2.10GHz (4 vCPU), QEMU 8.2.2 (Ubuntu 24.04 package).
Guest kernel: Ubuntu `linux-image-6.8.0-71-generic` arm64 (GPL-2.0, downloaded from ports.ubuntu.com), initramfs = static `init` + two static benchmarks.
Benchmark (`bench.c`): integer xorshift/mix loop, 4x4 float matrix*vector loop, 1 MiB memcpy loop. Times in ms (lower is better). Checksums identical in every run.

| run | arch | int_ms | fp_ms | mem_ms | note |
|---|---|---|---|---|---|
| native | x86_64 | 107 | 114 | 10 | reference |
| qemu-arm (linux-user) | arm32 | 145 | 1095 | 28 | TCG user mode, x86_64 host backend |
| qemu-aarch64 (linux-user) | arm64 | 156 | 878 | 13 | |
| system, `-cpu cortex-a57` | arm64 guest process | 199 | 1346 | 145 | softmmu |
| system, `-cpu cortex-a57` | **arm32 guest process** | **130** | **1086** | **108** | AArch32 EL0 via kernel COMPAT, exit 0 |
| system, `-cpu cortex-a76` | arm64 / arm32 | 204 / 195 | 1347 / 1547 | 95 / 99 | both exit 0 |
| system, `-cpu max` | arm64 / arm32 | 292 / 144 | 1336 / 1339 | 129 / 180 | both exit 0 |
| system, `-cpu neoverse-n1` | arm64 / arm32 | 182 / 153 | 1110 / 1150 | 138 / 94 | both exit 0 |

Kernel boot to `/init`: about 1.4 s guest time (`[ 1.365182] Run /init as init process`), 5-12 s wall including QEMU start, for every CPU model.

## What this shows

1. **A 64-bit Linux guest under QEMU TCG runs 32-bit ARM user-space binaries** through the kernel's AArch32 compat layer, for the `cortex-a57`, `cortex-a76`, `neoverse-n1` and `max` CPU models. The host CPU is x86_64 and has no ARM support at all, so the guest's AArch32 capability comes entirely from QEMU's CPU model. This is the mechanism approach A relies on for 32-bit (armeabi-v7a) game libraries on a 64-bit-only phone.
2. **TCG cost profile**: integer code is close to native speed (1.2-1.9x), floating point is roughly **10x slower** (QEMU emulates FP/NEON with softfloat), and memory-bandwidth code is 3-15x slower (softmmu adds TLB/page-walk emulation). 3D game engines are dominated by FP and memory traffic, so these are the numbers that matter for N.O.V.A. 3.

## What this does NOT show

- Nothing here ran on an Android device or on an aarch64 host backend. Ratios on the Galaxy S25 Ultra (TCG aarch64 backend, Oryon cores) are unmeasured.
- No Android guest was booted; only a Linux kernel + static binaries. Android userspace (SurfaceFlinger, ART, binder) was not exercised.
- No GPU path was exercised.

Reproduce: `./run.sh` (set `CPUS="cortex-a57"` to run a single model).
