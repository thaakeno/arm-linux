#include "BallPhysics.h"
#include <algorithm>
#include <cmath>

namespace bounce {
namespace {
float dot(Vec3 a,Vec3 b){return a.x*b.x+a.y*b.y+a.z*b.z;}
Vec3 add(Vec3 a,Vec3 b){return {a.x+b.x,a.y+b.y,a.z+b.z};}
Vec3 sub(Vec3 a,Vec3 b){return {a.x-b.x,a.y-b.y,a.z-b.z};}
Vec3 mul(Vec3 a,float s){return {a.x*s,a.y*s,a.z*s};}
float len(Vec3 a){return std::sqrt(std::max(0.0f,dot(a,a)));}
Vec3 norm(Vec3 a){float l=len(a);return l>1e-5f?mul(a,1.0f/l):Vec3{0,1,0};}
float dist(Vec3 a,Vec3 b){return len(sub(a,b));}
}

void BallPhysics::reset(){state_=BallState{};dashCooldown_=0.0f;worldTime_=0.0f;}

void BallPhysics::collideAabb(const Aabb& box, PhysicsEvents& ev){
    Vec3 lo{box.c.x-box.h.x,box.c.y-box.h.y,box.c.z-box.h.z};
    Vec3 hi{box.c.x+box.h.x,box.c.y+box.h.y,box.c.z+box.h.z};
    Vec3 q{std::clamp(state_.pos.x,lo.x,hi.x),std::clamp(state_.pos.y,lo.y,hi.y),std::clamp(state_.pos.z,lo.z,hi.z)};
    Vec3 d=sub(state_.pos,q);float dl=len(d);if(dl>=state_.radius)return;
    Vec3 n{};float penetration=0.0f;
    if(dl>1e-5f){n=mul(d,1.0f/dl);penetration=state_.radius-dl;}
    else{
        float dx=std::min(state_.pos.x-lo.x,hi.x-state_.pos.x);
        float dy=std::min(state_.pos.y-lo.y,hi.y-state_.pos.y);
        float dz=std::min(state_.pos.z-lo.z,hi.z-state_.pos.z);
        if(dy<=dx&&dy<=dz)n={0,state_.pos.y>box.c.y?1.f:-1.f,0};
        else if(dx<=dz)n={state_.pos.x>box.c.x?1.f:-1.f,0,0};
        else n={0,0,state_.pos.z>box.c.z?1.f:-1.f};
        penetration=state_.radius+std::min(dx,std::min(dy,dz));
    }
    state_.pos=add(state_.pos,mul(n,penetration));
    float vn=dot(state_.vel,n);
    if(vn<0.0f){
        float impact=-vn;float restitution=(n.y>.55f?.66f:.32f);
        state_.vel=sub(state_.vel,mul(n,(1.0f+restitution)*vn));
        if(n.y>.55f){state_.grounded=true;state_.vel.x*=.87f;state_.vel.z*=.87f;}
        if(impact>.55f){ev.bounced=true;ev.impact=std::max(ev.impact,impact);state_.squashVel+=std::min(2.8f,impact*.34f);}
    }
}

static void supportRamp(BallState& s, PhysicsEvents& ev, float cx,float zc,float halfX,float halfZ,float lowY,float highY){
    if(std::abs(s.pos.x-cx)>halfX+s.radius*.45f||s.pos.z<zc-halfZ-s.radius*.3f||s.pos.z>zc+halfZ+s.radius*.3f)return;
    float t=std::clamp((zc+halfZ-s.pos.z)/(2.0f*halfZ),0.0f,1.0f);
    float top=lowY+(highY-lowY)*t;
    float bottom=s.pos.y-s.radius;
    if(bottom<top && bottom>top-.72f && s.vel.y<=2.0f){
        float impact=std::max(0.0f,-s.vel.y);
        s.pos.y=top+s.radius;
        if(s.vel.y<0.0f)s.vel.y=std::max(0.0f,-s.vel.y*.18f);
        s.grounded=true;s.vel.x*=.965f;s.vel.z*=.965f;
        if(impact>.8f){ev.bounced=true;ev.impact=std::max(ev.impact,impact);s.squashVel+=std::min(1.7f,impact*.22f);}
    }
}

PhysicsEvents BallPhysics::step(float dt,float moveX,float moveZ,bool jump,bool dash){
    PhysicsEvents ev{};dt=std::clamp(dt,0.0f,.025f);worldTime_+=dt;dashCooldown_=std::max(0.0f,dashCooldown_-dt);
    float ml=std::sqrt(moveX*moveX+moveZ*moveZ);if(ml>1.0f){moveX/=ml;moveZ/=ml;}
    const bool wasGrounded=state_.grounded;
    float accel=wasGrounded?27.0f:11.5f;
    state_.vel.x+=moveX*accel*dt;state_.vel.z+=moveZ*accel*dt;
    float horizontal=std::sqrt(state_.vel.x*state_.vel.x+state_.vel.z*state_.vel.z);float maxSpeed=7.2f;
    if(horizontal>maxSpeed){float s=maxSpeed/horizontal;state_.vel.x*=s;state_.vel.z*=s;}
    if(jump&&wasGrounded){state_.vel.y=7.8f;state_.grounded=false;ev.jumped=true;state_.squashVel-=.72f;}
    if(dash&&dashCooldown_<=0.0f){
        Vec3 dir=ml>.1f?norm(Vec3{moveX,0,moveZ}):norm(Vec3{state_.vel.x,0,state_.vel.z});
        if(len(dir)<.2f)dir={0,0,-1};state_.vel.x+=dir.x*8.4f;state_.vel.z+=dir.z*8.4f;dashCooldown_=.75f;ev.dashed=true;
    }

    state_.grounded=false;
    state_.vel.y-=18.0f*dt;state_.pos=add(state_.pos,mul(state_.vel,dt));
    for(const auto&p:kPlatforms)collideAabb(p,ev);

    // These are the same solid props visible in the authored scene.
    const Aabb crateA{{-3.35f,.48f,-.35f},{.58f,.58f,.58f},3,false,false};
    const Aabb crateB{{-2.05f,.48f,-.35f},{.58f,.58f,.58f},3,false,false};
    const Aabb crateC{{3.30f,.88f,-6.25f},{.60f,.60f,.60f},3,false,false};
    const Aabb moving{{3.25f+std::sin(worldTime_*1.10f)*.90f,1.46f,-10.0f},{.62f,.62f,.62f},3,false,false};
    collideAabb(crateA,ev);collideAabb(crateB,ev);collideAabb(crateC,ev);collideAabb(moving,ev);

    // Low side walls at the opening arena. They match the visible concrete boundaries.
    collideAabb(Aabb{{-5.65f,.65f,-1.2f},{.24f,1.05f,6.5f},0,false,false},ev);
    collideAabb(Aabb{{ 5.65f,.65f,-1.2f},{.24f,1.05f,6.5f},0,false,false},ev);

    // Real ramp support instead of letting the sphere phase through the visual wedge.
    supportRamp(state_,ev,.15f,-.85f,1.65f,1.35f,-.05f,.75f);
    supportRamp(state_,ev,-.70f,-8.0f,1.25f,1.10f,.78f,1.50f);

    if(std::abs(state_.pos.x-kBouncePad.x)<1.12f&&std::abs(state_.pos.z-kBouncePad.z)<1.08f&&state_.pos.y<1.55f&&state_.vel.y<=1.0f){
        state_.vel.y=11.5f;state_.grounded=false;ev.bounced=true;ev.impact=7.8f;state_.squashVel+=2.0f;
    }

    for(int i=0;i<10;i++)if((state_.coinMask&(1u<<i))==0&&dist(state_.pos,kCoins[i])<.88f){state_.coinMask|=(1u<<i);state_.coins++;ev.collected=true;}

    // Visible spike trench.
    if(state_.pos.z<-9.35f&&state_.pos.z>-10.65f&&state_.pos.x>-2.65f&&state_.pos.x<2.65f&&state_.pos.y<1.70f){state_.dead=true;ev.died=true;}
    if(state_.pos.y<-5.0f){state_.dead=true;ev.died=true;}
    if(dist(state_.pos,kGoal)<1.85f&&state_.coins>=10){state_.won=true;ev.won=true;}
    if(state_.dead){auto mask=state_.coinMask;int coins=state_.coins;reset();state_.coinMask=mask;state_.coins=coins;}

    state_.squashVel+=(-52.0f*state_.squash-10.5f*state_.squashVel)*dt;state_.squash+=state_.squashVel*dt;
    state_.squash=std::clamp(state_.squash,-.18f,.38f);
    return ev;
}
}
