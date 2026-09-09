#include <arpa/inet.h>
#include <linux/vm_sockets.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
#include <signal.h>
#include <errno.h>
#include <string.h>

#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {
constexpr unsigned int WORKER_PORT = 7777;
constexpr unsigned int VNC_PORT = 5901;
constexpr int HTTP_PORT = 3128;
std::mutex workers_mu;
std::condition_variable workers_cv;
std::deque<int> workers;

void close_fd(int fd) { if (fd >= 0) ::close(fd); }

bool write_all(int fd, const void* data, size_t size) {
    const char* p = static_cast<const char*>(data);
    while (size) {
        ssize_t n = ::send(fd, p, size, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        p += n;
        size -= static_cast<size_t>(n);
    }
    return true;
}

bool write_all(int fd, const std::string& s) { return write_all(fd, s.data(), s.size()); }

std::string read_line(int fd, size_t limit = 4096) {
    std::string out;
    out.reserve(128);
    while (out.size() < limit) {
        char c = 0;
        ssize_t n = ::recv(fd, &c, 1, 0);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0 || c == '\n') break;
        if (c != '\r') out.push_back(c);
    }
    return out;
}

void copy_loop(int from, int to) {
    std::vector<char> buf(64 * 1024);
    for (;;) {
        ssize_t n = ::recv(from, buf.data(), buf.size(), 0);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        if (!write_all(to, buf.data(), static_cast<size_t>(n))) break;
    }
    ::shutdown(to, SHUT_WR);
}

void relay(int a, int b, const std::string& initial_to_b = {}) {
    if (!initial_to_b.empty() && !write_all(b, initial_to_b)) {
        close_fd(a); close_fd(b); return;
    }
    std::thread t([a, b] { copy_loop(a, b); });
    copy_loop(b, a);
    t.join();
    close_fd(a);
    close_fd(b);
}

