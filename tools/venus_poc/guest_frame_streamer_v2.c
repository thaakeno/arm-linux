#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/tcp.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define XWD_VERSION 7u
#define ZPIXMAP 2u
#define TILE 64u
#define FULL_INTERVAL 240u

static uint32_t bswap32u(uint32_t v){ return __builtin_bswap32(v); }
static uint16_t be16(uint16_t v){ return htons(v); }
static uint32_t be32(uint32_t v){ return htonl(v); }
static uint64_t be64(uint64_t v){
#if __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
    return __builtin_bswap64(v);
#else
    return v;
#endif
}

static int send_all(int fd, const void *buf, size_t len){
    const uint8_t *p=(const uint8_t*)buf;
    while(len){
        ssize_t n=send(fd,p,len,MSG_NOSIGNAL);
        if(n<0){ if(errno==EINTR) continue; return -1; }
        if(n==0) return -1;
        p+=n; len-=(size_t)n;
    }
    return 0;
}

struct meta {
    uint32_t width,height,stride,bpp,byte_order,red,green,blue,offset;
};

static int parse_xwd(const uint8_t *base,size_t size,struct meta *m){
    if(size<100) return -1;
    for(int mode=0; mode<2; ++mode){
        uint32_t v[25];
        memcpy(v,base,100);
        if(mode==0) for(int i=0;i<25;i++) v[i]=bswap32u(v[i]);
        uint32_t header=v[0], version=v[1], format=v[2], width=v[4], height=v[5];
        uint32_t bpp=v[11], stride=v[12], ncolors=v[19];
        if(version!=XWD_VERSION || format!=ZPIXMAP || width<320 || width>7680 || height<240 || height>4320) continue;
        if(bpp!=32 || stride<width*4u || header<100 || header>65536 || ncolors>4096) continue;
        uint64_t off=(uint64_t)header+(uint64_t)ncolors*12u;
        if(off+(uint64_t)stride*height>size) continue;
        m->width=width; m->height=height; m->stride=stride; m->bpp=bpp; m->byte_order=v[7];
        m->red=v[14]; m->green=v[15]; m->blue=v[16]; m->offset=(uint32_t)off;
        return 0;
    }
    return -1;
}

static inline void pixel_rgba(const uint8_t *src,const struct meta *m,uint8_t *dst){
    if(m->byte_order==0 && m->red==0x00ff0000u && m->green==0x0000ff00u && m->blue==0x000000ffu){
        dst[0]=src[2]; dst[1]=src[1]; dst[2]=src[0]; dst[3]=255; return;
    }
    uint32_t p;
    if(m->byte_order==0) p=(uint32_t)src[0]|((uint32_t)src[1]<<8)|((uint32_t)src[2]<<16)|((uint32_t)src[3]<<24);
    else p=(uint32_t)src[3]|((uint32_t)src[2]<<8)|((uint32_t)src[1]<<16)|((uint32_t)src[0]<<24);
    uint32_t rs=m->red?__builtin_ctz(m->red):0, gs=m->green?__builtin_ctz(m->green):0, bs=m->blue?__builtin_ctz(m->blue):0;
    dst[0]=(uint8_t)((p&m->red)>>rs); dst[1]=(uint8_t)((p&m->green)>>gs); dst[2]=(uint8_t)((p&m->blue)>>bs); dst[3]=255;
}

static int tile_changed(const uint8_t *a,const uint8_t *b,const struct meta *m,uint32_t x,uint32_t y,uint32_t w,uint32_t h){
    size_t row=(size_t)w*4u;
    for(uint32_t yy=0;yy<h;yy++){
        const uint8_t *pa=a+(size_t)(y+yy)*m->stride+(size_t)x*4u;
        const uint8_t *pb=b+(size_t)(y+yy)*m->stride+(size_t)x*4u;
        if(memcmp(pa,pb,row)!=0) return 1;
    }
    return 0;
}

static void copy_tile(uint8_t *dst,const uint8_t *src,const struct meta *m,uint32_t x,uint32_t y,uint32_t w,uint32_t h){
    size_t row=(size_t)w*4u;
    for(uint32_t yy=0;yy<h;yy++) memcpy(dst+(size_t)(y+yy)*m->stride+(size_t)x*4u,src+(size_t)(y+yy)*m->stride+(size_t)x*4u,row);
}

static size_t encode_tile(uint8_t *out,const uint8_t *src,const struct meta *m,uint32_t x,uint32_t y,uint32_t w,uint32_t h){
    size_t at=0;
    for(uint32_t yy=0;yy<h;yy++){
        const uint8_t *row=src+(size_t)(y+yy)*m->stride+(size_t)x*4u;
        for(uint32_t xx=0;xx<w;xx++,at+=4) pixel_rgba(row+xx*4u,m,out+at);
    }
    return at;
}

static int connect_host(const char *host,int port){
    int s=socket(AF_INET,SOCK_STREAM,0); if(s<0) return -1;
    int one=1, sz=4*1024*1024;
    setsockopt(s,IPPROTO_TCP,TCP_NODELAY,&one,sizeof(one));
    setsockopt(s,SOL_SOCKET,SO_KEEPALIVE,&one,sizeof(one));
    setsockopt(s,SOL_SOCKET,SO_SNDBUF,&sz,sizeof(sz));
    struct sockaddr_in a={0}; a.sin_family=AF_INET; a.sin_port=htons((uint16_t)port);
    if(inet_pton(AF_INET,host,&a.sin_addr)!=1 || connect(s,(struct sockaddr*)&a,sizeof(a))<0){ close(s); return -1; }
    return s;
}

