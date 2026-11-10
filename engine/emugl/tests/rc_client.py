#!/usr/bin/env python3
"""Minimal goldfish GL-pipe test client for aw-gl-renderer.

Speaks just enough of the emugl wire protocol (renderControl + a few GLES2
opcodes, no checksums) to prove the render server decodes and renders:

  1. rcGetRendererVersion / rcGetGLString round trips
  2. creates a GLES2 context + window surface + color buffer,
     clears it blue, with a red band at the TOP (GL y = h-100..h) and a
     green square at the bottom-left, then rcFlushWindowColorBuffer + rcFBPost
  3. repeats the post a few times

Usage: rc_client.py SOCKET WIDTH HEIGHT [FRAMES]
Opcodes: build-*/gen/{renderControl,gles2}_opcodes.h
"""
import socket
import struct
import sys

OP_rcGetRendererVersion = 10000
OP_rcGetEGLVersion = 10001
OP_rcGetGLString = 10003
OP_rcGetNumConfigs = 10004
OP_rcChooseConfig = 10006
OP_rcCreateContext = 10008
OP_rcDestroyContext = 10009
OP_rcCreateWindowSurface = 10010
OP_rcDestroyWindowSurface = 10011
OP_rcCreateColorBuffer = 10012
OP_rcCloseColorBuffer = 10014
OP_rcSetWindowColorBuffer = 10015
OP_rcFlushWindowColorBuffer = 10016
OP_rcMakeCurrent = 10017
OP_rcFBPost = 10018
OP_glClear = 2063
OP_glClearColor = 2064
OP_glDisable = 2086
OP_glEnable = 2090
OP_glGetError = 2108
OP_glScissor = 2144
OP_glViewport = 2189

GL_VENDOR, GL_RENDERER, GL_VERSION, GL_EXTENSIONS = 0x1F00, 0x1F01, 0x1F02, 0x1F03
GL_RGBA = 0x1908
GL_COLOR_BUFFER_BIT = 0x4000
GL_SCISSOR_TEST = 0x0C11


class Client:
    def __init__(self, path):
        self.s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.s.settimeout(20)
        self.s.connect(path)
        self.s.sendall(struct.pack('<I', 0))  # client flags

    def send(self, opcode, payload=b''):
        self.s.sendall(struct.pack('<II', opcode, 8 + len(payload)) + payload)

    def recv(self, n):
        buf = b''
        while len(buf) < n:
            chunk = self.s.recv(n - len(buf))
            if not chunk:
                raise RuntimeError('server closed the connection')
            buf += chunk
        return buf

    def call_i(self, opcode, *args):
        """call with u32 args, returns one int32"""
        self.send(opcode, struct.pack('<%dI' % len(args), *args))
        return struct.unpack('<i', self.recv(4))[0]

    def call_v(self, opcode, *args):
        self.send(opcode, struct.pack('<%dI' % len(args), *args))

    def gl_string(self, name):
        # First call with an empty buffer returns -needed_size.
        self.send(OP_rcGetGLString, struct.pack('<IIi', name, 0, 0))
        need = -struct.unpack('<i', self.recv(4))[0]
        if need <= 0:
            return ''
        self.send(OP_rcGetGLString, struct.pack('<IIi', name, need, need))
        data = self.recv(need + 4)
        return data[:need].split(b'\0')[0].decode('latin1')

    def choose_config(self, attribs):
        raw = struct.pack('<%di' % len(attribs), *attribs)
        payload = (struct.pack('<I', len(raw)) + raw +
                   struct.pack('<III', len(raw), 4, 1))
        self.send(OP_rcChooseConfig, payload)
        cfg, n = struct.unpack('<Ii', self.recv(8))
        return cfg, n


