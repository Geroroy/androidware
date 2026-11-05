#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/wait.h>
#include <sys/reboot.h>
#include <sys/utsname.h>
#include <fcntl.h>
#ifndef BENCH32
#define BENCH32 "/bench-arm32-glibc"
#endif
#ifndef BENCH64
#define BENCH64 "/bench-arm64-glibc"
#endif
static void run(const char *path){pid_t p=fork();if(p==0){execl(path,path,"1",(char*)0);perror("execl");_exit(127);}int st=0;waitpid(p,&st,0);printf("exit status of %s = %d (signal %d)\n",path,WEXITSTATUS(st),WIFSIGNALED(st)?WTERMSIG(st):0);fflush(stdout);}
int main(){mount("proc","/proc","proc",0,0);struct utsname u;uname(&u);printf("=== GUEST: %s %s\n",u.machine,u.release);
FILE*f=fopen("/proc/cpuinfo","r");char line[512];while(f&&fgets(line,sizeof line,f)){if(!strncmp(line,"Features",8)||!strncmp(line,"CPU part",8)){printf("=== %s",line);}}if(f)fclose(f);
printf("=== running arm64 bench (AArch64 EL0)\n");fflush(stdout);run(BENCH64);
printf("=== running arm32 bench (AArch32 EL0 via kernel COMPAT)\n");fflush(stdout);run(BENCH32);
printf("=== DONE\n");fflush(stdout);sync();reboot(RB_POWER_OFF);return 0;}
