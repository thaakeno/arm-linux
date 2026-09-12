        concreteAlb_=createTexture8("vulkan_v7/rough_concrete_diff_2k.jpg",VK_FORMAT_R8G8B8A8_SRGB);
        concreteNormal_=createTexture8("vulkan_v7/rough_concrete_nor_gl_2k.jpg",VK_FORMAT_R8G8B8A8_UNORM);
        concreteArm_=createTexture8("vulkan_v7/rough_concrete_arm_2k.jpg",VK_FORMAT_R8G8B8A8_UNORM);
        concreteHeight_=createTexture8("vulkan_v7/rough_concrete_disp_2k.png",VK_FORMAT_R8G8B8A8_UNORM);
        concreteHeightCpu_=loadHeight("vulkan_v7/rough_concrete_disp_2k.png");
        darkAlb_=createTexture8("vulkan_v7/dark_rock_diff_2k.jpg",VK_FORMAT_R8G8B8A8_SRGB);
        darkNormal_=createTexture8("vulkan_v7/dark_rock_nor_gl_2k.jpg",VK_FORMAT_R8G8B8A8_UNORM);
        darkArm_=createTexture8("vulkan_v7/dark_rock_arm_2k.jpg",VK_FORMAT_R8G8B8A8_UNORM);
        darkHeight_=createTexture8("vulkan_v7/dark_rock_disp_2k.png",VK_FORMAT_R8G8B8A8_UNORM);
        darkHeightCpu_=loadHeight("vulkan_v7/dark_rock_disp_2k.png");
        environment_=createTextureHdr("vulkan_v7/evening_museum_courtyard_1k.hdr");
        logV7("assets · layered wet stone + rough concrete/rock + worn body materials loaded");
    }

    int activeBodiesV7()const{
        if(!stress_.load())return int(kV7QualityBodies);
        int extras=std::clamp(6+(quality_.load()*12)/100,6,18);
        return std::min(int(kV7MaxRtBodies),int(kV7QualityBodies)+extras);
    }
    void configureBodiesV7(){std::lock_guard<std::mutex>lock(bodyMutex_);configureBodiesLockedV7();}
    void configureBodiesLockedV7(){
        if(bodies_.size()<kV7QualityBodies)return;
        auto setup=[&](int i,Vec3 p,float r,int mat,float rest,float fric,float density,bool gummy=false){
            Body&b=bodies_[i];b.p=p;b.v={};b.r=r;b.material=mat;b.restitution=rest;b.friction=fric;b.gummy=gummy;b.deform=b.deformV=0;
            b.mass=std::max(.18f,4.18879f*r*r*r*density);deformDir_[i]={0,1,0};sleepTimer_[i]=0;
        };
        setup(0,{-2.15f,kFloorY+.68f,-.28f},.68f,2,.025f,.66f,19.5f,false); // dense worn steel
        setup(1,{ .55f,kFloorY+.72f,.30f},.72f,3,.80f,.68f,1.08f,true);        // high-rebound opaque gummy rubber
        setup(2,{-.45f,2.55f,-2.05f},.28f,4,.02f,.45f,.70f,false);             // physical emissive orb
        setup(3,{ 2.15f,kFloorY+.64f,-.10f},.64f,7,.36f,.78f,1.22f,false);     // used rubber ball
        setup(4,{ 3.05f,kFloorY+.60f,1.55f},.60f,8,.0f,1.14f,1.16f,true);      // adhesive viscoelastic slime
        for(int i=5;i<kBodyCount;++i){
            int n=i-5,gx=n%6,gz=n/6;float r=.22f+.020f*float((n*5)%4);
            int mat=(n%4==0)?2:7;float density=mat==2?8.5f:1.25f;
            setup(i,{(float(gx)-2.5f)*1.05f,kFloorY+r+.10f*float(n%3),(float(gz)-1.5f)*1.02f},r,mat,.16f,.58f,density,false);
        }
        omega_.fill({});spin_.fill(0);sleepTimer_.fill(0);physicsAccumulator_=0;
    }

    float floorSurfaceYV7(float x,float z)const{
        float gx=std::clamp(x/14.10f+.5f,0.0f,1.0f),gz=std::clamp(z/14.10f+.5f,0.0f,1.0f);
        float u=gx*3.20f,v=gz*3.20f;float h=sampleHeight(floorHeightCpu_,u,v)-.5f;
        return kFloorY+h*.058f;
    }
    Vec3 clampInsideV7(Vec3 p,float r)const{
        p.x=std::clamp(p.x,-kV7RoomHalf+r,kV7RoomHalf-r);
        p.z=std::clamp(p.z,-kV7RoomHalf+r,kV7RoomHalf-r);
        p.y=std::clamp(p.y,floorSurfaceYV7(p.x,p.z)+r,4.55f-r);
        return p;
    }

    float supportRadiusV7(const Body&b,int i,Vec3 n)const{
        if(!b.gummy)return b.r;
        float maxC=b.material==8?.32f:.30f;
        float c=std::clamp(b.deform,-.020f,maxC);Vec3 d=deformDir_[i];float dl=len(d);if(dl<1e-5f)d={0,1,0};else d=d/dl;
        float minAlong=b.material==8?.64f:.68f;
        float along=std::clamp(1.0f-c,minAlong,1.020f),perp=1.0f/std::sqrt(along);float nd=std::clamp(dot(n,d),-1.0f,1.0f);float nd2=nd*nd;
        return b.r*std::sqrt(along*along*nd2+perp*perp*(1.0f-nd2));
    }

    static Vec3 heroFacePointV7(int face,float u,float vv,float h){
        float a=u*2.0f-1.0f,b=vv*2.0f-1.0f;Vec3 unit{};
        if(face==0)unit={a,b,1};else if(face==1)unit={-a,b,-1};else if(face==2)unit={1,b,-a};else if(face==3)unit={-1,b,a};else if(face==4)unit={a,1,-b};else unit={a,-1,b};
        Vec3 he{1.22f,1.06f,1.22f},inner{1.125f,.965f,1.125f};Vec3 raw{unit.x*he.x,unit.y*he.y,unit.z*he.z};
        Vec3 c{std::clamp(raw.x,-inner.x,inner.x),std::clamp(raw.y,-inner.y,inner.y),std::clamp(raw.z,-inner.z,inner.z)};Vec3 d=raw-c;Vec3 n=len(d)>1e-5f?norm(d):norm(unit);
        float edgeDist=1.0f-std::max(std::abs(a),std::abs(b));float seam=std::clamp(edgeDist/.085f,0.0f,1.0f);seam=seam*seam*(3.0f-2.0f*seam);
        return c+n*(.095f+h*.060f*seam);
    }
    std::vector<Vec3>heroTrianglesV7(){std::vector<Vec3>v;v.reserve(kV7HeroVerts);auto point=[&](int face,float u,float vv){float su=u*1.28f+float(face)*.173f,sv=vv*1.28f+float(face)*.071f;float tex=sampleHeight(concreteHeightCpu_,su,sv);return heroFacePointV7(face,u,vv,tex-.5f);};for(int f=0;f<6;++f)for(uint32_t y=0;y<kV7HeroGrid;++y)for(uint32_t x=0;x<kV7HeroGrid;++x){float u0=float(x)/kV7HeroGrid,u1=float(x+1)/kV7HeroGrid,v0=float(y)/kV7HeroGrid,v1=float(y+1)/kV7HeroGrid;Vec3 a=point(f,u0,v0),b=point(f,u1,v0),c=point(f,u1,v1),d=point(f,u0,v1);v.insert(v.end(),{a,b,c,a,c,d});}return v;}
    std::vector<Vec3>floorTrianglesV7(){std::vector<Vec3>v;v.reserve(kV7FloorVerts);auto point=[&](float gx,float gy){float u=gx*3.20f,vv=gy*3.20f;float h=sampleHeight(floorHeightCpu_,u,vv)-.5f;return Vec3{(gx*2.0f-1.0f)*7.05f,kFloorY+h*.058f,(gy*2.0f-1.0f)*7.05f};};for(uint32_t y=0;y<kV7FloorGrid;++y)for(uint32_t x=0;x<kV7FloorGrid;++x){float x0=float(x)/kV7FloorGrid,x1=float(x+1)/kV7FloorGrid,y0=float(y)/kV7FloorGrid,y1=float(y+1)/kV7FloorGrid;Vec3 a=point(x0,y0),b=point(x1,y0),c=point(x1,y1),d=point(x0,y1);v.insert(v.end(),{a,b,c,a,c,d});}return v;}

    float restitutionThresholdV7(const Body&b)const{
        if(b.material==8)return 1.75f;
        if(b.material==3)return .42f;
        if(b.material==7)return .62f;
        if(b.material==2)return 1.20f;
        return .90f;
    }
    void applyPlaneContactV7(Body&b,int i,Vec3 n,float pen,float contactR=-1.0f){
        if(pen<=0)return;if(contactR<=0)contactR=supportRadiusV7(b,i,n);b.p+=n*pen;float invM=1.0f/std::max(.08f,b.mass),invI=1.0f/std::max(.01f,.4f*b.mass*b.r*b.r);Vec3 r=n*(-contactR),rv=b.v+cross(omega_[i],r);float vn=dot(rv,n),jn=0;
        if(vn<0){float e=(std::abs(vn)<restitutionThresholdV7(b))?0.0f:b.restitution;jn=-(1.0f+e)*vn/invM;Vec3 J=n*jn;b.v+=J*invM;omega_[i]+=cross(r,J)*invI;}
        rv=b.v+cross(omega_[i],r);Vec3 tv=rv-n*dot(rv,n);float tl=len(tv);if(tl>1e-4f){Vec3 t=tv/tl;Vec3 rx=cross(r,t);float denom=invM+dot(cross(rx*invI,r),t);float jt=-dot(rv,t)/std::max(denom,.001f);float maxF=std::max(.010f,std::abs(jn))*b.friction;jt=std::clamp(jt,-maxF,maxF);Vec3 F=t*jt;b.v+=F*invM;omega_[i]+=cross(r,F)*invI;}
        float impactSpeed=std::max(0.0f,-vn);if(b.gummy&&impactSpeed>(b.material==8?1.10f:.48f)){
            deformDir_[i]=n;float gain=b.material==8?.052f:.120f;b.deformV+=std::min(b.material==8?.58f:1.55f,impactSpeed*gain);
        }
        sleepTimer_[i]=0;
    }
    void collideRoomV7(Body&b,int i){
        Vec3 nx{1,0,0},px{-1,0,0},nz{0,0,1},pz{0,0,-1},ny{0,1,0},py{0,-1,0};float rx=supportRadiusV7(b,i,nx),rpx=supportRadiusV7(b,i,px),rz=supportRadiusV7(b,i,nz),rpz=supportRadiusV7(b,i,pz),ry=supportRadiusV7(b,i,ny),rpy=supportRadiusV7(b,i,py);
        applyPlaneContactV7(b,i,nx,(-kV7RoomHalf+rx)-b.p.x,rx);applyPlaneContactV7(b,i,px,b.p.x-(kV7RoomHalf-rpx),rpx);applyPlaneContactV7(b,i,nz,(-kV7RoomHalf+rz)-b.p.z,rz);applyPlaneContactV7(b,i,pz,b.p.z-(kV7RoomHalf-rpz),rpz);float floorY=floorSurfaceYV7(b.p.x,b.p.z);float floorPen=floorY-(b.p.y-ry);applyPlaneContactV7(b,i,ny,floorPen,ry);applyPlaneContactV7(b,i,py,(b.p.y+rpy)-4.55f,rpy);
        float settle=b.material==8?.30f:(b.material==3?.10f:(b.material==7?.15f:.24f));if(floorPen>-.006f&&std::abs(b.v.y)<settle)b.v.y=0;
    }
    void collideHeroV7(Body&b,int i){
        Vec3 lo{-1.22f,-1.06f,-1.22f},hi{1.22f,1.06f,1.22f};Vec3 cp{std::clamp(b.p.x,lo.x,hi.x),std::clamp(b.p.y,lo.y,hi.y),std::clamp(b.p.z,lo.z,hi.z)};Vec3 d=b.p-cp;float dl=len(d);Vec3 n=dl>1e-5f?d/dl:norm(Vec3{b.p.x,.15f,b.p.z});float rr=supportRadiusV7(b,i,n);if(dl>=rr)return;applyPlaneContactV7(b,i,n,rr-dl,rr);
    }
    void collideArchitectureV7(Body&b,int i){
        for(int k=3;k<18;++k){if(k>=8&&k<=11)continue;Vec3 c,s;bool light;archInfoV7(k,c,s,light);Vec3 lo=c-s,hi=c+s;Vec3 cp{std::clamp(b.p.x,lo.x,hi.x),std::clamp(b.p.y,lo.y,hi.y),std::clamp(b.p.z,lo.z,hi.z)};Vec3 d=b.p-cp;float dl=len(d);if(dl>1e-5f){Vec3 n=d/dl;float rr=supportRadiusV7(b,i,n);if(dl<rr)applyPlaneContactV7(b,i,n,rr-dl,rr);continue;}bool inside=b.p.x>lo.x&&b.p.x<hi.x&&b.p.y>lo.y&&b.p.y<hi.y&&b.p.z>lo.z&&b.p.z<hi.z;if(!inside)continue;float dx=std::min(b.p.x-lo.x,hi.x-b.p.x),dy=std::min(b.p.y-lo.y,hi.y-b.p.y),dz=std::min(b.p.z-lo.z,hi.z-b.p.z);Vec3 n{};float m=dx;n={(b.p.x-c.x)>=0?1.f:-1.f,0,0};if(dy<m){m=dy;n={0,(b.p.y-c.y)>=0?1.f:-1.f,0};}if(dz<m){m=dz;n={0,0,(b.p.z-c.z)>=0?1.f:-1.f};}float rr=supportRadiusV7(b,i,n);applyPlaneContactV7(b,i,n,rr+m,rr);}
    }
    void solvePairV7(int ia,int ib){
        Body&a=bodies_[ia],&b=bodies_[ib];Vec3 d=b.p-a.p;float dist=len(d);if(dist<1e-6f)return;Vec3 n=d/dist;float raLen=supportRadiusV7(a,ia,n),rbLen=supportRadiusV7(b,ib,n*(-1.0f)),rr=raLen+rbLen;if(dist>=rr)return;float ima=1/std::max(.08f,a.mass),imb=1/std::max(.08f,b.mass),sum=ima+imb,pen=rr-dist;Vec3 corr=n*(std::max(0.f,pen-.0010f)*.72f/sum);a.p-=corr*ima;b.p+=corr*imb;
        Vec3 ra=n*raLen,rb=n*(-rbLen);Vec3 va=a.v+cross(omega_[ia],ra),vb=b.v+cross(omega_[ib],rb),rel=vb-va;float vn=dot(rel,n);if(vn>=0)return;float threshold=std::max(restitutionThresholdV7(a),restitutionThresholdV7(b));float e=std::abs(vn)<threshold?0.0f:std::min(a.restitution,b.restitution),jn=-(1+e)*vn/sum;Vec3 J=n*jn;a.v-=J*ima;b.v+=J*imb;
        float iia=1/std::max(.01f,.4f*a.mass*a.r*a.r),iib=1/std::max(.01f,.4f*b.mass*b.r*b.r);rel=(b.v+cross(omega_[ib],rb))-(a.v+cross(omega_[ia],ra));Vec3 tv=rel-n*dot(rel,n);float tl=len(tv);if(tl>1e-4f){Vec3 t=tv/tl;Vec3 rax=cross(ra,t),rbx=cross(rb,t);float denom=sum+dot(cross(rax*iia,ra),t)+dot(cross(rbx*iib,rb),t);float jt=-dot(rel,t)/std::max(denom,.001f);float mu=std::sqrt(std::max(0.f,a.friction*b.friction));jt=std::clamp(jt,-jn*mu,jn*mu);Vec3 F=t*jt;a.v-=F*ima;b.v+=F*imb;omega_[ia]-=cross(ra,F)*iia;omega_[ib]+=cross(rb,F)*iib;}
        float impact=std::abs(vn);if(a.gummy&&impact>(a.material==8?1.10f:.48f)){deformDir_[ia]=n;a.deformV+=impact*(a.material==8?.050f:.115f);}if(b.gummy&&impact>(b.material==8?1.10f:.48f)){deformDir_[ib]=n*(-1.0f);b.deformV+=impact*(b.material==8?.050f:.115f);}
        sleepTimer_[ia]=sleepTimer_[ib]=0;
    }
    void physicsStepV7(float dt){
        std::lock_guard<std::mutex>lock(bodyMutex_);if(!physics_.load())return;int n=activeBodiesV7();physicsAccumulator_+=std::clamp(dt,0.0f,.066f);constexpr float h=1.0f/240.0f;int steps=std::min(16,int(physicsAccumulator_/h));if(steps<=0)return;physicsAccumulator_-=float(steps)*h;
        for(int s=0;s<steps;++s){
            for(int i=0;i<n;++i){
                Body&b=bodies_[i];
                if(i==grabbed_){
                    Vec3 desired=(grabTarget_-b.p)/h;float ds=len(desired);if(ds>14.0f)desired=desired*(14.0f/ds);b.v=desired*.62f+grabTargetVelocity_*.38f;sleepTimer_[i]=0;
                }else b.v.y-=9.81f*h;
                float drag=b.material==8?.52f:(b.material==3?.018f:.008f);b.v=b.v*std::exp(-drag*h);omega_[i]=omega_[i]*std::exp(-(b.material==8?1.65f:(b.gummy?.22f:.10f))*h);b.p+=b.v*h;
                collideRoomV7(b,i);collideHeroV7(b,i);collideArchitectureV7(b,i);
                float ry=supportRadiusV7(b,i,{0,1,0});float floorY=floorSurfaceYV7(b.p.x,b.p.z);bool grounded=b.p.y-ry<=floorY+.009f;
                if(grounded&&i!=grabbed_){
                    float speed=len(b.v),spin=len(omega_[i]);
                    float rolling=b.material==8?7.8f:(b.material==3?.40f:(b.material==7?.72f:.18f));float roll=std::exp(-rolling*h);b.v.x*=roll;b.v.z*=roll;
                    if(b.material==8&&std::abs(b.v.y)<.36f)b.v.y=0;
                    float sleepSpeed=b.material==8?.115f:.075f,sleepSpin=b.material==8?.20f:.15f;
                    if(speed<sleepSpeed&&spin<sleepSpin){sleepTimer_[i]+=h;if(sleepTimer_[i]>(b.material==8?.12f:.24f)){b.v={};omega_[i]={};}}
                    else sleepTimer_[i]=0;
                }else if(i!=grabbed_)sleepTimer_[i]=0;
                spin_[i]+=len(omega_[i])*h;
            }
            for(int it=0;it<5;++it)for(int i=0;i<n;++i)for(int j=i+1;j<n;++j)solvePairV7(i,j);
            for(int i=0;i<n;++i)if(bodies_[i].gummy){
                Body&b=bodies_[i];float ry=supportRadiusV7(b,i,{0,1,0});float floorY=floorSurfaceYV7(b.p.x,b.p.z);bool grounded=b.p.y-ry<=floorY+.012f;
                float target=(b.material==8&&grounded&&i!=grabbed_)?0.045f:0.0f;float spring=b.material==8?16.0f:58.0f,damping=b.material==8?18.0f:8.8f,maxDef=b.material==8?.32f:.30f;
                b.deformV+=(spring*(target-b.deform)-damping*b.deformV)*h;b.deform+=b.deformV*h;b.deform=std::clamp(b.deform,-.020f,maxDef);
                if(b.material==8&&grounded&&i!=grabbed_)b.deformV*=std::exp(-5.5f*h);
                float newRy=supportRadiusV7(b,i,{0,1,0});if(grounded&&i!=grabbed_){float restY=floorY+newRy;if(b.p.y<restY)b.p.y=restY;if(std::abs(b.v.y)<.10f)b.v.y=0;}
                if(sleepTimer_[i]>(b.material==8?.12f:.24f)){b.deform=target;b.deformV=0;deformDir_[i]={0,1,0};}
                else if(std::abs(b.deform-target)<.0008f&&std::abs(b.deformV)<.006f)deformDir_[i]={0,1,0};
            }
        }
    }
    void updateBodyGpuV7(){if(!bodyMapped_)return;std::lock_guard<std::mutex>lock(bodyMutex_);auto*out=reinterpret_cast<BodyGpu*>(bodyMapped_);for(int i=0;i<kBodyCount;++i){const Body&b=bodies_[i];Vec3 d=deformDir_[i];float dl=len(d);if(dl<1e-4f)d={0,1,0};else d=d/dl;out[i]={{b.p.x,b.p.y,b.p.z,b.r},{b.v.x,b.v.y,b.v.z,b.mass},{b.deform,d.x,d.y,d.z},{b.material,b.gummy?1:0,b.material==8?1:0,0}};}}

    void screenRayV7(float nx,float ny,Vec3&ro,Vec3&rd)const{float yaw=yaw_.load(),pitch=pitch_.load(),cp=std::cos(pitch);ro=cameraDistance_.load()*Vec3{cp*std::sin(yaw),std::sin(pitch),cp*std::cos(yaw)}+Vec3{0,.38f,1.70f};Vec3 target{0,-.15f,-.15f};Vec3 f=norm(target-ro),r=norm(cross(f,{0,1,0})),u=norm(cross(r,f));float x=nx*2-1,y=1-ny*2,aspect=float(std::max(1,visibleW_.load()))/float(std::max(1,visibleH_.load()));float tanHalf=std::tan(34.0f*kPi/360.0f);rd=norm(f+r*(x*aspect*tanHalf)+u*(y*tanHalf));}
    void grabStartImpl(float nx,float ny,bool forceLight){std::lock_guard<std::mutex>lock(bodyMutex_);Vec3 ro,rd;screenRayV7(nx,ny,ro,rd);int bestIndex=forceLight?2:-1;float bestHit=1e9f;if(forceLight){Vec3 to=bodies_[2].p-ro;bestHit=std::max(.65f,dot(to,rd));}else{for(int i=0;i<activeBodiesV7();++i){if(i==2)continue;const Body&b=bodies_[i];float pickR=b.r*1.16f+.030f;Vec3 oc=ro-b.p;float qb=dot(oc,rd),cc=dot(oc,oc)-pickR*pickR,disc=qb*qb-cc;if(disc<0)continue;float t=-qb-std::sqrt(disc);if(t>.02f&&t<bestHit){bestHit=t;bestIndex=i;}}}grabbed_=bestIndex;if(grabbed_>=0){Body&b=bodies_[grabbed_];float centerDepth=dot(b.p-ro,rd);grabDepth_=std::max(centerDepth,b.r*2.35f+.55f);grabTarget_=ro+rd*grabDepth_;grabPrevTarget_=grabTarget_;grabTargetVelocity_={};grabLastUpdate_=Clock::now();sleepTimer_[grabbed_]=0;const char*name=grabbed_==2?"MOVABLE LIGHT":grabbed_==1?"GUMMY":grabbed_==0?"HEAVY METAL":grabbed_==4?"SLIME":"NORMAL BALL";logV7Unlocked(std::string("grab ")+name);}}

    std::array<Vec3,kBodyCount>deformDir_{};
    std::array<float,kBodyCount>sleepTimer_{};
    float physicsAccumulator_=0.0f;
