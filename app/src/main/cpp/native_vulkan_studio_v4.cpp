// Vessel Vulkan Studio v4
// Reuses the proven Vulkan/RT backend from v3 and replaces the simulation,
// interaction loop and JNI surface with a higher fidelity benchmark layer.

#define private public
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeCreate Java_com_example_dreamlinux_NativeCubeActivity_nativeCreate_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeDestroy Java_com_example_dreamlinux_NativeCubeActivity_nativeDestroy_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeResize Java_com_example_dreamlinux_NativeCubeActivity_nativeResize_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeRotate Java_com_example_dreamlinux_NativeCubeActivity_nativeRotate_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeZoom Java_com_example_dreamlinux_NativeCubeActivity_nativeZoom_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeSetStress Java_com_example_dreamlinux_NativeCubeActivity_nativeSetStress_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeSetPhysics Java_com_example_dreamlinux_NativeCubeActivity_nativeSetPhysics_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeSetRt Java_com_example_dreamlinux_NativeCubeActivity_nativeSetRt_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeSetQuality Java_com_example_dreamlinux_NativeCubeActivity_nativeSetQuality_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeResetPhysics Java_com_example_dreamlinux_NativeCubeActivity_nativeResetPhysics_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabStart Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabStart_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabMove Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabMove_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabEnd Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabEnd_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeStatus Java_com_example_dreamlinux_NativeCubeActivity_nativeStatus_v3_internal
#define Java_com_example_dreamlinux_NativeCubeActivity_nativeLogs Java_com_example_dreamlinux_NativeCubeActivity_nativeLogs_v3_internal
#include "native_vulkan_studio_v3.cpp"
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeCreate
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeDestroy
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeResize
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeRotate
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeZoom
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeSetStress
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeSetPhysics
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeSetRt
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeSetQuality
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeResetPhysics
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabStart
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabMove
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabEnd
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeStatus
#undef Java_com_example_dreamlinux_NativeCubeActivity_nativeLogs
#undef private

namespace {
constexpr float kStudioHalfExtent = 24.0f;

class VulkanStudioV4 final : public VulkanStudioV3 {
public:
    explicit VulkanStudioV4(ANativeWindow* w) : VulkanStudioV3(w) {
        configureScene();
        logV4("Studio v4 physics: semi-implicit impulse solver, 5 substeps, finite floor");
        logV4("body 1: high-restitution gummy; body 2: movable physical light orb");
    }

    void start() {
        bool expected = false;
        if (!running_.compare_exchange_strong(expected, true)) return;
        thread_ = std::thread([this] { loopV4(); });
    }

    void resetPhysics() {
        std::lock_guard<std::mutex> lock(bodyMutex_);
        initBodiesLocked();
        configureSceneLocked();
        grabbed_ = -1;
        grabTargetVelocity_ = {};
        logV4Unlocked("physics reset · v4 material lab");
    }

    void grabStart(float nx, float ny) {
        std::lock_guard<std::mutex> lock(bodyMutex_);
        Vec3 ro, rd;
        screenRay(nx, ny, ro, rd);
        float best = 1e9f;
        int bestIndex = -1;
        for (int i = 0; i < activeBodies(); ++i) {
            const Body& body = bodies_[i];
            Vec3 oc = ro - body.p;
            float b = dot(oc, rd);
            float c = dot(oc, oc) - body.r * body.r;
            float disc = b * b - c;
            if (disc < 0.0f) continue;
            float t = -b - std::sqrt(disc);
            if (t > 0.02f && t < best) {
                best = t;
                bestIndex = i;
            }
        }
        grabbed_ = bestIndex;
        grabDepth_ = best;
        if (grabbed_ >= 0) {
            grabTarget_ = ro + rd * best;
            grabPrevTarget_ = grabTarget_;
            grabTargetVelocity_ = {};
            grabLastUpdate_ = Clock::now();
            logV4Unlocked(std::string("grab body ") + std::to_string(grabbed_) +
                          (grabbed_ == 2 ? " · LIGHT" : grabbed_ == 1 ? " · GUMMY" : ""));
        }
    }

    void grabMove(float nx, float ny) {
        std::lock_guard<std::mutex> lock(bodyMutex_);
        if (grabbed_ < 0) return;
        Vec3 ro, rd;
        screenRay(nx, ny, ro, rd);
        Vec3 next = ro + rd * grabDepth_;
        auto now = Clock::now();
        float dt = std::chrono::duration<float>(now - grabLastUpdate_).count();
        if (dt > 0.0005f) {
            Vec3 instant = (next - grabPrevTarget_) / dt;
            float speed = len(instant);
            if (speed > 18.0f) instant = instant * (18.0f / speed);
            grabTargetVelocity_ = grabTargetVelocity_ * 0.72f + instant * 0.28f;
        }
        grabPrevTarget_ = next;
        grabTarget_ = next;
        grabLastUpdate_ = now;
    }

