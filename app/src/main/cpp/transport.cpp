#include <jni.h>
#include <unistd.h>
#include <poll.h>
#include <cerrno>
#include <cstring>
#include <string>
#include <stdexcept>
#include <chrono>

namespace {
constexpr uint32_t tag(char a,char b,char c,char d) { return uint32_t(a)|(uint32_t(b)<<8)|(uint32_t(c)<<16)|(uint32_t(d)<<24); }
constexpr auto CNXN=tag('C','N','X','N'), AUTH=tag('A','U','T','H'), OPEN=tag('O','P','E','N');
constexpr auto OKAY=tag('O','K','A','Y'), WRTE=tag('W','R','T','E'), CLSE=tag('C','L','S','E');
using Clock=std::chrono::steady_clock;

struct OwnedFd {
    int fd;
    ~OwnedFd(){ if(fd>=0) close(fd); }
};

void ready(int fd, short events, Clock::time_point deadline) {
    int ms=static_cast<int>(std::chrono::duration_cast<std::chrono::milliseconds>(deadline-Clock::now()).count());
    if(ms<=0) throw std::runtime_error("Guest operation timed out");
    pollfd p{fd,events,0};
    int rc;
    do { rc=poll(&p,1,ms); } while(rc<0 && errno==EINTR);
    if(rc==0) throw std::runtime_error("Guest connection timed out");
    if(rc<0) throw std::runtime_error(std::string("poll: ")+strerror(errno));
    if(p.revents & (POLLERR|POLLHUP|POLLNVAL)) throw std::runtime_error("Guest connection closed or failed");
}

void transfer(int fd, void* ptr, size_t len, bool write, Clock::time_point deadline) {
    auto p=static_cast<char*>(ptr);
    while(len){
        ready(fd,write?POLLOUT:POLLIN,deadline);
        ssize_t n=write?::write(fd,p,len):::read(fd,p,len);
        if(n<0&&(errno==EINTR||errno==EAGAIN)) continue;
        if(n<=0) throw std::runtime_error("Guest connection closed or failed");
        p+=n; len-=static_cast<size_t>(n);
    }
}

struct Header { uint32_t cmd,a,b,len,sum,magic; };

void packet(int fd,uint32_t cmd,uint32_t a,uint32_t b,std::string data,Clock::time_point until) {
    uint32_t sum=0; for(unsigned char c:data) sum+=c;
    Header h{cmd,a,b,static_cast<uint32_t>(data.size()),sum,cmd^0xffffffff};
    transfer(fd,&h,sizeof(h),true,until);
    if(!data.empty()) transfer(fd,data.data(),data.size(),true,until);
}

std::string shellOnConnectedFd(int borrowedFd,const std::string& command) {
    // JNI receives a borrowed Java-owned fd. Duplicate it so native ownership is explicit:
    // closing this function's fd can never invalidate the ParcelFileDescriptor on the Java side.
    int duplicated;
    do { duplicated=dup(borrowedFd); } while(duplicated<0 && errno==EINTR);
    if(duplicated<0) throw std::runtime_error(std::string("dup connected vsock fd: ")+strerror(errno));
    OwnedFd s{duplicated};

    auto until=Clock::now()+std::chrono::seconds(20);
    packet(s.fd,CNXN,0x01000000,4096,std::string("host::\0",7),until);
    bool opened=false;
    std::string out;
    for(;;){
        Header h{};
        transfer(s.fd,&h,sizeof(h),false,until);
        if(h.magic!=(h.cmd^0xffffffff)||h.len>1024*1024) throw std::runtime_error("Invalid ADB packet");
        std::string data(h.len,'\0');
        if(h.len) transfer(s.fd,data.data(),h.len,false,until);
        if(h.cmd==AUTH) {
            throw std::runtime_error("Guest requires ADB authentication; no authentication bypass attempted");
        }
        if(h.cmd==CNXN&&!opened){
            auto service="shell:"+command;
            service.push_back('\0');
            packet(s.fd,OPEN,1,0,service,until);
            opened=true;
        } else if(h.cmd==WRTE&&opened&&h.b==1){
            out+=data;
            packet(s.fd,OKAY,1,h.a,"",until);
            if(out.size()>60000) throw std::runtime_error("Guest output exceeded 60 KB limit");
        } else if(h.cmd==CLSE){
            packet(s.fd,CLSE,1,h.a,"",until);
            return out;
        }
    }
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_dreamlinux_NativeTransport_shellFd(JNIEnv* env,jobject,jint fd,jstring cmd){
    if(fd<0){
        env->ThrowNew(env->FindClass("java/io/IOException"),"Invalid connected vsock fd");
        return nullptr;
    }
    const char* value=env->GetStringUTFChars(cmd,nullptr);
    if(!value) return nullptr;
    std::string command(value);
    env->ReleaseStringUTFChars(cmd,value);
    try {
        auto result=shellOnConnectedFd(fd,command);
        return env->NewStringUTF(result.c_str());
    } catch(const std::exception& e){
        env->ThrowNew(env->FindClass("java/io/IOException"),e.what());
        return nullptr;
    }
}
