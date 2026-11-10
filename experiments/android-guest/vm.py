#!/usr/bin/env python3
"""Tiny harness: run QEMU with serial+monitor on unix sockets, drive the guest console, dump the framebuffer."""
import socket, subprocess, time, os, sys, threading, re
class VM:
    def __init__(s, args, workdir, log='serial.log'):
        s.wd=workdir; s.ser_path=os.path.join(workdir,'ser.sock'); s.mon_path=os.path.join(workdir,'mon.sock')
        for p in (s.ser_path,s.mon_path):
            if os.path.exists(p): os.unlink(p)
        s.qmp_path=os.path.join(workdir,'qmp.sock')
        if os.path.exists(s.qmp_path): os.unlink(s.qmp_path)
        cmd=args+['-serial',f'unix:{s.ser_path},server=on,wait=off','-monitor',f'unix:{s.mon_path},server=on,wait=off','-qmp',f'unix:{s.qmp_path},server=on,wait=off','-display','none']
        s.p=subprocess.Popen(cmd,cwd=workdir,stdout=open(os.path.join(workdir,'qemu.out'),'w'),stderr=subprocess.STDOUT)
        for _ in range(100):
            if os.path.exists(s.ser_path) and os.path.exists(s.mon_path): break
            time.sleep(0.1)
        s.ser=socket.socket(socket.AF_UNIX); s.ser.connect(s.ser_path)
        s.mon=socket.socket(socket.AF_UNIX); s.mon.connect(s.mon_path); s.mon.settimeout(2)
        s.buf=b''; s.lock=threading.Lock(); s.logf=open(os.path.join(workdir,log),'wb'); s.t0=time.time()
        threading.Thread(target=s._rd,daemon=True).start()
        s.hmp('')
        import json
        s.qmp=socket.socket(socket.AF_UNIX); s.qmp.connect(s.qmp_path); s.qf=s.qmp.makefile('rw')
        s.qf.readline(); s.qcmd('qmp_capabilities')
    def qcmd(s, cmd, **args):
        import json
        s.qf.write(json.dumps({'execute':cmd,'arguments':args})+'\n'); s.qf.flush()
        while True:
            r=json.loads(s.qf.readline())
            if 'return' in r or 'error' in r: return r
    def tap(s, x, y, w=480, h=800, hold=0.15):
        X=int(x*32767/(w-1)); Y=int(y*32767/(h-1))
        ev=lambda *e: s.qcmd('input-send-event', events=list(e))
        ev({'type':'abs','data':{'axis':'x','value':X}},{'type':'abs','data':{'axis':'y','value':Y}})
        ev({'type':'btn','data':{'button':'left','down':True}}); time.sleep(hold)
        ev({'type':'btn','data':{'button':'left','down':False}})
    def key(s, qcode, hold=0.1):
        s.qcmd('input-send-event', events=[{'type':'key','data':{'key':{'type':'qcode','data':qcode},'down':True}}]); time.sleep(hold)
        s.qcmd('input-send-event', events=[{'type':'key','data':{'key':{'type':'qcode','data':qcode},'down':False}}])
    def shot(s, png):
        ppm=png+'.ppm'
        try: r=s.qcmd('screendump', filename=ppm)
        except Exception as e:
            try: rc=s.p.wait(5)
            except Exception: rc='still running'
            raise RuntimeError(f'QEMU went away during screendump (exit status {rc}; 0 = guest reset/poweroff with -no-reboot, negative = signal)') from e
        from PIL import Image
        Image.open(ppm).save(png); os.unlink(ppm); return png
    def _rd(s):
        while True:
            try: d=s.ser.recv(65536)
            except OSError: break
            if not d: break
            with s.lock: s.buf+=d
            s.logf.write(d); s.logf.flush()
    def wait_for(s, pat, timeout=600, since=0):
        end=time.time()+timeout; r=re.compile(pat.encode() if isinstance(pat,str) else pat)
        while time.time()<end:
            with s.lock: m=r.search(s.buf,since)
            if m: return m
            if s.p.poll() is not None: return None
            time.sleep(0.5)
        return None
    def sh(s, cmd, timeout=60):
        """run a command on the guest console, return its output"""
        tag=f'@@{int(time.time()*1000)%100000}@@'
        with s.lock: start=len(s.buf)
        s.ser.sendall((cmd+f'; echo {tag}$?\n').encode())
        m=s.wait_for(re.escape(tag)+r'(\d+)', timeout, start+len(cmd))
        with s.lock: out=s.buf[start:m.start() if m else len(s.buf)]
        return out.decode('utf-8','replace')
    def hmp(s, cmd):
        if cmd: s.mon.sendall((cmd+'\n').encode())
        out=b''; time.sleep(0.3)
        try:
            while True:
                d=s.mon.recv(65536)
                if not d: break
                out+=d
                if out.endswith(b'(qemu) '): break
        except socket.timeout: pass
        return out.decode('utf-8','replace')
    def fbshot(s, png, addr=0x7f800000, w=480, h=800, stride=960):
        raw=os.path.join(s.wd,'fb.raw'); 
        if os.path.exists(raw): os.unlink(raw)
        s.hmp(f'pmemsave {addr} {stride*h} "{raw}"')
        for _ in range(50):
            if os.path.exists(raw) and os.path.getsize(raw)==stride*h: break
            time.sleep(0.1)
        from PIL import Image
        im=Image.frombytes('RGB',(w,h),open(raw,'rb').read(),'raw','BGR;16',stride)
        im.save(png); return png
    def stop(s):
        try: s.hmp('quit')
        except Exception: pass
        try: s.p.wait(10)
        except Exception: s.p.kill()
