#include "vessel_jolt_world.h"

#include <Jolt/Jolt.h>
#include <Jolt/RegisterTypes.h>
#include <Jolt/Core/Factory.h>
#include <Jolt/Core/TempAllocator.h>
#include <Jolt/Core/JobSystemThreadPool.h>
#include <Jolt/Physics/PhysicsSystem.h>
#include <Jolt/Physics/Body/BodyCreationSettings.h>
#include <Jolt/Physics/Collision/Shape/BoxShape.h>
#include <Jolt/Physics/Collision/Shape/SphereShape.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <mutex>
#include <thread>

using namespace JPH;

namespace {
namespace Layers {
static constexpr ObjectLayer Static = 0;
static constexpr ObjectLayer Dynamic = 1;
static constexpr uint Count = 2;
}
namespace BPLayers {
static constexpr BroadPhaseLayer Static(0);
static constexpr BroadPhaseLayer Dynamic(1);
static constexpr uint Count = 2;
}

class BPInterface final : public BroadPhaseLayerInterface {
public:
    BPInterface() { map_[Layers::Static] = BPLayers::Static; map_[Layers::Dynamic] = BPLayers::Dynamic; }
    uint GetNumBroadPhaseLayers() const override { return BPLayers::Count; }
    BroadPhaseLayer GetBroadPhaseLayer(ObjectLayer l) const override { return map_[l]; }
private:
    BroadPhaseLayer map_[Layers::Count];
};
class ObjVsBP final : public ObjectVsBroadPhaseLayerFilter {
public:
    bool ShouldCollide(ObjectLayer a, BroadPhaseLayer b) const override {
        return a == Layers::Dynamic || b == BPLayers::Dynamic;
    }
};
class ObjPair final : public ObjectLayerPairFilter {
public:
    bool ShouldCollide(ObjectLayer a, ObjectLayer b) const override {
        return a == Layers::Dynamic || b == Layers::Dynamic;
    }
};

void ensureJoltRegistered() {
    static std::once_flag once;
    std::call_once(once, [] {
        RegisterDefaultAllocator();
        Factory::sInstance = new Factory();
        RegisterTypes();
    });
}

struct BoxDef { float x,y,z,hx,hy,hz; };
constexpr std::array<BoxDef, 18> kArena = {{
    {-9.0f,-0.69f,-7.0f, 2.8f,0.32f,2.4f},
    { 8.0f,-0.49f,-6.0f, 3.1f,0.52f,2.5f},
    { 0.0f,-0.20f, 8.0f, 4.2f,0.82f,2.3f},
    {-10.0f,-0.89f, 3.0f, 1.7f,0.12f,1.2f},
    {-10.0f,-0.68f, 4.3f, 1.7f,0.20f,1.2f},
    {-10.0f,-0.43f, 5.6f, 1.7f,0.25f,1.2f},
    {-10.0f,-0.12f, 6.9f, 1.7f,0.31f,1.2f},
    {-10.0f, 0.25f, 8.2f, 1.7f,0.37f,1.2f},
    { 11.0f,-0.45f, 7.0f, 1.3f,0.56f,1.3f},
    { 11.0f, 0.70f, 7.0f, 0.95f,0.58f,0.95f},
    { 6.0f,-0.70f, 2.5f, 1.25f,0.31f,1.25f},
    { 6.0f,-0.02f, 2.5f, 0.90f,0.36f,0.90f},
    {-4.0f,-0.78f,-11.0f, 3.0f,0.23f,1.3f},
    {-4.0f,-0.31f,-11.0f, 2.2f,0.24f,1.0f},
    {-4.0f, 0.14f,-11.0f, 1.35f,0.21f,0.75f},
    { 4.0f,-0.62f,-13.0f, 4.0f,0.40f,1.8f},
    {13.0f,-0.60f,-2.0f, 2.5f,0.42f,2.5f},
    {-14.0f,-0.55f,-3.0f,2.8f,0.47f,2.2f}
}};
}