    void grabEnd() {
        std::lock_guard<std::mutex> lock(bodyMutex_);
        if (grabbed_ >= 0 && grabbed_ < static_cast<int>(bodies_.size())) {
            // Preserve the user's release velocity instead of dropping the spring target.
            bodies_[grabbed_].v += grabTargetVelocity_ * 0.72f;
            float speed = len(bodies_[grabbed_].v);
            if (speed > 22.0f) bodies_[grabbed_].v = bodies_[grabbed_].v * (22.0f / speed);
        }
        grabbed_ = -1;
        grabTargetVelocity_ = {};
    }

private:
    void configureScene() {
        std::lock_guard<std::mutex> lock(bodyMutex_);
        configureSceneLocked();
    }

    void configureSceneLocked() {
        if (bodies_.size() < 3) return;
        // Brushed steel hero sphere.
        bodies_[0].restitution = 0.34f;
        bodies_[0].friction = 0.34f;

        // The gummy is intentionally lively: high restitution, low sliding friction,
        // soft visual deformation driven by real collision impulses.
        bodies_[1].restitution = 0.94f;
        bodies_[1].friction = 0.12f;
        bodies_[1].mass = std::max(0.65f, bodies_[1].mass * 0.72f);
        bodies_[1].gummy = true;

        // Warm light controller sphere: still a real rigid body so light motion and
        // collisions are visible. Physics can be disabled to park it in mid-air.
        bodies_[2].restitution = 0.55f;
        bodies_[2].friction = 0.38f;
    }

    void logV4(const std::string& s) {
        __android_log_print(ANDROID_LOG_INFO, kTag, "%s", s.c_str());
        std::lock_guard<std::mutex> lock(logMutex_);
        logV4Unlocked(s);
    }

    void logV4Unlocked(const std::string& s) {
        logs_.push_back(s);
        if (logs_.size() > 120) logs_.erase(logs_.begin(), logs_.begin() + (logs_.size() - 120));
    }

    void collideFloor(Body& b, float h) {
        const bool inside = std::abs(b.p.x) <= kStudioHalfExtent + b.r &&
                            std::abs(b.p.z) <= kStudioHalfExtent + b.r;
        if (!inside) return;
        float penetration = kFloorY - (b.p.y - b.r);
        if (penetration <= 0.0f) return;

        b.p.y += penetration;
        Vec3 normal{0, 1, 0};
        float vn = dot(b.v, normal);
        float impact = std::max(0.0f, -vn);
        if (vn < 0.0f) {
            float jn = -(1.0f + b.restitution) * vn;
            b.v += normal * jn;
        }

        Vec3 tangent{b.v.x, 0.0f, b.v.z};
        float tangentSpeed = len(tangent);
        if (tangentSpeed > 1e-5f) {
            float maxDrop = 9.81f * b.friction * h;
            float newSpeed = std::max(0.0f, tangentSpeed - maxDrop);
            Vec3 tdir = tangent / tangentSpeed;
            b.v.x = tdir.x * newSpeed;
            b.v.z = tdir.z * newSpeed;
        }

        if (b.gummy && impact > 0.08f) b.deformV += impact * 0.34f;
    }

    void collideCube(Body& b) {
        Vec3 closest{
            std::clamp(b.p.x, -1.0f, 1.0f),
            std::clamp(b.p.y, -1.0f, 1.0f),
            std::clamp(b.p.z, -1.0f, 1.0f)
        };
        Vec3 d = b.p - closest;
        float dist = len(d);
        if (dist >= b.r) return;

        Vec3 n;
        if (dist > 1e-5f) {
            n = d / dist;
        } else {
            Vec3 a{std::abs(b.p.x), std::abs(b.p.y), std::abs(b.p.z)};
            if (a.x >= a.y && a.x >= a.z) n = {b.p.x >= 0 ? 1.0f : -1.0f, 0, 0};
            else if (a.y >= a.z) n = {0, b.p.y >= 0 ? 1.0f : -1.0f, 0};
            else n = {0, 0, b.p.z >= 0 ? 1.0f : -1.0f};
            dist = 0.0f;
        }

        float penetration = b.r - dist;
        b.p += n * penetration;
        float vn = dot(b.v, n);
        if (vn < 0.0f) {
            float impulse = -(1.0f + b.restitution) * vn;
            b.v += n * impulse;
            Vec3 tangent = b.v - n * dot(b.v, n);
            b.v -= tangent * std::min(0.72f, b.friction * 0.55f);
            if (b.gummy) b.deformV += std::abs(impulse) * 0.22f;
        }
    }

