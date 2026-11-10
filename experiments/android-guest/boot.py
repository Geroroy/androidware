#!/usr/bin/env python3
"""Boot the Android 7.0 armv7 emulator image on QEMU 'virt,goldfish=on' (engine patch 0024) on a PC,
wait for sys.boot_completed, take screenshots and exercise touch/keys over QMP.

usage: boot.py <guest dir from scripts/fetch-android-guest.sh> <tag>
env:   QEMU=path/to/qemu-system-aarch64 built with patch 0024, SMP=4, HOLD=1 (keep running),
       APK=file.apk PKG=package/.Activity  (APK is written into /data before boot, then installed and started)
The same command line is what the app's QemuLauncher builds for GuestProfile.KIND_ANDROID_RANCHU.
"""
import sys, time, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from vm import VM
wd, tag = os.path.abspath(sys.argv[1]), sys.argv[2]
qemu = os.environ.get('QEMU', 'qemu-system-aarch64')
# FULL=1: what the app does since 0.0.7 -- overlay ramdisk (auto-install), transfer disk (vde), SD card (vdd), audio
full = []
if os.environ.get('FULL'):
    os.environ.setdefault('RAMDISK', 'ramdisk-aw.img')
    full = ['-drive', f'if=none,id=xfer,file={wd}/transfer.img,format=raw,readonly=on', '-device', 'virtio-blk-device,drive=xfer',
            '-drive', f'if=none,id=sd,file={wd}/sdcard.img,format=raw', '-device', 'virtio-blk-device,drive=sd',
            '-chardev', f'file,id=aud,path={wd}/audio.raw', '-global', 'goldfish-audio.chardev=aud']
args = [qemu, '-M', 'virt,highmem=off,goldfish=on', '-cpu', 'cortex-a15', '-smp', os.environ.get('SMP', '4'),
        '-m', '1024', '-accel', 'tcg,thread=multi', '-no-reboot',
        '-kernel', f'{wd}/kernel-ranchu', '-initrd', f"{wd}/{os.environ.get('RAMDISK', 'ramdisk.img')}",
        '-append', 'console=ttyAMA0 androidboot.console=ttyAMA0 androidboot.hardware=ranchu qemu=1 qemu.gles=0 '
                   'androidboot.selinux=permissive ' + os.environ.get('EXTRA_APPEND', ''),
        *full,
        '-drive', f'if=none,id=data,file={wd}/userdata.img,format=raw', '-device', 'virtio-blk-device,drive=data',
        '-drive', f'if=none,id=cache,file={wd}/cache.img,format=raw', '-device', 'virtio-blk-device,drive=cache',
        '-drive', f'if=none,id=system,file={wd}/system.img,format=raw,readonly=on', '-device', 'virtio-blk-device,drive=system',
        '-global', 'goldfish-fb.width=480', '-global', 'goldfish-fb.height=800', '-global', 'goldfish-fb.dpi=240',
        '-nic', 'none']
apk = os.environ.get('APK')
if apk:  # no adb (guest network is off): put the APK straight into the userdata ext4 image
    import subprocess
    subprocess.run(['debugfs', '-w', '-f', '-', f'{wd}/userdata.img'], input=f'rm androidware-test.apk\nwrite {apk} androidware-test.apk\n',
                   text=True, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
vm = VM(args, wd, f'serial-{tag}.log')
t0 = time.time()
vm.wait_for(r'generic:/ \$', 300); print('console prompt at %.1fs' % (time.time() - t0)); vm.sh('su 0 dmesg -n 1', 20)
while vm.sh('getprop sys.boot_completed', 20).strip().splitlines()[-1:] != ['1'] and time.time() - t0 < 900:
    time.sleep(5)
print('sys.boot_completed=1 at %.1fs' % (time.time() - t0)); time.sleep(10)
print(vm.sh('getprop ro.product.cpu.abilist; getprop ro.build.version.release; wm size; wm density', 30))
vm.shot(f'{wd}/{tag}-1-home.png')
vm.sh('am start -a android.settings.SETTINGS', 30); time.sleep(15); vm.shot(f'{wd}/{tag}-2-settings.png')
vm.tap(240, 400); time.sleep(10); vm.shot(f'{wd}/{tag}-3-tap.png')
vm.key('esc'); time.sleep(8); vm.shot(f'{wd}/{tag}-4-back.png')
if os.environ.get('FULL'):
    time.sleep(60)
    print(vm.sh('su 0 dmesg | grep androidware:; ls /storage; ls -l /sdcard/; df | grep -E "storage|media"; ls -l /dev/eac', 60))
    vm.shot(f'{wd}/{tag}-5-autostart.png')
    vm.sh('am start -a android.intent.action.VIEW -d file:///system/media/audio/ringtones/Andromeda.ogg -t audio/ogg', 60)
    time.sleep(25); vm.shot(f'{wd}/{tag}-6-audio.png')
    print(vm.sh('su 0 logcat -d -s gles1test:* | tail -5; echo x > /sdcard/aw-test.txt; ls -l /sdcard/aw-test.txt; grep -i gold /proc/interrupts', 60))
elif apk:
    t1 = time.time()
    print(vm.sh('su 0 chmod 644 /data/androidware-test.apk; pm install -r /data/androidware-test.apk', 300).strip().splitlines()[-1:],
          'install %.1fs' % (time.time() - t1))
    vm.sh('su 0 logcat -c', 20)
    print(vm.sh('am start -W -n ' + os.environ['PKG'], 120))
    time.sleep(20); vm.shot(f'{wd}/{tag}-5-app.png')
    vm.tap(360, 600); time.sleep(8); vm.shot(f'{wd}/{tag}-6-app-touch.png')
    print(vm.sh('su 0 logcat -d -s gles1test:* DEBUG:* AndroidRuntime:* | tail -25', 60))
    print(vm.sh('su 0 dumpsys SurfaceFlinger | grep -E "^\\+ Layer|visibleRegion|activeBuffer|layerStack=" | head -40', 60))
    print(vm.sh('su 0 cat /proc/$(pidof dev.androidware.gles1test)/maps | grep -E "gles1test|GLES" | head -5; file /data/app/*/lib/arm/* 2>/dev/null', 30))
if os.environ.get('HOLD'):
    open(f'{wd}/hold', 'w').close()
    print('holding; delete', f'{wd}/hold', 'to stop')
    while os.path.exists(f'{wd}/hold'): time.sleep(2)
vm.stop()