struct VesselJoltWorld::Impl {
    BPInterface bp;
    ObjVsBP objVsBP;
    ObjPair objPair;
    PhysicsSystem physics;
    TempAllocatorImpl temp{16 * 1024 * 1024};
    JobSystemThreadPool jobs{cMaxPhysicsJobs, cMaxPhysicsBarriers, std::max(1u, std::min(3u, std::thread::hardware_concurrency() > 1 ? std::thread::hardware_concurrency() - 1 : 1u))};
    std::array<BodyID,4> ids{};
    std::array<float,4> radius{{.68f,.72f,.28f,.64f}};
    std::array<float,4> mass{{24.0f,1.15f,.70f,1.35f}};
    std::array<float,4> restitution{{.03f,.82f,.38f,.76f}};
    std::array<float,4> friction{{.68f,.54f,.45f,.78f}};
    bool enabled=true;
    int dragged=-1;
    bool ready=false;

    Impl() {
        ensureJoltRegistered();
        physics.Init(512, 0, 2048, 2048, bp, objVsBP, objPair);
        physics.SetGravity(Vec3(0,-9.81f,0));
        reset();
        ready=true;
    }
    ~Impl() {
        BodyInterface &bi=physics.GetBodyInterface();
        for (BodyID id: ids) if (!id.IsInvalid()) { bi.RemoveBody(id); bi.DestroyBody(id); }
    }

    void addStaticBox(const BoxDef &b) {
        BodyCreationSettings s(new BoxShape(Vec3(b.hx,b.hy,b.hz)), RVec3(b.x,b.y,b.z), Quat::sIdentity(), EMotionType::Static, Layers::Static);
        BodyID id=physics.GetBodyInterface().CreateAndAddBody(s,EActivation::DontActivate);
        (void)id;
    }
    void makeDynamic(int i, RVec3 p) {
        BodyCreationSettings s(new SphereShape(radius[i]), p, Quat::sIdentity(), EMotionType::Dynamic, Layers::Dynamic);
        s.mMotionQuality=EMotionQuality::LinearCast;
        s.mRestitution=restitution[i];
        s.mFriction=friction[i];
        s.mLinearDamping=(i==0?0.08f:0.12f);
        s.mAngularDamping=0.18f;
        s.mOverrideMassProperties=EOverrideMassProperties::CalculateInertia;
        s.mMassPropertiesOverride.mMass=mass[i];
        ids[i]=physics.GetBodyInterface().CreateAndAddBody(s,EActivation::Activate);
    }
    void reset() {
        BodyInterface &bi=physics.GetBodyInterface();
        for (BodyID &id: ids) if (!id.IsInvalid()) { bi.RemoveBody(id); bi.DestroyBody(id); id=BodyID(); }
        // The playground is deliberately open: a large floor plus spaced platforms, no enclosing green box.
        BodyCreationSettings floor(new BoxShape(Vec3(30.0f,.20f,30.0f)),RVec3(0,-1.21f,0),Quat::sIdentity(),EMotionType::Static,Layers::Static);
        bi.CreateAndAddBody(floor,EActivation::DontActivate);
        for (const BoxDef &b:kArena) addStaticBox(b);
        makeDynamic(0,RVec3(-2.35f,-.30f,0.0f));
        makeDynamic(1,RVec3( .25f,-.25f,.45f));
        makeDynamic(2,RVec3(-.60f, 2.60f,-2.10f));
        makeDynamic(3,RVec3( 2.20f,-.34f,-.10f));
        physics.OptimizeBroadPhase();
        dragged=-1;
    }
    void recreate(int i) {
        if(i<0||i>=4)return;
        BodyInterface &bi=physics.GetBodyInterface();
        RVec3 p(0,2,0);Vec3 v=Vec3::sZero();
        if(!ids[i].IsInvalid()){p=bi.GetCenterOfMassPosition(ids[i]);v=bi.GetLinearVelocity(ids[i]);bi.RemoveBody(ids[i]);bi.DestroyBody(ids[i]);}
        makeDynamic(i,p);bi.SetLinearVelocity(ids[i],v);
    }
};