    void solveSpherePair(Body& a, Body& b) {
        Vec3 d = b.p - a.p;
        float dist = len(d);
        float radius = a.r + b.r;
        if (dist >= radius || dist < 1e-6f) return;

        Vec3 n = d / dist;
        float invA = 1.0f / std::max(a.mass, 0.1f);
        float invB = 1.0f / std::max(b.mass, 0.1f);
        float invSum = invA + invB;
        float penetration = radius - dist;
        const float slop = 0.0015f;
        float correction = std::max(0.0f, penetration - slop) * 0.82f;
        a.p -= n * (correction * invA / invSum);
        b.p += n * (correction * invB / invSum);

        Vec3 rel = b.v - a.v;
        float vn = dot(rel, n);
        if (vn >= 0.0f) return;

        float restitution = std::min(a.restitution, b.restitution);
        float j = -(1.0f + restitution) * vn / invSum;
        Vec3 impulse = n * j;
        a.v -= impulse * invA;
        b.v += impulse * invB;

        Vec3 tangent = rel - n * vn;
        float tl = len(tangent);
        if (tl > 1e-5f) {
            Vec3 t = tangent / tl;
            float jt = -dot(rel, t) / invSum;
            float mu = std::sqrt(std::max(0.0f, a.friction * b.friction));
            jt = std::clamp(jt, -j * mu, j * mu);
            Vec3 fi = t * jt;
            a.v -= fi * invA;
            b.v += fi * invB;
        }

        if (a.gummy) a.deformV += std::abs(j) * invA * 0.16f;
        if (b.gummy) b.deformV += std::abs(j) * invB * 0.16f;
    }

    void physicsStepV4(float dt) {
        if (!physics_.load()) return;
        std::lock_guard<std::mutex> lock(bodyMutex_);
        const int n = activeBodies();
        if (n <= 0) return;

        const int substeps = 5;
        const float h = std::min(dt, 0.025f) / float(substeps);
        for (int sub = 0; sub < substeps; ++sub) {
            for (int i = 0; i < n; ++i) {
                Body& b = bodies_[i];
                b.v.y -= 9.81f * h;

                if (i == grabbed_) {
                    Vec3 error = grabTarget_ - b.p;
                    Vec3 desiredVel = grabTargetVelocity_;
                    // Stable spring-damper manipulation: responsive without teleporting.
                    Vec3 accel = error * 78.0f + (desiredVel - b.v) * 15.5f;
                    float al = len(accel);
                    if (al > 125.0f) accel = accel * (125.0f / al);
                    b.v += accel * h;
                }

                b.p += b.v * h;
                collideFloor(b, h);
                collideCube(b);
            }

            // Two sequential-impulse iterations materially reduce overlap/jitter while
            // staying cheap for the benchmark's small real-time scene.
            for (int iteration = 0; iteration < 2; ++iteration) {
                for (int i = 0; i < n; ++i) {
                    for (int j = i + 1; j < n; ++j) solveSpherePair(bodies_[i], bodies_[j]);
                }
            }

            for (int i = 0; i < n; ++i) {
                Body& b = bodies_[i];
                if (!b.gummy) continue;
                // Underdamped oscillator gives a visible squash/rebound while conserving
                // the sphere's rendered volume. The impulse is produced by real contacts.
                constexpr float spring = 72.0f;
                constexpr float damping = 6.2f;
                b.deformV += (-spring * b.deform - damping * b.deformV) * h;
                b.deform += b.deformV * h;
                b.deform = std::clamp(b.deform, -0.08f, 0.14f);
            }
        }
    }

