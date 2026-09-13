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

void BallPhysics::reset(){ state_=BallState{}; dashCooldown_=0.0f; }

void BallPhysics::collideAabb(const Aabb& box, PhysicsEvents& ev){
    Vec3 lo{box.c.x-box.h.x,box.c.y-box.h.y,box.c.z-box.h.z};
    Vec3 hi{box.c.x+box.h.x,box.c.y+box.h.y,box.c.z+box.h.z};
    Vec3 q{std::clamp(state_.pos.x,lo.x,hi.x),std::clamp(state_.pos.y,lo.y,hi.y),std::clamp(state_.pos.z,lo.z,hi.z)};
    Vec3 d=sub(state_.pos,q);float dl=len(d);if(dl>=state_.radius)return;
    Vec3 n{};float penetration=0.0f;
    if(dl>1e-5f){n=mul(d,1.0f/dl);penetration=state_.radius-dl;}
    else {
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
        float impact=-vn; float restitution=(n.y>.55f?0.62f:0.38f);
        state_.vel=sub(state_.vel,mul(n,(1.0f+restitution)*vn));
        if(n.y>.55f){state_.grounded=true;float grip=0.86f;state_.vel.x*=grip;state_.vel.z*=grip;}
        if(impact>.55f){ev.bounced=true;ev.impact=std::max(ev.impact,impact);state_.squashVel+=std::min(2.8f,impact*.34f);}
    }
}

PhysicsEvents BallPhysics::step(float dt,float moveX,float moveZ,bool jump,bool dash){
    PhysicsEvents ev{};dt=std::clamp(dt,0.0f,.033f);dashCooldown_=std::max(0.0f,dashCooldown_-dt);
    float ml=std::sqrt(moveX*moveX+moveZ*moveZ);if(ml>1.0f){moveX/=ml;moveZ/=ml;}
    state_.grounded=false;
    float accel=state_.grounded?30.0f:18.0f; // grounded is refreshed during contacts; air steering still intentionally strong
    state_.vel.x+=moveX*accel*dt;state_.vel.z+=moveZ*accel*dt;
    float horizontal=std::sqrt(state_.vel.x*state_.vel.x+state_.vel.z*state_.vel.z);float maxSpeed=8.0f;
    if(horizontal>maxSpeed){float s=maxSpeed/horizontal;state_.vel.x*=s;state_.vel.z*=s;}
    if(jump && std::abs(state_.vel.y)<1.8f){state_.vel.y=8.0f;ev.jumped=true;state_.squashVel-=.8f;}
    if(dash&&dashCooldown_<=0.0f){
        Vec3 dir=ml>.1f?norm(Vec3{moveX,0,moveZ}):norm(Vec3{state_.vel.x,0,state_.vel.z});
        if(len(dir)<.2f)dir={0,0,-1};state_.vel.x+=dir.x*9.5f;state_.vel.z+=dir.z*9.5f;dashCooldown_=.72f;ev.dashed=true;
    }
    state_.vel.y-=18.5f*dt;state_.pos=add(state_.pos,mul(state_.vel,dt));
    for(const auto& p:kPlatforms)collideAabb(p,ev);

    // Bounce pad on the opening platform.
    if(std::abs(state_.pos.x-kBouncePad.x)<1.15f && std::abs(state_.pos.z-kBouncePad.z)<1.15f && state_.pos.y<1.45f && state_.vel.y<=1.0f){
        state_.vel.y=12.0f;ev.bounced=true;ev.impact=8.0f;state_.squashVel+=2.1f;
    }

    for(int i=0;i<10;i++) if((state_.coinMask&(1u<<i))==0 && dist(state_.pos,kCoins[i])<.95f){state_.coinMask|=(1u<<i);state_.coins++;ev.collected=true;}

    // Spike pit between the first and second major sections.
    if(state_.pos.z<-4.55f&&state_.pos.z>-5.65f&&std::abs(state_.pos.x)<2.5f&&state_.pos.y<.2f){state_.dead=true;ev.died=true;}
    if(state_.pos.y<-5.0f){state_.dead=true;ev.died=true;}
    if(dist(state_.pos,kGoal)<1.9f&&state_.coins>=10){state_.won=true;ev.won=true;}
    if(state_.dead){auto mask=state_.coinMask;int coins=state_.coins;reset();state_.coinMask=mask;state_.coins=coins;}

    // Volume-preserving squash/stretch spring.
    state_.squashVel+=(-52.0f*state_.squash-10.5f*state_.squashVel)*dt;state_.squash+=state_.squashVel*dt;
    state_.squash=std::clamp(state_.squash,-.18f,.38f);
    return ev;
}
}
