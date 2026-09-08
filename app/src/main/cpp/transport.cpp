#include <jni.h>
#include <sys/socket.h>
#include <linux/vm_sockets.h>
#include <unistd.h>
#include <poll.h>
#include <fcntl.h>
#include <cerrno>
#include <cstring>
#include <string>
#include <vector>
#include <stdexcept>
#include <chrono>

namespace {
constexpr uint32_t tag(char a,char b,char c,char d) { return uint32_t(a)|(uint32_t(b)<<8)|(uint32_t(c)<<16)|(uint32_t(d)<<24); }
constexpr auto CNXN=tag('C','N','X','N'), AUTH=tag('A','U','T','H'), OPEN=tag('O','P','E','N');
constexpr auto OKAY=tag('O','K','A','Y'), WRTE=tag('W','R','T','E'), CLSE=tag('C','L','S','E');
struct Socket { int fd; ~Socket(){ if(fd>=0)close(fd); } };
using Clock=std::chrono::steady_clock;
void ready(int fd, short events, Clock::time_point deadline) {
    int ms=static_cast<int>(std::chrono::duration_cast<std::chrono::milliseconds>(deadline-Clock::now()).count());
    if(ms<=0)throw std::runtime_error("Guest operation timed out");
    pollfd p{fd,events,0};
    if(poll(&p,1,ms)<=0)throw std::runtime_error("Guest connection timed out");
}
void transfer(int fd, void* ptr, size_t len, bool write, Clock::time_point deadline) {
    auto p=static_cast<char*>(ptr);
    while(len){ ready(fd,write?POLLOUT:POLLIN,deadline);
        ssize_t n=write?send(fd,p,len,MSG_NOSIGNAL):recv(fd,p,len,0);
        if(n<0&&(errno==EINTR||errno==EAGAIN))continue;
        if(n<=0)throw std::runtime_error("Guest connection closed or failed");
        p+=n;len-=n;
    }
}
struct Header { uint32_t cmd,a,b,len,sum,magic; };
void packet(int fd,uint32_t cmd,uint32_t a,uint32_t b,std::string data,Clock::time_point until) {
    uint32_t sum=0;for(unsigned char c:data)sum+=c;
    Header h{cmd,a,b,static_cast<uint32_t>(data.size()),sum,cmd^0xffffffff};
    transfer(fd,&h,sizeof(h),true,until);
    if(!data.empty())transfer(fd,data.data(),data.size(),true,until);
}
std::string shell(int cid,const std::string& command) {
    Socket s{socket(AF_VSOCK,SOCK_STREAM|SOCK_CLOEXEC|SOCK_NONBLOCK,0)};
    if(s.fd<0)throw std::runtime_error(std::string("vsock socket: ")+strerror(errno));
    auto until=Clock::now()+std::chrono::seconds(20);
    sockaddr_vm addr{};addr.svm_family=AF_VSOCK;addr.svm_cid=cid;addr.svm_port=5555;
    if(connect(s.fd,reinterpret_cast<sockaddr*>(&addr),sizeof(addr))<0){
        if(errno!=EINPROGRESS)throw std::runtime_error(std::string("vsock connect: ")+strerror(errno));
        ready(s.fd,POLLOUT,until);int err=0;socklen_t size=sizeof(err);
        if(getsockopt(s.fd,SOL_SOCKET,SO_ERROR,&err,&size)<0||err)throw std::runtime_error(std::string("vsock connect: ")+strerror(err?err:errno));
    }
    packet(s.fd,CNXN,0x01000000,4096,std::string("host::\0",7),until);
    bool opened=false;std::string out;
    for(;;){
        Header h{};transfer(s.fd,&h,sizeof(h),false,until);
        if(h.magic!=(h.cmd^0xffffffff)||h.len>1024*1024)throw std::runtime_error("Invalid ADB packet");
        std::string data(h.len,'\0');if(h.len)transfer(s.fd,data.data(),h.len,false,until);
        if(h.cmd==AUTH)throw std::runtime_error("Guest requires ADB authentication; no authentication bypass attempted");
        if(h.cmd==CNXN&&!opened){ auto service="shell:"+command;service.push_back('\0');packet(s.fd,OPEN,1,0,service,until);opened=true; }
        else if(h.cmd==WRTE&&opened&&h.b==1){
            out+=data;packet(s.fd,OKAY,1,h.a,"",until);
            if(out.size()>60000)throw std::runtime_error("Guest output exceeded 60 KB limit");
        } else if(h.cmd==CLSE){ packet(s.fd,CLSE,1,h.a,"",until);return out; }
    }
}
}
extern "C" JNIEXPORT jstring JNICALL Java_com_example_dreamlinux_NativeTransport_shell(JNIEnv* env,jobject,jint cid,jstring cmd){
    const char* value=env->GetStringUTFChars(cmd,nullptr);if(!value)return nullptr;
    std::string command(value);env->ReleaseStringUTFChars(cmd,value);
    try { auto result=shell(cid,command);return env->NewStringUTF(result.c_str()); }
    catch(const std::exception& e){env->ThrowNew(env->FindClass("java/io/IOException"),e.what());return nullptr;}
}
