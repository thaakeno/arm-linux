#pragma once
#include <cstdint>
#include "../game/Level.h"

namespace bounce {
struct BallState {
    Vec3 pos{kSpawn};
    Vec3 vel{};
    float radius=.58f;
    float squash=0.0f;
    float squashVel=0.0f;
    bool grounded=false;
    bool dead=false;
    bool won=false;
    uint16_t coinMask=0;
    int coins=0;
};

struct PhysicsEvents {
    bool bounced=false;
    bool jumped=false;
    bool dashed=false;
    bool collected=false;
    bool died=false;
    bool won=false;
    float impact=0.0f;
};

class BallPhysics {
public:
    void reset();
    PhysicsEvents step(float dt,float moveX,float moveZ,bool jump,bool dash);
    const BallState& state() const { return state_; }
private:
    BallState state_{};
    float dashCooldown_=0.0f;
    void collideAabb(const Aabb& box, PhysicsEvents& ev);
};
}
