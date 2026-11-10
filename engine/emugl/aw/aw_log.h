// Tiny logging helper shared by the awgl glue files.
// Everything goes to stderr; on Android awgl_start() redirects stdout/stderr
// into logcat (tag "awgl"), which also captures emugl's own fprintf()s.
#pragma once
#include <stdio.h>
#define AWLOG(...) do { fprintf(stderr, "awgl: " __VA_ARGS__); fputc('\n', stderr); } while (0)