    void loopV4() {
        try {
            setStatus("Vulkan Studio v4 initializing…");
            initVulkan();
            logV4("hybrid raster + ray-query material lab ready");
            logV4("floor collider matches 48x48 studio surface; objects can fall beyond it");
            auto last = Clock::now();
            auto stat = last;
            auto startTime = last;
            auto telemetry = last;
            uint32_t frames = 0;

            while (running_) {
                auto now = Clock::now();
                float dt = std::chrono::duration<float>(now - last).count();
                last = now;
                physicsStepV4(dt);
                updateBodyGpu();
                float time = std::chrono::duration<float>(now - startTime).count();
                draw(time);
                ++frames;

                float elapsed = std::chrono::duration<float>(now - stat).count();
                if (elapsed >= 0.5f) {
                    float fps = frames / elapsed;
                    bool rt = rtSupported_ && requestedRt_.load() && quality_.load() >= 48;
                    std::ostringstream out;
                    out.setf(std::ios::fixed);
                    out.precision(1);
                    out << "Vulkan Studio v4 · " << gpuName_ << " · " << fps
                        << " FPS · GPU " << gpuMs_ << " ms\n";
                    out << "Q" << quality_.load() << " · "
                        << (stress_.load() ? "STRESS" : "QUALITY")
                        << " · Physics " << (physics_.load() ? "ON" : "OFF")
                        << " · RT " << (rt ? "RAY QUERY" : "OFF") << "\n";
                    out << "view " << visibleW_.load() << "x" << visibleH_.load()
                        << " · buffer " << extent_.width << "x" << extent_.height
                        << " · " << rotName(preTransform_)
                        << " · solver 5x/2i";
                    setStatus(out.str());
                    frames = 0;
                    stat = now;
                }

                if (std::chrono::duration<float>(now - telemetry).count() >= 2.0f) {
                    bool rt = rtSupported_ && requestedRt_.load() && quality_.load() >= 48;
                    std::ostringstream l;
                    l.setf(std::ios::fixed);
                    l.precision(2);
                    l << "telemetry · GPU " << gpuMs_ << " ms · bodies " << activeBodies()
                      << " · RT " << (rt ? "on" : "off")
                      << " · grabbed " << grabbed_;
                    logV4(l.str());
                    telemetry = now;
                }
            }
        } catch (const std::exception& e) {
            setStatus(std::string("ERROR: Vulkan Studio v4: ") + e.what());
            logV4(std::string("ERROR: ") + e.what());
        }
        cleanup();
    }

    Vec3 grabPrevTarget_{};
    Vec3 grabTargetVelocity_{};
    Clock::time_point grabLastUpdate_ = Clock::now();
};

VulkanStudioV4* ptrV4(jlong h) {
    return reinterpret_cast<VulkanStudioV4*>(static_cast<intptr_t>(h));
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeCreate(JNIEnv* e, jobject, jobject surface) {
    ANativeWindow* w = ANativeWindow_fromSurface(e, surface);
    if (!w) return 0;
    try {
        auto* renderer = new VulkanStudioV4(w);
        renderer->start();
        return static_cast<jlong>(reinterpret_cast<intptr_t>(renderer));
    } catch (...) {
        ANativeWindow_release(w);
        return 0;
    }
}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeDestroy(JNIEnv*, jobject, jlong h){delete ptrV4(h);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeResize(JNIEnv*, jobject, jlong h, jint w, jint he){if(auto*r=ptrV4(h))r->resize(w,he);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeRotate(JNIEnv*, jobject, jlong h, jfloat y, jfloat p){if(auto*r=ptrV4(h))r->rotate(y,p);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeZoom(JNIEnv*, jobject, jlong h, jfloat s){if(auto*r=ptrV4(h))r->zoom(s);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeSetStress(JNIEnv*, jobject, jlong h, jboolean v){if(auto*r=ptrV4(h))r->setStress(v);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeSetPhysics(JNIEnv*, jobject, jlong h, jboolean v){if(auto*r=ptrV4(h))r->setPhysics(v);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeSetRt(JNIEnv*, jobject, jlong h, jboolean v){if(auto*r=ptrV4(h))r->setRt(v);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeSetQuality(JNIEnv*, jobject, jlong h, jint q){if(auto*r=ptrV4(h))r->setQuality(q);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeResetPhysics(JNIEnv*, jobject, jlong h){if(auto*r=ptrV4(h))r->resetPhysics();}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabStart(JNIEnv*, jobject, jlong h, jfloat x, jfloat y){if(auto*r=ptrV4(h))r->grabStart(x,y);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabMove(JNIEnv*, jobject, jlong h, jfloat x, jfloat y){if(auto*r=ptrV4(h))r->grabMove(x,y);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabEnd(JNIEnv*, jobject, jlong h){if(auto*r=ptrV4(h))r->grabEnd();}
extern "C" JNIEXPORT jstring JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeStatus(JNIEnv* e, jobject, jlong h){std::string s=ptrV4(h)?ptrV4(h)->status():"renderer stopped";return e->NewStringUTF(s.c_str());}
extern "C" JNIEXPORT jstring JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeLogs(JNIEnv* e, jobject, jlong h){std::string s=ptrV4(h)?ptrV4(h)->logs():"";return e->NewStringUTF(s.c_str());}