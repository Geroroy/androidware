#!/usr/bin/env python3
"""Performance harness for the Android 7 guest on a PC (engine work for docs/optimization.md).

usage: bench.py <tag>
Boots the guest in Host-GPU mode against engine/emugl/build-linux/aw-gl-renderer (-snapshot: the images are
not modified), waits for sys.boot_completed and for the auto-installed gles2test app, then runs in the guest:
  bench     experiments/pc-tcg-aarch32/bench.c (armv7 bionic static): int / fp / mem milliseconds
  appstart  cold `am start -W` of Settings and Calculator, 3 times each: (TotalTime ms, QEMU CPU seconds)
  pingpong  perf/ctxbench.c: two processes ping-pong over pipes: (wall s, QEMU CPU s, microseconds per round trip)
  fork      150 x /system/bin/true from the shell: (wall s, QEMU CPU s)
Prepare once (guest must not be running):
  debugfs -w -R "write app/src/main/assets/selftest/bench-arm32-bionic aw-bench32" dist/android-guest/userdata.img
  $NDK/.../armv7a-linux-androideabi28-clang -O2 -static -o ctxbench32 experiments/android-guest/perf/ctxbench.c
  debugfs -w -R "write ctxbench32 aw-ctx32" dist/android-guest/userdata.img
env: QEMU (qemu-system-aarch64 built for the PC with engine/qemu/patches), GUEST (image dir, default dist/android-guest),
     OUT (run dir, default /tmp/aw-perf/<tag>; keep it short, it holds unix sockets), SMP (2), CPU, ACCEL, W, H,
     RAMDISK, XFER, APPEND, EXTRA (more qemu args), PERF=1 (+PERF_BIN: perf record of the engine during boot and app),
     SKIPAPP=1, NOMICRO=1, SETTLE (s), CMDS ("cmd;;cmd" extra guest shell commands), HOLD (s: keep running; while
     holding, write "hmp <cmd>" / "sh <cmd>" / "quit" to <OUT>/cmd, the result appears in <OUT>/cmd.out).
The engine reads AW_TLB_CTX from the environment: 0 = no saved TLB contexts, verify = self-check (report in <OUT>/qemu.out).
"""
import sys, os, time, subprocess, re, shlex
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
sys.path.insert(0, os.path.join(ROOT, 'experiments', 'android-guest'))
from vm import VM
tag = sys.argv[1]
wd = os.path.abspath(os.environ.get('GUEST', os.path.join(ROOT, 'dist', 'android-guest')))
out = os.environ.get('OUT', os.path.join('/tmp/aw-perf', tag)); os.makedirs(out, exist_ok=True)
qemu = os.environ.get('QEMU', 'qemu-system-aarch64')
sock = os.path.join(out, 'gl.sock')
W, H = os.environ.get('W', '480'), os.environ.get('H', '800')
rend = subprocess.Popen([os.path.join(ROOT, 'engine', 'emugl', 'build-linux', 'aw-gl-renderer'), '--socket', sock, '--width', W, '--height', H],
                        stdout=open(os.path.join(out, 'renderer.log'), 'w'), stderr=subprocess.STDOUT)
for _ in range(50):
    if os.path.exists(sock): break
    time.sleep(0.1)
ramdisk = os.environ.get('RAMDISK', 'ramdisk-aw.img'); xfer = os.environ.get('XFER', 'transfer-gl.img')
args = [qemu, '-M', 'virt,highmem=off,goldfish=on', '-cpu', os.environ.get('CPU', 'cortex-a15'), '-smp', os.environ.get('SMP', '2'),
        '-m', '1024', '-accel', os.environ.get('ACCEL', 'tcg,thread=multi'), '-no-reboot', '-snapshot',
        '-kernel', f'{wd}/kernel-ranchu', '-initrd', f'{wd}/{ramdisk}',
        '-append', 'console=ttyAMA0 androidboot.console=ttyAMA0 androidboot.hardware=ranchu qemu=1 qemu.gles=1 androidboot.selinux=permissive ' + os.environ.get('APPEND', ''),
        '-drive', f'if=none,id=xfer,file={wd}/{xfer},format=raw,readonly=on', '-device', 'virtio-blk-device,drive=xfer',
        '-drive', f'if=none,id=sd,file={wd}/sdcard.img,format=raw', '-device', 'virtio-blk-device,drive=sd',
        '-chardev', f'file,id=aud,path={out}/audio.raw', '-global', 'goldfish-audio.chardev=aud',
        '-global', 'goldfish-pipe.socket=' + sock,
        '-drive', f'if=none,id=data,file={wd}/userdata.img,format=raw', '-device', 'virtio-blk-device,drive=data',
        '-drive', f'if=none,id=cache,file={wd}/cache.img,format=raw', '-device', 'virtio-blk-device,drive=cache',
        '-drive', f'if=none,id=system,file={wd}/system.img,format=raw,readonly=on', '-device', 'virtio-blk-device,drive=system',
        '-global', 'goldfish-fb.width=' + W, '-global', 'goldfish-fb.height=' + H, '-global', 'goldfish-fb.dpi=240',
        '-nic', 'none'] + shlex.split(os.environ.get('EXTRA', ''))