int new_tcp_listener(int port) {
    int fd = ::socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(static_cast<uint16_t>(port));
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (::bind(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0 || ::listen(fd, 64) < 0) {
        close_fd(fd); return -1;
    }
    return fd;
}

int new_vsock_listener(unsigned int port) {
    int fd = ::socket(AF_VSOCK, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    sockaddr_vm addr{};
    addr.svm_family = AF_VSOCK;
    addr.svm_cid = VMADDR_CID_ANY;
    addr.svm_port = port;
    if (::bind(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0 || ::listen(fd, 64) < 0) {
        close_fd(fd); return -1;
    }
    return fd;
}

void worker_accept_loop() {
    int server = new_vsock_listener(WORKER_PORT);
    if (server < 0) { perror("DEV1 worker vsock listen"); return; }
    fprintf(stderr, "DEV1 payload bridge worker vsock listening on %u\n", WORKER_PORT);
    for (;;) {
        int c = ::accept4(server, nullptr, nullptr, SOCK_CLOEXEC);
        if (c < 0) { if (errno == EINTR) continue; break; }
        {
            std::lock_guard<std::mutex> lock(workers_mu);
            workers.push_back(c);
        }
        workers_cv.notify_one();
    }
    close_fd(server);
}

int acquire_worker(const std::string& host, int port) {
    for (;;) {
        int fd = -1;
        {
            std::unique_lock<std::mutex> lock(workers_mu);
            workers_cv.wait(lock, [] { return !workers.empty(); });
            fd = workers.front();
            workers.pop_front();
        }
        std::string request = "CONNECT " + host + " " + std::to_string(port) + "\n";
        if (write_all(fd, request) && read_line(fd) == "OK") return fd;
        close_fd(fd);
    }
}

std::string read_http_header(int fd) {
    std::string header;
    std::vector<char> buf(4096);
    while (header.size() < 128 * 1024 && header.find("\r\n\r\n") == std::string::npos) {
        ssize_t n = ::recv(fd, buf.data(), buf.size(), 0);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        header.append(buf.data(), static_cast<size_t>(n));
    }
    return header;
}

bool split_host_port(const std::string& value, std::string& host, int& port, int default_port) {
    std::string v = value;
    while (!v.empty() && (v.back() == '\r' || v.back() == ' ' || v.back() == '\t')) v.pop_back();
    size_t colon = v.rfind(':');
    if (colon != std::string::npos && colon + 1 < v.size()) {
        bool digits = true;
        for (size_t i = colon + 1; i < v.size(); ++i) digits &= v[i] >= '0' && v[i] <= '9';
        if (digits) {
            host = v.substr(0, colon);
            port = atoi(v.c_str() + colon + 1);
            return !host.empty() && port > 0 && port <= 65535;
        }
    }
    host = v;
    port = default_port;
    return !host.empty();
}

void handle_http(int client) {
    std::string header = read_http_header(client);
    if (header.empty()) { close_fd(client); return; }
    size_t first_end = header.find("\r\n");
    if (first_end == std::string::npos) { close_fd(client); return; }
    std::string first = header.substr(0, first_end);
    size_t p1 = first.find(' '), p2 = p1 == std::string::npos ? std::string::npos : first.find(' ', p1 + 1);
    if (p1 == std::string::npos || p2 == std::string::npos) { close_fd(client); return; }
    std::string method = first.substr(0, p1);
    std::string target = first.substr(p1 + 1, p2 - p1 - 1);
    std::string version = first.substr(p2 + 1);

    std::string host;
    int port = 80;
    if (method == "CONNECT") {
        if (!split_host_port(target, host, port, 443)) { close_fd(client); return; }
        int worker = acquire_worker(host, port);
        if (!write_all(client, "HTTP/1.1 200 Connection Established\r\n\r\n")) {
            close_fd(client); close_fd(worker); return;
        }
        relay(client, worker);
        return;
    }

    std::string path = target;
    if (target.rfind("http://", 0) == 0 || target.rfind("https://", 0) == 0) {
        bool https = target.rfind("https://", 0) == 0;
        size_t start = https ? 8 : 7;
        size_t slash = target.find('/', start);
        std::string authority = target.substr(start, slash == std::string::npos ? std::string::npos : slash - start);
        if (!split_host_port(authority, host, port, https ? 443 : 80)) { close_fd(client); return; }
        path = slash == std::string::npos ? "/" : target.substr(slash);
    } else {
        size_t cursor = first_end + 2;
        while (cursor < header.size()) {
            size_t end = header.find("\r\n", cursor);
            if (end == std::string::npos || end == cursor) break;
            std::string line = header.substr(cursor, end - cursor);
            if (line.size() >= 5 && strncasecmp(line.c_str(), "Host:", 5) == 0) {
                std::string authority = line.substr(5);
                while (!authority.empty() && (authority.front() == ' ' || authority.front() == '\t')) authority.erase(authority.begin());
                split_host_port(authority, host, port, 80);
                break;
            }
            cursor = end + 2;
        }
    }
    if (host.empty()) { close_fd(client); return; }

    std::string rewritten = method + " " + path + " " + version + header.substr(first_end);
    int worker = acquire_worker(host, port);
    relay(client, worker, rewritten);
}

void http_loop() {
    int server = new_tcp_listener(HTTP_PORT);
    if (server < 0) { perror("DEV1 http listen"); return; }
    fprintf(stderr, "DEV1 payload HTTP proxy listening on 127.0.0.1:%d\n", HTTP_PORT);
    for (;;) {
        int c = ::accept4(server, nullptr, nullptr, SOCK_CLOEXEC);
        if (c < 0) { if (errno == EINTR) continue; break; }
        std::thread(handle_http, c).detach();
    }
    close_fd(server);
}

void vnc_loop() {
    int server = new_vsock_listener(VNC_PORT);
    if (server < 0) { perror("DEV1 vnc vsock listen"); return; }
    fprintf(stderr, "DEV1 payload VNC vsock listening on %u\n", VNC_PORT);
    for (;;) {
        int c = ::accept4(server, nullptr, nullptr, SOCK_CLOEXEC);
        if (c < 0) { if (errno == EINTR) continue; break; }
        std::thread([c] {
            int local = ::socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
            if (local < 0) { close_fd(c); return; }
            sockaddr_in addr{};
            addr.sin_family = AF_INET;
            addr.sin_port = htons(5901);
            addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
            if (::connect(local, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
                close_fd(local); close_fd(c); return;
            }
            relay(c, local);
        }).detach();
    }
    close_fd(server);
}
}

extern "C" __attribute__((visibility("default"))) int AVmPayload_main() {
    setvbuf(stdin, nullptr, _IONBF, 0);
    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    signal(SIGPIPE, SIG_IGN);
    std::puts("DEV 1 LINUX Gate A payload running");
    std::thread(worker_accept_loop).detach();
    std::thread(http_loop).detach();
    std::thread(vnc_loop).detach();
    std::puts("DEV1 payload bridge started");
    for (;;) pause();
    return 0;
}
