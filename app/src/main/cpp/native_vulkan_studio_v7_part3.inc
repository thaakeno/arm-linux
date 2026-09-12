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
        logV7("assets · cobblestone + rough concrete + dark rock + visible courtyard HDR loaded");
    }

    int activeBodiesV7()const{
        if(!stress_.load())return int(kV7QualityBodies);
        int extras=std::clamp(8+(quality_.load()*16)/100,8,24);
        return std::min(int(kV7MaxRtBodies),int(kV7QualityBodies)+extras);
    }
    void configureBodiesV7(){std::lock_guard<std::mutex>lock(bodyMutex_);configureBodiesLockedV7();}
    void configureBodiesLockedV7(){
        if(bodies_.size()<kV7QualityBodies)return;
        auto setup=[&](int i,Vec3 p,float r,int mat,float rest,float fric,float density,bool gummy=false){
            Body&b=bodies_[i];b.p=p;b.v={};b.r=r;b.material=mat;b.restitution=rest;b.friction=fric;b.gummy=gummy;b.deform=b.deformV=0;
            b.mass=std::max(.18f,4.18879f*r*r*r*density);
        };
        setup(0,{-2.25f,kFloorY+.66f,-.25f},.66f,2,.20f,.26f,6.8f,false);      // chrome
        setup(1,{ .65f,kFloorY+.73f,.35f},.73f,3,.56f,.30f,1.08f,true);       // gummy resin
        setup(2,{-.35f,2.65f,-2.15f},.30f,4,.18f,.30f,1.0f,false);            // physical light
        setup(3,{ 2.30f,kFloorY+.64f,-.20f},.64f,7,.28f,.34f,1.48f,false);    // porcelain
        setup(4,{-.65f,kFloorY+.62f,1.80f},.62f,8,.24f,.28f,5.4f,false);      // copper
        setup(5,{ 2.00f,kFloorY+.58f,1.72f},.58f,9,.32f,.34f,1.34f,false);    // frosted glass
        for(int i=6;i<kBodyCount;++i){
            int n=i-6,gx=n%6,gz=n/6;float r=.23f+.022f*float((n*5)%4);
            int mat=(n%7==0)?8:((n%5==0)?6:((n%3==0)?7:2));
            float density=mat==8?5.0f:(mat==2?6.2f:1.6f);
            setup(i,{(float(gx)-2.5f)*1.12f,kFloorY+r+.12f*float(n%3),(float(gz)-1.6f)*1.04f},r,mat,.24f+.035f*float(n%3),.40f,density,false);
        }
        omega_.fill({});spin_.fill(0);
    }
    Vec3 clampInsideV7(Vec3 p,float r)const{
        p.x=std::clamp(p.x,-kV7RoomHalf+r,kV7RoomHalf-r);
        p.z=std::clamp(p.z,-kV7RoomHalf+r,kV7RoomHalf-r);
        p.y=std::clamp(p.y,kFloorY+r,4.55f-r);
        return p;
    }

    static Vec3 heroFacePointV7(int face,float u,float vv,float h){
        float a=u*2.0f-1.0f,b=vv*2.0f-1.0f;Vec3 unit{};
        if(face==0)unit={a,b,1};else if(face==1)unit={-a,b,-1};else if(face==2)unit={1,b,-a};else if(face==3)unit={-1,b,a};else if(face==4)unit={a,1,-b};else unit={a,-1,b};
        Vec3 he{1.22f,1.06f,1.22f},inner{1.125f,.965f,1.125f};Vec3 raw{unit.x*he.x,unit.y*he.y,unit.z*he.z};
        Vec3 c{std::clamp(raw.x,-inner.x,inner.x),std::clamp(raw.y,-inner.y,inner.y),std::clamp(raw.z,-inner.z,inner.z)};Vec3 d=raw-c;Vec3 n=len(d)>1e-5f?norm(d):norm(unit);
        float edgeDist=1.0f-std::max(std::abs(a),std::abs(b));float edge=1.0f-std::clamp((edgeDist-.015f)/.215f,0.0f,1.0f);float chips=-std::max(0.0f,.42f-(h+.5f))*edge*.22f;
        return c+n*(.095f+h*(.055f+edge*.045f)+chips);
    }
    std::vector<Vec3>heroTrianglesV7(){
        std::vector<Vec3>v;v.reserve(kV7HeroVerts);
        auto point=[&](int face,float u,float vv){float su=u*1.28f+float(face)*.173f,sv=vv*1.28f+float(face)*.071f;float tex=sampleHeight(concreteHeightCpu_,su,sv);return heroFacePointV7(face,u,vv,tex-.5f);};
        for(int f=0;f<6;++f)for(uint32_t y=0;y<kV7HeroGrid;++y)for(uint32_t x=0;x<kV7HeroGrid;++x){float u0=float(x)/kV7HeroGrid,u1=float(x+1)/kV7HeroGrid,v0=float(y)/kV7HeroGrid,v1=float(y+1)/kV7HeroGrid;Vec3 a=point(f,u0,v0),b=point(f,u1,v0),c=point(f,u1,v1),d=point(f,u0,v1);v.insert(v.end(),{a,b,c,a,c,d});}
        return v;
    }
    std::vector<Vec3>floorTrianglesV7(){
        std::vector<Vec3>v;v.reserve(kV7FloorVerts);
        auto point=[&](float gx,float gy){float u=gx*5.8f,vv=gy*5.8f;float h=sampleHeight(floorHeightCpu_,u,vv)-.5f;return Vec3{(gx*2.0f-1.0f)*7.05f,kFloorY+h*.065f,(gy*2.0f-1.0f)*7.05f};};
        for(uint32_t y=0;y<kV7FloorGrid;++y)for(uint32_t x=0;x<kV7FloorGrid;++x){float x0=float(x)/kV7FloorGrid,x1=float(x+1)/kV7FloorGrid,y0=float(y)/kV7FloorGrid,y1=float(y+1)/kV7FloorGrid;Vec3 a=point(x0,y0),b=point(x1,y0),c=point(x1,y1),d=point(x0,y1);v.insert(v.end(),{a,b,c,a,c,d});}
        return v;
    }

    void applyPlaneContactV7(Body&b,int i,Vec3 n,float pen){
        if(pen<=0)return;b.p+=n*pen;float invM=1.0f/std::max(.08f,b.mass),invI=1.0f/std::max(.01f,.4f*b.mass*b.r*b.r);Vec3 r=n*(-b.r),rv=b.v+cross(omega_[i],r);float vn=dot(rv,n),jn=0;
        if(vn<0){jn=-(1.0f+b.restitution)*vn/invM;Vec3 J=n*jn;b.v+=J*invM;omega_[i]+=cross(r,J)*invI;}
        rv=b.v+cross(omega_[i],r);Vec3 tv=rv-n*dot(rv,n);float tl=len(tv);if(tl>1e-4f){Vec3 t=tv/tl;Vec3 rx=cross(r,t);float denom=invM+dot(cross(rx*invI,r),t);float jt=-dot(rv,t)/std::max(denom,.001f);jt=std::clamp(jt,-std::abs(jn)*b.friction,std::abs(jn)*b.friction);Vec3 F=t*jt;b.v+=F*invM;omega_[i]+=cross(r,F)*invI;}
        if(b.gummy&&std::abs(jn)>.02f)b.deformV+=std::min(1.2f,std::abs(jn)*invM*.085f);
    }
    void collideRoomV7(Body&b,int i){
        applyPlaneContactV7(b,i,{1,0,0},(-kV7RoomHalf+b.r)-b.p.x);applyPlaneContactV7(b,i,{-1,0,0},b.p.x-(kV7RoomHalf-b.r));
        applyPlaneContactV7(b,i,{0,0,1},(-kV7RoomHalf+b.r)-b.p.z);applyPlaneContactV7(b,i,{0,0,-1},b.p.z-(kV7RoomHalf-b.r));
        applyPlaneContactV7(b,i,{0,1,0},kFloorY-(b.p.y-b.r));applyPlaneContactV7(b,i,{0,-1,0},(b.p.y+b.r)-4.55f);
    }
    void collideHeroV7(Body&b,int i){
        Vec3 lo{-1.22f,-1.06f,-1.22f},hi{1.22f,1.06f,1.22f};Vec3 cp{std::clamp(b.p.x,lo.x,hi.x),std::clamp(b.p.y,lo.y,hi.y),std::clamp(b.p.z,lo.z,hi.z)};Vec3 d=b.p-cp;float dl=len(d);if(dl>=b.r)return;
        Vec3 n=dl>1e-5f?d/dl:norm(Vec3{b.p.x,.15f,b.p.z});float pen=b.r-dl;b.p+=n*pen;float vn=dot(b.v,n);if(vn<0)b.v-=n*((1+b.restitution)*vn);Vec3 tangent=b.v-n*dot(b.v,n);b.v-=tangent*std::min(.34f,b.friction*.26f);if(b.gummy)b.deformV+=std::abs(vn)*.16f;
    }
    void solvePairV7(int ia,int ib){
        Body&a=bodies_[ia],&b=bodies_[ib];Vec3 d=b.p-a.p;float dist=len(d),rr=a.r+b.r;if(dist>=rr||dist<1e-6f)return;Vec3 n=d/dist;float ima=1/std::max(.08f,a.mass),imb=1/std::max(.08f,b.mass),sum=ima+imb,pen=rr-dist;Vec3 corr=n*(std::max(0.f,pen-.0008f)*.70f/sum);a.p-=corr*ima;b.p+=corr*imb;
        Vec3 ra=n*a.r,rb=n*(-b.r);Vec3 va=a.v+cross(omega_[ia],ra),vb=b.v+cross(omega_[ib],rb),rel=vb-va;float vn=dot(rel,n);if(vn>=0)return;float e=std::min(a.restitution,b.restitution),jn=-(1+e)*vn/sum;Vec3 J=n*jn;a.v-=J*ima;b.v+=J*imb;
        float iia=1/std::max(.01f,.4f*a.mass*a.r*a.r),iib=1/std::max(.01f,.4f*b.mass*b.r*b.r);rel=(b.v+cross(omega_[ib],rb))-(a.v+cross(omega_[ia],ra));Vec3 tv=rel-n*dot(rel,n);float tl=len(tv);if(tl>1e-4f){Vec3 t=tv/tl;Vec3 rax=cross(ra,t),rbx=cross(rb,t);float denom=sum+dot(cross(rax*iia,ra),t)+dot(cross(rbx*iib,rb),t);float jt=-dot(rel,t)/std::max(denom,.001f);float mu=std::sqrt(std::max(0.f,a.friction*b.friction));jt=std::clamp(jt,-jn*mu,jn*mu);Vec3 F=t*jt;a.v-=F*ima;b.v+=F*imb;omega_[ia]-=cross(ra,F)*iia;omega_[ib]+=cross(rb,F)*iib;}
        if(a.gummy)a.deformV+=std::abs(jn)*ima*.075f;if(b.gummy)b.deformV+=std::abs(jn)*imb*.075f;
    }
    void physicsStepV7(float dt){
        std::lock_guard<std::mutex>lock(bodyMutex_);if(!physics_.load())return;int n=activeBodiesV7();const int sub=6;float h=std::min(dt,.020f)/float(sub);
        for(int s=0;s<sub;++s){
            for(int i=0;i<n;++i){Body&b=bodies_[i];b.v.y-=9.81f*h;if(i==grabbed_){Vec3 err=grabTarget_-b.p;Vec3 acc=err*50.0f+(grabTargetVelocity_-b.v)*14.5f;float al=len(acc);if(al>68)acc=acc*(68/al);b.v+=acc*h;}b.v=b.v*(1.0f-.032f*h);omega_[i]=omega_[i]*(1.0f-.18f*h);b.p+=b.v*h;collideRoomV7(b,i);collideHeroV7(b,i);spin_[i]+=len(omega_[i])*h;}
            for(int it=0;it<4;++it)for(int i=0;i<n;++i)for(int j=i+1;j<n;++j)solvePairV7(i,j);
            for(int i=0;i<n;++i)if(bodies_[i].gummy){Body&b=bodies_[i];constexpr float spring=38.0f,damping=7.4f;b.deformV+=(-spring*b.deform-damping*b.deformV)*h;b.deform+=b.deformV*h;b.deform=std::clamp(b.deform,-.08f,.18f);}
        }
    }
    void updateBodyGpuV7(){if(!bodyMapped_)return;std::lock_guard<std::mutex>lock(bodyMutex_);auto*out=reinterpret_cast<BodyGpu*>(bodyMapped_);for(int i=0;i<kBodyCount;++i){const Body&b=bodies_[i];out[i]={{b.p.x,b.p.y,b.p.z,b.r},{b.v.x,b.v.y,b.v.z,b.mass},{b.deform,spin_[i],len(omega_[i]),0},{b.material,b.gummy?1:0,0,0}};}}

    void screenRayV7(float nx,float ny,Vec3&ro,Vec3&rd)const{
        float yaw=yaw_.load(),pitch=pitch_.load(),cp=std::cos(pitch);ro=cameraDistance_.load()*Vec3{cp*std::sin(yaw),std::sin(pitch),cp*std::cos(yaw)}+Vec3{0,.38f,1.70f};Vec3 target{0,-.15f,-.15f};Vec3 f=norm(target-ro),r=norm(cross(f,{0,1,0})),u=norm(cross(r,f));float x=nx*2-1,y=1-ny*2,aspect=float(std::max(1,visibleW_.load()))/float(std::max(1,visibleH_.load()));float tanHalf=std::tan(34.0f*kPi/360.0f);rd=norm(f+r*(x*aspect*tanHalf)+u*(y*tanHalf));
    }
    void grabStartImpl(float nx,float ny,bool forceLight){
        std::lock_guard<std::mutex>lock(bodyMutex_);Vec3 ro,rd;screenRayV7(nx,ny,ro,rd);int bestIndex=forceLight?2:-1;float best=1e9f;if(forceLight){Vec3 to=bodies_[2].p-ro;best=std::max(.45f,dot(to,rd));}else{for(int i=0;i<activeBodiesV7();++i){const Body&b=bodies_[i];float pickR=b.r*1.22f+.045f;Vec3 oc=ro-b.p;float qb=dot(oc,rd),cc=dot(oc,oc)-pickR*pickR,disc=qb*qb-cc;if(disc<0)continue;float t=-qb-std::sqrt(disc);if(t>.02f&&t<best){best=t;bestIndex=i;}}}grabbed_=bestIndex;grabDepth_=best;if(grabbed_>=0){grabTarget_=ro+rd*best;grabPrevTarget_=grabTarget_;grabTargetVelocity_={};grabLastUpdate_=Clock::now();logV7Unlocked(std::string("grab ")+(grabbed_==2?"MOVABLE LIGHT":grabbed_==1?"GUMMY":"body "+std::to_string(grabbed_)));}}
