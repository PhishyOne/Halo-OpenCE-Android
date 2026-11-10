from pathlib import Path
import os, shutil, subprocess, tempfile
from harness import function, read

SRC=read("port/linux/game/bots.c")
ROOT=Path(__file__).resolve().parents[1]
C=r"""
#include <stdio.h>
#include <math.h>
#include <string.h>
#include "bot_target_range_gate.h"
#define TRUE 1
#define FALSE 0
#define NONE (-1)
#define BOTS_MAXIMUM 127
#define MAXIMUM_LOCAL_PLAYERS 4
#define NUMBEROF(a) (sizeof(a)/sizeof((a)[0]))
typedef float real;
typedef int boolean;
typedef struct real_point3d {float x,y,z;} real_point3d;
struct bots_target_candidate {long player_index,unit_index; real score; real_point3d point;};
static int visible[131],got[131],want[131],ng,nw;
static boolean bots_target_line_of_sight(long a,real_point3d const *b,real_point3d const *c,long u){
 (void)a;(void)b;(void)c;
 got[ng++]=(int)u;
 return visible[u];
}
#include "less.inc"
#include "sift.inc"
#include "select.inc"
static long previous(struct bots_target_candidate *input,int n){
 struct bots_target_candidate c[131];
 int i,small;
 memcpy(c,input,n*sizeof(c[0]));
 while(n>0){
  small=0;
  for(i=1;i<n;i++)if(c[i].score<c[small].score)small=i;
  want[nw++]=(int)c[small].unit_index;
  if(visible[c[small].unit_index])return c[small].player_index;
  n--;
  for(i=small;i<n;i++)c[i]=c[i+1];
 }
 return NONE;
}
static unsigned seed=0xC0FFEE12u;
static unsigned next_random(void){seed^=seed<<13;seed^=seed>>17;seed^=seed<<5;return seed;}
#define CHECK(x) do{if(!(x)){fprintf(stderr,"bad line %d %s\n",__LINE__,#x);return 1;}}while(0)
int main(void){
 struct bots_target_candidate c[131];real_point3d origin={0,0,0};
 int n,t,i,rounds=0,k;
 for(n=0;n<=131;n++)for(t=0;t<32;t++){
  for(i=0;i<n;i++){
   c[i].player_index=100+i;c[i].unit_index=i;
   c[i].score=(float)((int)(next_random()%19)-9)*0.25f;
   visible[i]=t%7==0?0:(int)(next_random()%5==0);
   if(t==11)visible[i]=1;
   if(t==12)visible[i]=0;
   if(t==13)c[i].score=0.0f;
  }
  ng=nw=0;
  {
   long old=previous(c,n);
   long modern=bots_target_ordered_visible(c,n,0,&origin);
   CHECK(old==modern);
   CHECK(ng==nw);
   CHECK(memcmp(got,want,ng*sizeof(int))==0);
  }
  rounds++;
 }
 CHECK(bots_target_ordered_visible(c,-1,0,&origin)==NONE);
 CHECK(bots_target_ordered_visible(c,132,0,&origin)==NONE);
 for(k=0;k<7;k++){
  float reach=(float[]){4.8f,16,22,32,42,55,68.75f}[k];
  for(i=0;i<50000;i++){
   float dx=((int)(next_random()%400001)-200000)*reach/100000.0f;
   float dy=((int)(next_random()%400001)-200000)*reach/100000.0f;
   float dz=((int)(next_random()%400001)-200000)*reach/100000.0f;
   float distance=(float)sqrt(dx*dx+dy*dy+dz*dz);
   if(distance<=reach)CHECK(!halo_bot_target_outside_reach_box(dx,dy,dz,reach));
  }
  CHECK(halo_bot_target_outside_reach_box(3*reach,0,0,reach));
  CHECK(!halo_bot_target_outside_reach_box(reach,0,0,reach));
 }
 CHECK(!halo_bot_target_outside_reach_box(NAN,0,0,20));
 printf("PASS %d exact legacy/heap match cases, up to 131 targets; 350k reach trials\n",rounds);
 return 0;
}
"""
def main():
 compiler=os.environ.get("CC") or shutil.which("cc")
 assert compiler, "C compiler missing"
 assert "if (score != score)" in function(SRC,"bots_find_target")
 assert "if (!ordered)" in function(SRC,"bots_find_target")
 assert "bots_target_ordered_visible(candidates, candidate_count," in function(SRC,"bots_find_target")
 assert "halo_bot_target_outside_reach_box(" in function(SRC,"bots_target_score")
 with tempfile.TemporaryDirectory() as temporary:
  p=Path(temporary)
  (p/"test.c").write_text(C)
  for name,func in [("less.inc","bots_target_heap_less"),("sift.inc","bots_target_heap_sift"),("select.inc","bots_target_ordered_visible")]:
   (p/name).write_text(function(SRC,func))
  out=p/"check"
  args=[compiler,"-std=gnu99","-O2","-Wall","-Wextra","-Werror","-I",str(ROOT/"port/linux/game"),"-I",str(p),str(p/"test.c"),"-o",str(out),"-lm"]
  proc=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
  assert proc.returncode==0,proc.stderr
  proc=subprocess.run([str(out)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,timeout=20)
  assert proc.returncode==0,proc.stderr
  print(proc.stdout.strip())
if __name__=="__main__":main()