static int send_header(int s,const struct meta *m,int fps){
    char h[256];
    int n=snprintf(h,sizeof(h),"{\"magic\":\"VFRM2\",\"width\":%u,\"height\":%u,\"format\":\"RGBA8888\",\"tile\":%u,\"fps\":%d}\n",m->width,m->height,TILE,fps);
    return send_all(s,h,(size_t)n);
}

static int send_full(int s,uint64_t seq,const uint8_t *cur,const struct meta *m,uint8_t *scratch){
    size_t total=(size_t)m->width*m->height*4u, at=0;
    for(uint32_t y=0;y<m->height;y++){
        const uint8_t *row=cur+(size_t)y*m->stride;
        for(uint32_t x=0;x<m->width;x++,at+=4) pixel_rgba(row+x*4u,m,scratch+at);
    }
    uint8_t head[13]; head[0]='F'; uint64_t sq=be64(seq); uint32_t sz=be32((uint32_t)total);
    memcpy(head+1,&sq,8); memcpy(head+9,&sz,4);
    return send_all(s,head,sizeof(head)) || send_all(s,scratch,total);
}

static int send_delta(int s,uint64_t seq,const uint8_t *cur,uint8_t *prev,const struct meta *m,uint8_t *scratch){
    uint32_t nx=(m->width+TILE-1)/TILE, ny=(m->height+TILE-1)/TILE, count=0;
    for(uint32_t ty=0;ty<ny;ty++) for(uint32_t tx=0;tx<nx;tx++){
        uint32_t x=tx*TILE,y=ty*TILE,w=m->width-x<TILE?m->width-x:TILE,h=m->height-y<TILE?m->height-y:TILE;
        if(tile_changed(cur,prev,m,x,y,w,h)) count++;
    }
    if(!count) return 1;
    uint8_t head[13]; head[0]='D'; uint64_t sq=be64(seq); uint32_t c=be32(count);
    memcpy(head+1,&sq,8); memcpy(head+9,&c,4);
    if(send_all(s,head,sizeof(head))<0) return -1;
    for(uint32_t ty=0;ty<ny;ty++) for(uint32_t tx=0;tx<nx;tx++){
        uint32_t x=tx*TILE,y=ty*TILE,w=m->width-x<TILE?m->width-x:TILE,h=m->height-y<TILE?m->height-y:TILE;
        if(!tile_changed(cur,prev,m,x,y,w,h)) continue;
        uint32_t bytes=w*h*4u; uint8_t th[12];
        uint16_t vx=be16((uint16_t)x),vy=be16((uint16_t)y),vw=be16((uint16_t)w),vh=be16((uint16_t)h); uint32_t vb=be32(bytes);
        memcpy(th,&vx,2); memcpy(th+2,&vy,2); memcpy(th+4,&vw,2); memcpy(th+6,&vh,2); memcpy(th+8,&vb,4);
        encode_tile(scratch,cur,m,x,y,w,h);
        if(send_all(s,th,sizeof(th))<0 || send_all(s,scratch,bytes)<0) return -1;
        copy_tile(prev,cur,m,x,y,w,h);
    }
    return 0;
}

static void sleep_until(struct timespec *next,long period_ns){
    next->tv_nsec += period_ns;
    while(next->tv_nsec>=1000000000L){ next->tv_nsec-=1000000000L; next->tv_sec++; }
    clock_nanosleep(CLOCK_MONOTONIC,TIMER_ABSTIME,next,NULL);
}

int main(int argc,char **argv){
    const char *path=argc>1?argv[1]:"/tmp/vessel-fb/Xvfb_screen0";
    const char *host=argc>2?argv[2]:"10.0.2.2";
    int port=argc>3?atoi(argv[3]):47637, fps=argc>4?atoi(argv[4]):120;
    if(fps<30) fps=30; if(fps>120) fps=120;
    int fd=open(path,O_RDONLY); if(fd<0){ perror("open xwd"); return 2; }
    struct stat st; if(fstat(fd,&st)<0){ perror("stat xwd"); return 2; }
    uint8_t *map=mmap(NULL,(size_t)st.st_size,PROT_READ,MAP_SHARED,fd,0); if(map==MAP_FAILED){ perror("mmap xwd"); return 2; }
    struct meta m; if(parse_xwd(map,(size_t)st.st_size,&m)<0){ fprintf(stderr,"VFRM2: bad XWD\n"); return 2; }
    const uint8_t *cur=map+m.offset; size_t raw=(size_t)m.stride*m.height;
    uint8_t *prev=calloc(1,raw); uint8_t *scratch=malloc((size_t)m.width*m.height*4u);
    if(!prev||!scratch){ fprintf(stderr,"VFRM2: oom\n"); return 2; }
    uint64_t seq=0; unsigned full_counter=FULL_INTERVAL;
    struct timespec next; clock_gettime(CLOCK_MONOTONIC,&next); long period=1000000000L/fps;
    for(;;){
        int s=connect_host(host,port); if(s<0){ usleep(30000); continue; }
        if(send_header(s,&m,fps)<0){ close(s); continue; }
        full_counter=FULL_INTERVAL;
        for(;;){
            int rc;
            if(full_counter>=FULL_INTERVAL){ seq++; rc=send_full(s,seq,cur,&m,scratch); if(rc<0) break; memcpy(prev,cur,raw); full_counter=0; }
            else { seq++; rc=send_delta(s,seq,cur,prev,&m,scratch); if(rc<0) break; if(rc==0) full_counter++; else seq--; }
            sleep_until(&next,period);
        }
        close(s); usleep(20000);
    }
}