vm = VM(args, out, 'serial.log')
t0 = time.time()
def perf(name, secs):
    if not os.environ.get('PERF'): time.sleep(secs); return
    subprocess.run([os.environ.get('PERF_BIN', 'perf'), 'record', '-e', 'cpu-clock', '-F', '499', '-g', '-p', str(vm.p.pid), '-o', f'{out}/perf-{name}.data', '--', 'sleep', str(secs)],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
res = {}
try:
    m = vm.wait_for(r'generic:/ \$', 300); res['prompt'] = time.time() - t0
    vm.sh('su 0 dmesg -n 1', 20)
    if os.environ.get('PERF'): perf('boot', 20)
    while time.time() - t0 < 900:
        o = vm.sh('getprop sys.boot_completed', 20).strip().splitlines()
        if o[-1:] == ['1']: break
        time.sleep(2)
    res['boot'] = time.time() - t0
    # wait for the auto-installed test app
    while time.time() - t0 < 600 and not os.environ.get('SKIPAPP'):
        if 'fps=' in vm.sh('su 0 logcat -d -s gles2test:* | tail -2', 60): break
        time.sleep(3)
    res['app_start'] = time.time() - t0
    time.sleep(int(os.environ.get('SETTLE', '30')))
    open(f'{out}/ready', 'w').close()
    def cpu_s():
        f = open(f'/proc/{vm.p.pid}/stat').read().rsplit(')', 1)[1].split(); return (int(f[11]) + int(f[12])) / os.sysconf('SC_CLK_TCK')
    c0, w0 = cpu_s(), time.time()
    if os.environ.get('SKIPAPP'): time.sleep(20)
    else: perf('app', 20)
    res['app_cpu_per_s'] = (cpu_s() - c0) / (time.time() - w0)
    o = vm.sh('su 0 logcat -d -s gles2test:* | tail -12', 60)
    fps = [float(x) for x in re.findall(r'fps[= ]+([0-9.]+)', o)]
    res['fps'] = fps[-8:]
    open(f'{out}/logcat-app.txt', 'w').write(o)
    cpu = vm.sh('su 0 top -n 1 -d 3 -m 12 -t', 60); open(f'{out}/top.txt', 'w').write(cpu)
    if not os.environ.get('NOMICRO'):
        vm.sh('su 0 am force-stop dev.androidware.gles2test; su 0 sh -c "cp /data/aw-bench32 /data/local/tmp/b; chmod 755 /data/local/tmp/b"', 60); time.sleep(5)
        b = [vm.sh('/data/local/tmp/b 1', 300) for _ in range(2)]
        mm = [re.search(r'int_ms=(\d+) fp_ms=(\d+) mem_ms=(\d+)', x) for x in b]
        res['bench'] = [tuple(int(v) for v in m.groups()) for m in mm if m]
        vm.sh('su 0 sh -c "cp /data/aw-ctx32 /data/local/tmp/c; chmod 755 /data/local/tmp/c"', 60)
        pp = '/data/local/tmp/c 5000'
        fk = 'i=0; while [ $i -lt 150 ]; do /system/bin/true; i=$((i+1)); done'
        st = []
        for comp in ('com.android.settings/.Settings', 'com.android.calculator2/.Calculator') * 3:
            vm.sh('su 0 am force-stop ' + comp.split('/')[0], 60); time.sleep(2)
            c1 = cpu_s(); o = vm.sh('am start -W -n ' + comp, 300); m = re.search(r'TotalTime: (\d+)', o)
            st.append((int(m.group(1)) if m else -1, round(cpu_s() - c1, 2)))
            vm.sh('input keyevent 3', 60); time.sleep(2)
        res['appstart_ms_cpu'] = st
        for name, cmd in (('pingpong', pp), ('fork', fk)):
            ts = []
            for _ in range(2):
                t1 = time.time(); c1 = cpu_s(); o = vm.sh('su 0 sh -c ' + shlex.quote(cmd), 600); m = re.search(r'us_per_roundtrip=([0-9.]+)', o); ts.append((round(time.time() - t1, 2), round(cpu_s() - c1, 2)) + ((float(m.group(1)),) if m else ()))
            res[name] = ts
    for c in filter(None, os.environ.get('CMDS', '').split(';;')):
        print('$', c); print(vm.sh(c, 300))
    h = float(os.environ.get('HOLD', '0')); tend = time.time() + h
    while time.time() < tend:   # command channel: write 'hmp <cmd>' or 'sh <cmd>' to <out>/cmd, result appears in <out>/cmd.out
        cf = f'{out}/cmd'
        if os.path.exists(cf):
            c = open(cf).read().strip(); os.unlink(cf)
            if c == 'quit': break
            r = vm.hmp(c[4:]) if c.startswith('hmp ') else vm.sh(c[3:], 300)
            open(f'{out}/cmd.out.tmp', 'w').write(r); os.rename(f'{out}/cmd.out.tmp', f'{out}/cmd.out')
        time.sleep(0.2)
finally:
    print(tag, ' '.join(f'{k}={v:.1f}' if isinstance(v, float) else f'{k}={v}' for k, v in res.items()), flush=True)
    vm.stop(); rend.terminate()
