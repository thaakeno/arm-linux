#include <jni.h>
#include <unistd.h>
#include <poll.h>
#include <fcntl.h>
#include <cerrno>
#include <cstring>
#include <string>
#include <stdexcept>
#include <chrono>
#include <cstdint>
#include <sys/socket.h>

namespace {
constexpr uint32_t tag(char a,char b,char c,char d) { return uint32_t(a)|(uint32_t(b)<<8)|(uint32_t(c)<<16)|(uint32_t(d)<<24); }
constexpr auto CNXN=tag('C','N','X','N'), AUTH=tag('A','U','T','H'), OPEN=tag('O','P','E','N');
constexpr auto OKAY=tag('O','K','A','Y'), WRTE=tag('W','R','T','E'), CLSE=tag('C','L','S','E');
using Clock=std::chrono::steady_clock;

struct Fd { int fd=-1; ~Fd(){ if(fd>=0) close(fd); } };
struct Header { uint32_t cmd,a,b,len,sum,magic; };

void ready(int fd, short events, Clock::time_point deadline) {
    const auto left=std::chrono::duration_cast<std::chrono::milliseconds>(deadline-Clock::now()).count();
    if(left<=0) throw std::runtime_error("Guest operation timed out");
    pollfd p{fd,events,0};
    int rc;
    do { rc=poll(&p,1,static_cast<int>(left)); } while(rc<0 && errno==EINTR);
    if(rc==0) throw std::runtime_error("Guest connection timed out");
    if(rc<0) throw std::runtime_error(std::string("poll: ")+strerror(errno));
    if(p.revents&(POLLERR|POLLHUP|POLLNVAL)) throw std::runtime_error("Guest connection closed");
}

void transfer(int fd, void* ptr, size_t len, bool write, Clock::time_point deadline) {
    auto* p=static_cast<char*>(ptr);
    while(len){
        ready(fd,write?POLLOUT:POLLIN,deadline);
        ssize_t n=write?send(fd,p,len,MSG_NOSIGNAL):recv(fd,p,len,0);
        if(n<0&&(errno==EINTR||errno==EAGAIN||errno==EWOULDBLOCK)) continue;
        if(n<0) throw std::runtime_error(std::string(write?"send: ":"recv: ")+strerror(errno));
        if(n==0) throw std::runtime_error("Guest connection closed");
        p+=n; len-=static_cast<size_t>(n);
    }
}

void packet(int fd,uint32_t cmd,uint32_t a,uint32_t b,const std::string& data,Clock::time_point until) {
    uint32_t sum=0; for(unsigned char c:data) sum+=c;
    Header h{cmd,a,b,static_cast<uint32_t>(data.size()),sum,cmd^0xffffffff};
    transfer(fd,&h,sizeof(h),true,until);
    if(!data.empty()) transfer(fd,const_cast<char*>(data.data()),data.size(),true,until);
}

std::string shellFd(int suppliedFd,const std::string& command) {
    Fd s{dup(suppliedFd)};
    if(s.fd<0) throw std::runtime_error(std::string("dup vsock fd: ")+strerror(errno));
    int flags=fcntl(s.fd,F_GETFL,0);
    if(flags>=0) fcntl(s.fd,F_SETFL,flags|O_NONBLOCK);
    auto until=Clock::now()+std::chrono::seconds(25);
    packet(s.fd,CNXN,0x01000000,4096,std::string("host::\0",7),until);
    bool opened=false;
    std::string out;
    for(;;){
        Header h{}; transfer(s.fd,&h,sizeof(h),false,until);
        if(h.magic!=(h.cmd^0xffffffff)||h.len>1024*1024) throw std::runtime_error("Invalid ADB packet");
        std::string data(h.len,'\0'); if(h.len) transfer(s.fd,data.data(),h.len,false,until);
        if(h.cmd==AUTH) throw std::runtime_error("Guest requested ADB authentication");
        if(h.cmd==CNXN&&!opened){
            auto service="shell:"+command; service.push_back('\0');
            packet(s.fd,OPEN,1,0,service,until); opened=true;
        } else if(h.cmd==WRTE&&opened&&h.b==1){
            out+=data;
            packet(s.fd,OKAY,1,h.a,"",until);
            if(out.size()>256*1024) throw std::runtime_error("Guest output exceeded 256 KB limit");
        } else if(h.cmd==CLSE&&opened){
            packet(s.fd,CLSE,1,h.a,"",until);
            return out;
        }
    }
}
}

extern "C" JNIEXPORT jstring JNICALL Java_com_example_dreamlinux_NativeTransport_shellFd(JNIEnv* env,jobject,jint fd,jstring cmd){
    const char* value=env->GetStringUTFChars(cmd,nullptr); if(!value) return nullptr;
    std::string command(value); env->ReleaseStringUTFChars(cmd,value);
    try { auto result=shellFd(fd,command); return env->NewStringUTF(result.c_str()); }
    catch(const std::exception& e){ env->ThrowNew(env->FindClass("java/io/IOException"),e.what()); return nullptr; }
}
