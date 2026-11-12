/* ctxbench: two processes ping-pong over pipes; after every wake-up each touches PAGES data pages and
 * calls FUNCS small functions spread over separate code pages (its "working set").
 * Measures the cost of an address-space switch in the emulator (TLB + translated-code lookup refill). */
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <time.h>
#include <sys/wait.h>
#define PAGES 256
#define FUNCS 64
static double now(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec+t.tv_nsec*1e-9;}
#define F(n) __attribute__((noinline,aligned(4096))) static int f##n(int x){return x*31+n;}
#define F8(n) F(n##0) F(n##1) F(n##2) F(n##3) F(n##4) F(n##5) F(n##6) F(n##7)
F8(0) F8(1) F8(2) F8(3) F8(4) F8(5) F8(6) F8(7)
#define P(n) f##n,
#define P8(n) P(n##0) P(n##1) P(n##2) P(n##3) P(n##4) P(n##5) P(n##6) P(n##7)
static int (*volatile fn[FUNCS])(int)={P8(0) P8(1) P8(2) P8(3) P8(4) P8(5) P8(6) P8(7)};
static volatile unsigned char *mem;
static int work(int x){for(int i=0;i<PAGES;i++)x+=mem[i*4096]++;for(int i=0;i<FUNCS;i++)x=fn[i](x);return x;}
int main(int argc,char**argv){int n=argc>1?atoi(argv[1]):20000;int a[2],b[2];char c=0;int acc=0;
  if(pipe(a)||pipe(b))return 1;mem=calloc(PAGES,4096);
  pid_t p=fork();
  if(p==0){close(a[1]);close(b[0]);for(;;){if(read(a[0],&c,1)!=1)_exit(0);acc=work(acc);if(write(b[1],&c,1)!=1)_exit(0);}}
  close(a[0]);close(b[1]);
  double t0=now();
  for(int i=0;i<n;i++){if(write(a[1],&c,1)!=1)return 2;if(read(b[0],&c,1)!=1)return 3;acc=work(acc);}
  double t1=now();close(a[1]);waitpid(p,0,0);
  printf("ctx_ms=%.0f us_per_roundtrip=%.1f (n=%d chk %d)\n",(t1-t0)*1e3,(t1-t0)*1e6/n,n,acc&0xffff);return 0;}