VesselJoltWorld::VesselJoltWorld():impl_(std::make_unique<Impl>()){}
VesselJoltWorld::~VesselJoltWorld()=default;
bool VesselJoltWorld::valid()const{return impl_&&impl_->ready;}
void VesselJoltWorld::reset(){if(impl_)impl_->reset();}
void VesselJoltWorld::setEnabled(bool e){if(impl_)impl_->enabled=e;}
void VesselJoltWorld::step(float dt){if(!impl_||!impl_->enabled)return;float h=std::clamp(dt,0.0f,1.0f/30.0f);impl_->physics.Update(h,1,&impl_->temp,&impl_->jobs);}
void VesselJoltWorld::setBodyProperties(int i,float r,float m,float re,float fr){if(!impl_||i<0||i>=4)return;bool shape=std::abs(impl_->radius[i]-r)>.001f||std::abs(impl_->mass[i]-m)>.01f;impl_->radius[i]=std::clamp(r,.12f,1.4f);impl_->mass[i]=std::clamp(m,.12f,80.0f);impl_->restitution[i]=std::clamp(re,0.0f,.98f);impl_->friction[i]=std::clamp(fr,.02f,1.2f);if(shape)impl_->recreate(i);BodyInterface &bi=impl_->physics.GetBodyInterface();bi.SetRestitution(impl_->ids[i],impl_->restitution[i]);bi.SetFriction(impl_->ids[i],impl_->friction[i]);}
void VesselJoltWorld::setBodyPose(int i,float x,float y,float z){if(!impl_||i<0||i>=4)return;impl_->physics.GetBodyInterface().SetPositionAndRotation(impl_->ids[i],RVec3(x,y,z),Quat::sIdentity(),EActivation::Activate);}
void VesselJoltWorld::setBodyVelocity(int i,float x,float y,float z){if(!impl_||i<0||i>=4)return;impl_->physics.GetBodyInterface().SetLinearVelocity(impl_->ids[i],Vec3(x,y,z));}
void VesselJoltWorld::beginDrag(int i){if(!impl_||i<0||i>=4)return;impl_->dragged=i;impl_->physics.GetBodyInterface().ActivateBody(impl_->ids[i]);}
void VesselJoltWorld::dragTo(float x,float y,float z,float vx,float vy,float vz){if(!impl_||impl_->dragged<0)return;int i=impl_->dragged;BodyInterface &bi=impl_->physics.GetBodyInterface();RVec3 p=bi.GetCenterOfMassPosition(impl_->ids[i]);Vec3 err(float(x-p.GetX()),float(y-p.GetY()),float(z-p.GetZ()));Vec3 target(vx,vy,vz);Vec3 vel=err*16.0f+target*.35f;float speed=vel.Length();if(speed>12.0f)vel*=12.0f/speed;bi.SetLinearVelocity(impl_->ids[i],vel);bi.ActivateBody(impl_->ids[i]);}
void VesselJoltWorld::endDrag(float vx,float vy,float vz){if(!impl_||impl_->dragged<0)return;Vec3 v(vx,vy,vz);float speed=v.Length();if(speed>11.0f)v*=11.0f/speed;impl_->physics.GetBodyInterface().SetLinearVelocity(impl_->ids[impl_->dragged],v);impl_->dragged=-1;}
VesselJoltBodyState VesselJoltWorld::state(int i)const{VesselJoltBodyState s;if(!impl_||i<0||i>=4)return s;const BodyInterface &bi=impl_->physics.GetBodyInterface();RVec3 p=bi.GetCenterOfMassPosition(impl_->ids[i]);Vec3 v=bi.GetLinearVelocity(impl_->ids[i]);Quat q=bi.GetRotation(impl_->ids[i]);s.px=float(p.GetX());s.py=float(p.GetY());s.pz=float(p.GetZ());s.vx=v.GetX();s.vy=v.GetY();s.vz=v.GetZ();s.qx=q.GetX();s.qy=q.GetY();s.qz=q.GetZ();s.qw=q.GetW();return s;}