def main():
    path, w, h = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
    frames = int(sys.argv[4]) if len(sys.argv) > 4 else 3
    c = Client(path)

    print('rcGetRendererVersion ->', c.call_i(OP_rcGetRendererVersion))
    c.send(OP_rcGetEGLVersion, struct.pack('<II', 4, 4))
    major, minor, ok = struct.unpack('<iii', c.recv(12))
    print('rcGetEGLVersion -> %d.%d (ret %d)' % (major, minor, ok))
    c.send(OP_rcGetNumConfigs, struct.pack('<I', 4))
    nattr, ncfg = struct.unpack('<Ii', c.recv(8))
    print('rcGetNumConfigs -> %d configs, %d attribs each' % (ncfg, nattr))

    EGL_SURFACE_TYPE, EGL_PBUFFER_BIT = 0x3033, 1
    EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT = 0x3040, 4
    EGL_RED, EGL_GREEN, EGL_BLUE, EGL_ALPHA, EGL_NONE = 0x3024, 0x3023, 0x3022, 0x3021, 0x3038
    cfg, n = c.choose_config([EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                              EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                              EGL_RED, 8, EGL_GREEN, 8, EGL_BLUE, 8, EGL_ALPHA, 8,
                              EGL_NONE])
    print('rcChooseConfig -> %d matching, using config index %d' % (n, cfg))
    assert n > 0

    ctx = c.call_i(OP_rcCreateContext, cfg, 0, 2)
    win = c.call_i(OP_rcCreateWindowSurface, cfg, w, h)
    cb = c.call_i(OP_rcCreateColorBuffer, w, h, GL_RGBA)
    print('context %#x, window surface %#x, color buffer %#x' % (ctx, win, cb))
    assert ctx and win and cb
    c.call_v(OP_rcSetWindowColorBuffer, win, cb)
    print('rcMakeCurrent ->', c.call_i(OP_rcMakeCurrent, ctx, win, win))

    for name, label in ((GL_VENDOR, 'GL_VENDOR'), (GL_RENDERER, 'GL_RENDERER'),
                        (GL_VERSION, 'GL_VERSION'), (GL_EXTENSIONS, 'GL_EXTENSIONS')):
        print('guest-visible %s = %s' % (label, c.gl_string(name)))

    f = lambda *v: struct.pack('<%df' % len(v), *v)
    for i in range(frames):
        c.call_v(OP_glViewport, 0, 0, w, h)
        c.call_v(OP_glDisable, GL_SCISSOR_TEST)
        c.send(OP_glClearColor, f(0.0, 0.0, 1.0, 1.0))
        c.call_v(OP_glClear, GL_COLOR_BUFFER_BIT)
        c.call_v(OP_glEnable, GL_SCISSOR_TEST)
        c.call_v(OP_glScissor, 0, h - 100, w, 100)       # top band (GL coords)
        c.send(OP_glClearColor, f(1.0, 0.0, 0.0, 1.0))
        c.call_v(OP_glClear, GL_COLOR_BUFFER_BIT)
        c.call_v(OP_glScissor, 10 * i, 0, 100, 100)      # bottom-left square
        c.send(OP_glClearColor, f(0.0, 1.0, 0.0, 1.0))
        c.call_v(OP_glClear, GL_COLOR_BUFFER_BIT)
        err = c.call_i(OP_glGetError)
        flushed = c.call_i(OP_rcFlushWindowColorBuffer, win)
        c.call_v(OP_rcFBPost, cb)
        print('frame %d: glGetError=%#x rcFlushWindowColorBuffer=%d' % (i, err, flushed))

    # Round trip once more so we know the last rcFBPost has been executed.
    c.call_i(OP_rcGetRendererVersion)
    print('rcMakeCurrent(0,0,0) ->', c.call_i(OP_rcMakeCurrent, 0, 0, 0))
    c.call_v(OP_rcDestroyWindowSurface, win)
    c.call_v(OP_rcDestroyContext, ctx)
    c.call_v(OP_rcCloseColorBuffer, cb)
    c.call_i(OP_rcGetRendererVersion)
    c.s.close()
    print('OK')


if __name__ == '__main__':
    main()
