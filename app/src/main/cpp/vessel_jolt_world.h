#pragma once

#include <array>
#include <cstdint>
#include <memory>

struct VesselJoltBodyState {
    float px=0, py=0, pz=0;
    float vx=0, vy=0, vz=0;
    float qx=0, qy=0, qz=0, qw=1;
};

class VesselJoltWorld {
public:
    VesselJoltWorld();
    ~VesselJoltWorld();
    VesselJoltWorld(const VesselJoltWorld&) = delete;
    VesselJoltWorld& operator=(const VesselJoltWorld&) = delete;

    bool valid() const;
    void reset();
    void step(float dt);
    void setEnabled(bool enabled);
    void setBodyProperties(int index, float radius, float mass, float restitution, float friction);
    void setBodyPose(int index, float x, float y, float z);
    void setBodyVelocity(int index, float x, float y, float z);
    void beginDrag(int index);
    void dragTo(float x, float y, float z, float vx, float vy, float vz);
    void endDrag(float vx, float vy, float vz);
    VesselJoltBodyState state(int index) const;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
