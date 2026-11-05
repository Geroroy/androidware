#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <time.h>
#include <string.h>
#include <math.h>
static double now(){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec+t.tv_nsec*1e-9;}
/* integer: xorshift + mixing loop (typical game logic) */
static uint32_t int_work(uint32_t n){uint32_t x=2463534242u,acc=0;for(uint32_t i=0;i<n;i++){x^=x<<13;x^=x>>17;x^=x<<5;acc+=x*(i|1);acc^=acc>>7;}return acc;}
/* float: 4x4 matrix * vec, typical 3D math */
static float fp_work(uint32_t n){float m[16];for(int i=0;i<16;i++)m[i]=(float)(i%5)*0.25f+1.0f;float v[4]={1,2,3,1},o[4];float acc=0;for(uint32_t i=0;i<n;i++){for(int r=0;r<4;r++){o[r]=m[r*4]*v[0]+m[r*4+1]*v[1]+m[r*4+2]*v[2]+m[r*4+3]*v[3];}float inv=1.0f/(fabsf(o[0])+fabsf(o[1])+fabsf(o[2])+1.0f);v[0]=o[0]*inv;v[1]=o[1]*inv;v[2]=o[2]*inv;v[3]=1;acc+=sqrtf(fabsf(o[0]))*inv;}return acc;}
/* memory: memcpy/stride */
static uint32_t mem_work(uint32_t n){size_t sz=1<<20;uint8_t*a=malloc(sz),*b=malloc(sz);memset(a,1,sz);uint32_t acc=0;for(uint32_t i=0;i<n;i++){memcpy(b,a,sz);acc+=b[(i*4099)&(sz-1)];a[(i*8191)&(sz-1)]++;}free(a);free(b);return acc;}
int main(int argc,char**argv){uint32_t scale=argc>1?atoi(argv[1]):1;double t0=now();uint32_t r1=int_work(50000000u*scale);double t1=now();float r2=fp_work(10000000u*scale);double t2=now();uint32_t r3=mem_work(200*scale);double t3=now();
printf("int_ms=%.0f fp_ms=%.0f mem_ms=%.0f (chk %u %.1f %u) arch=%s\n",(t1-t0)*1e3,(t2-t1)*1e3,(t3-t2)*1e3,r1,r2,r3,
#if defined(__aarch64__)
"aarch64"
#elif defined(__arm__)
"arm32"
#elif defined(__x86_64__)
"x86_64"
#else
"other"
#endif
);return 0;}
