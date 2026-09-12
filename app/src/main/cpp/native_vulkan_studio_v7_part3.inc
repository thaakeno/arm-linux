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
        logV7("assets · PBR stone/concrete/rock loaded; procedural worn body materials enabled");
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
            b.mass=std::max(.18f,4.18879f*r*r*r*density);deformDir_[i]={0,1,0};
        };
        setup(0,{-2.15f,kFloorY+.68f,-.28f},.68f,2,.07f,.48f,19.5f,false); // heavy worn steel
        setup(1,{ .55f,kFloorY+.72f,.30f},.72f,3,.76f,.62f,1.08f,true);      // opaque red gummy rubber
        setup(2,{-.45f,2.55f,-2.05f},.28f,4,.08f,.36f,.70f,false);           // physical emissive orb
        setup(3,{ 2.15f,kFloorY+.64f,-.10f},.64f,7,.38f,.72f,1.22f,false);   // normal used rubber ball
        setup(4,{ 3.05f,kFloorY+.60f,1.55f},.60f,8,.12f,.88f,1.05f,true);    // green viscoelastic slime
        for(int i=5;i<kBodyCount;++i){
            int n=i-5,gx=n%6,gz=n/6;float r=.22f+.020f*float((n*5)%4);
            int mat=(n%4==0)?2:7;float density=mat==2?8.5f:1.25f;
            setup(i,{(float(gx)-2.5f)*1.05f,kFloorY+r+.10f*float(n%3),(float(gz)-1.5f)*1.02f},r,mat,.20f,.46f,density,false);
        }
        omega_.fill({});spin_.fill(0);
    }
    Vec3 clampInsideV7(Vec3 p,float r)const{
        p.x=std::clamp(p.x,-kV7RoomHalf+r,kV7RoomHalf-r);
        p.z=std::clamp(p.z,-kV7RoomHalf+r,kV7RoomHalf-r);
        p.y=std::clamp(p.y,kFloorY+r,4.55f-r);
        return p;
    }

    float supportRadiusV7(const Body&b,int i,Vec3 n)const{
        if(!b.gummy)return b.r;
        float maxC=b.material==8?.42f:.31f;
        float c=std::clamp(b.deform,-.05f,maxC);Vec3 d=deformDir_[i];float dl=len(d);if(dl<1e-5f)d={0,1,0};else d=d/dl;
        float minAlong=b.material==8?.54f:.65f;
        float along=std::clamp(1.0f-c,minAlong,1.06f),perp=1.0f/std::sqrt(along);float nd=std::clamp(dot(n,d),-1.0f,1.0f);float nd2=nd*nd;
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
    std::vector<Vec3>floorTrianglesV7(){std::vector<Vec3>v;v.reserve(kV7FloorVerts);auto point=[&](float gx,float gy){float u=gx*5.8f,vv=gy*5.8f;float h=sampleHeight(floorHeightCpu_,u,vv)-.5f;return Vec3{(gx*2.0f-1.0f)*7.05f,kFloorY+h*.052f,(gy*2.0f-1.0f)*7.05f};};for(uint32_t y=0;y<kV7FloorGrid;++y)for(uint32_t x=0;x<kV7FloorGrid;++x){float x0=float(x)/kV7FloorGrid,x1=float(x+1)/kV7FloorGrid,y0=float(y)/kV7FloorGrid,y1=float(y+1)/kV7FloorGrid;Vec3 a=point(x0,y0),b=point(x1,y0),c=point(x1,y1),d=point(x0,y1);v.insert(v.end(),{a,b,c,a,c,d});}return v;}

    void applyPlaneContactV7(Body&b,int i,Vec3 n,float pen,float contactR=-1.0f){
        if(pen<=0)return;if(contactR<=0)contactR=supportRadiusV7(b,i,n);b.p+=n*pen;float invM=1.0f/std::max(.08f,b.mass),invI=1.0f/std::max(.01f,.4f*b.mass*b.r*b.r);Vec3 r=n*(-contactR),rv=b.v+cross(omega_[i],r);float vn=dot(rv,n),jn=0;
        if(vn<0){jn=-(1.0f+b.restitution)*vn/invM;Vec3 J=n*jn;b.v+=J*invM;omega_[i]+=cross(r,J)*invI;}
        rv=b.v+cross(omega_[i],r);Vec3 tv=rv-n*dot(rv,n);float tl=len(tv);if(tl>1e-4f){Vec3 t=tv/tl;Vec3 rx=cross(r,t);float denom=invM+dot(cross(rx*invI,r),t);float jt=-dot(rv,t)/std::max(denom,.001f);jt=std::clamp(jt,-std::abs(jn)*b.friction,std::abs(jn)*b.friction);Vec3 F=t*jt;b.v+=F*invM;omega_[i]+=cross(r,F)*invI;}
        if(b.gummy&&std::abs(jn)>.012f){deformDir_[i]=n;float gain=b.material==8?.20f:.15f;b.deformV+=std::min(b.material==8?2.8f:2.1f,std::abs(jn)*invM*gain);}
    }
    void collideRoomV7(Body&b,int i){
        Vec3 nx{1,0,0},px{-1,0,0},nz{0,0,1},pz{0,0,-1},ny{0,1,0},py{0,-1,0};float rx=supportRadiusV7(b,i,nx),rpx=supportRadiusV7(b,i,px),rz=supportRadiusV7(b,i,nz),rpz=supportRadiusV7(b,i,pz),ry=supportRadiusV7(b,i,ny),rpy=supportRadiusV7(b,i,py);
        applyPlaneContactV7(b,i,nx,(-kV7RoomHalf+rx)-b.p.x,rx);applyPlaneContactV7(b,i,px,b.p.x-(kV7RoomHalf-rpx),rpx);applyPlaneContactV7(b,i,nz,(-kV7RoomHalf+rz)-b.p.z,rz);applyPlaneContactV7(b,i,pz,b.p.z-(kV7RoomHalf-rpz),rpz);applyPlaneContactV7(b,i,ny,kFloorY-(b.p.y-ry),ry);applyPlaneContactV7(b,i,py,(b.p.y+rpy)-4.55f,rpy);
    }
    void collideHeroV7(Body&b,int i){
        Vec3 lo{-1.22f,-1.06f,-1.22f},hi{1.22f,1.06f,1.22f};Vec3 cp{std::clamp(b.p.x,lo.x,hi.x),std::clamp(b.p.y,lo.y,hi.y),std::clamp(b.p.z,lo.z,hi.z)};Vec3 d=b.p-cp;float dl=len(d);Vec3 n=dl>1e-5f?d/dl:norm(Vec3{b.p.x,.15f,b.p.z});float rr=supportRadiusV7(b,i,n);if(dl>=rr)return;applyPlaneContactV7(b,i,n,rr-dl,rr);
    }
    void collideArchitectureV7(Body&b,int i){
        for(int k=3;k<18;++k){if(k>=8&&k<=11)continue;Vec3 c,s;bool light;archInfoV7(k,c,s,light);Vec3 lo=c-s,hi=c+s;Vec3 cp{std::clamp(b.p.x,lo.x,hi.x),std::clamp(b.p.y,lo.y,hi.y),std::clamp(b.p.z,lo.z,hi.z)};Vec3 d=b.p-cp;float dl=len(d);if(dl>1e-5f){Vec3 n=d/dl;float rr=supportRadiusV7(b,i,n);if(dl<rr)applyPlaneContactV7(b,i,n,rr-dl,rr);continue;}bool inside=b.p.x>lo.x&&b.p.x<hi.x&&b.p.y>lo.y&&b.p.y<hi.y&&b.p.z>lo.z&&b.p.z<hi.z;if(!inside)continue;float dx=std::min(b.p.x-lo.x,hi.x-b.p.x),dy=std::min(b.p.y-lo.y,hi.y-b.p.y),dz=std::min(b.p.z-lo.z,hi.z-b.p.z);Vec3 n{};float m=dx;n={(b.p.x-c.x)>=0?1.f:-1.f,0,0};if(dy<m){m=dy;n={0,(b.p.y-c.y)>=0?1.f:-1.f,0};}if(dz<m){m=dz;n={0,0,(b.p.z-c.z)>=0?1.f:-1.f};}float rr=supportRadiusV7(b,i,n);applyPlaneContactV7(b,i,n,rr+m,rr);}
    }
    void solvePairV7(int ia,int ib){
        Body&a=bodies_[ia],&b=bodies_[ib];Vec3 d=b.p-a.p;float dist=len(d);if(dist<1e-6f)return;Vec3 n=d/dist;float raLen=supportRadiusV7(a,ia,n),rbLen=supportRadiusV7(b,ib,n*(-1.0f)),rr=raLen+rbLen;if(dist>=rr)return;float ima=1/std::max(.08f,a.mass),imb=1/std::max(.08f,b.mass),sum=ima+imb,pen=rr-dist;Vec3 corr=n*(std::max(0.f,pen-.0005f)*.88f/sum);a.p-=corr*ima;b.p+=corr*imb;
        Vec3 ra=n*raLen,rb=n*(-rbLen);Vec3 va=a.v+cross(omega_[ia],ra),vb=b.v+cross(omega_[ib],rb),rel=vb-va;float vn=dot(rel,n);if(vn>=0)return;float e=std::min(a.restitution,b.restitution),jn=-(1+e)*vn/sum;Vec3 J=n*jn;a.v-=J*ima;b.v+=J*imb;
        float iia=1/std::max(.01f,.4f*a.mass*a.r*a.r),iib=1/std::max(.01f,.4f*b.mass*b.r*b.r);rel=(b.v+cross(omega_[ib],rb))-(a.v+cross(omega_[ia],ra));Vec3 tv=rel-n*dot(rel,n);float tl=len(tv);if(tl>1e-4f){Vec3 t=tv/tl;Vec3 rax=cross(ra,t),rbx=cross(rb,t);float denom=sum+dot(cross(rax*iia,ra),t)+dot(cross(rbx*iib,rb),t);float jt=-dot(rel,t)/std::max(denom,.001f);float mu=std::sqrt(std::max(0.f,a.friction*b.friction));jt=std::clamp(jt,-jn*mu,jn*mu);Vec3 F=t*jt;a.v-=F*ima;b.v+=F*imb;omega_[ia]-=cross(ra,F)*iia;omega_[ib]+=cross(rb,F)*iib;}
        if(a.gummy){deformDir_[ia]=n;a.deformV+=std::abs(jn)*ima*(a.material==8?.18f:.14f);}if(b.gummy){deformDir_[ib]=n*(-1.0f);b.deformV+=std::abs(jn)*imb*(b.material==8?.18f:.14f);}
    }
    void physicsStepV7(float dt){
        std::lock_guard<std::mutex>lock(bodyMutex_);if(!physics_.load())return;int n=activeBodiesV7();float simDt=std::clamp(dt,0.0f,.050f);int sub=std::clamp(int(std::ceil(simDt/.0036f)),4,14);float h=simDt/float(sub);
        for(int s=0;s<sub;++s){
            for(int i=0;i<n;++i){Body&b=bodies_[i];b.v.y-=9.81f*h;if(i==grabbed_){Vec3 err=grabTarget_-b.p;float kp=b.material==8?48.0f:72.0f, kd=b.material==8?13.0f:17.5f;Vec3 acc=err*kp+(grabTargetVelocity_-b.v)*kd;float al=len(acc);if(al>92)acc=acc*(92/al);b.v+=acc*h;}float drag=b.material==8?.12f:.018f;b.v=b.v*std::exp(-drag*h);omega_[i]=omega_[i]*std::exp(-(b.material==8?.55f:.12f)*h);b.p+=b.v*h;collideRoomV7(b,i);collideHeroV7(b,i);collideArchitectureV7(b,i);if(b.material==8){float ry=supportRadiusV7(b,i,{0,1,0});if(b.p.y-ry<kFloorY+.018f&&std::abs(b.v.y)<1.8f){float stick=std::exp(-2.6f*h);b.v.x*=stick;b.v.z*=stick;}}spin_[i]+=len(omega_[i])*h;}
            for(int it=0;it<6;++it)for(int i=0;i<n;++i)for(int j=i+1;j<n;++j)solvePairV7(i,j);
            for(int i=0;i<n;++i)if(bodies_[i].gummy){Body&b=bodies_[i];float spring=b.material==8?18.0f:47.0f,damping=b.material==8?7.8f:6.9f,maxDef=b.material==8?.42f:.31f;b.deformV+=(-spring*b.deform-damping*b.deformV)*h;b.deform+=b.deformV*h;b.deform=std::clamp(b.deform,-.05f,maxDef);if(std::abs(b.deform)<.0015f&&std::abs(b.deformV)<.012f)deformDir_[i]={0,1,0};}
        }
    }
    void updateBodyGpuV7(){if(!bodyMapped_)return;std::lock_guard<std::mutex>lock(bodyMutex_);auto*out=reinterpret_cast<BodyGpu*>(bodyMapped_);for(int i=0;i<kBodyCount;++i){const Body&b=bodies_[i];Vec3 d=deformDir_[i];float dl=len(d);if(dl<1e-4f)d={0,1,0};else d=d/dl;out[i]={{b.p.x,b.p.y,b.p.z,b.r},{b.v.x,b.v.y,b.v.z,b.mass},{b.deform,d.x,d.y,d.z},{b.material,b.gummy?1:0,b.material==8?1:0,0}};}}

    void screenRayV7(float nx,float ny,Vec3&ro,Vec3&rd)const{float yaw=yaw_.load(),pitch=pitch_.load(),cp=std::cos(pitch);ro=cameraDistance_.load()*Vec3{cp*std::sin(yaw),std::sin(pitch),cp*std::cos(yaw)}+Vec3{0,.38f,1.70f};Vec3 target{0,-.15f,-.15f};Vec3 f=norm(target-ro),r=norm(cross(f,{0,1,0})),u=norm(cross(r,f));float x=nx*2-1,y=1-ny*2,aspect=float(std::max(1,visibleW_.load()))/float(std::max(1,visibleH_.load()));float tanHalf=std::tan(34.0f*kPi/360.0f);rd=norm(f+r*(x*aspect*tanHalf)+u*(y*tanHalf));}
    void grabStartImpl(float nx,float ny,bool forceLight){std::lock_guard<std::mutex>lock(bodyMutex_);Vec3 ro,rd;screenRayV7(nx,ny,ro,rd);int bestIndex=forceLight?2:-1;float best=1e9f;if(forceLight){Vec3 to=bodies_[2].p-ro;best=std::max(.65f,dot(to,rd));}else{for(int i=0;i<activeBodiesV7();++i){if(i==2)continue;const Body&b=bodies_[i];float pickR=b.r*1.18f+.035f;Vec3 oc=ro-b.p;float qb=dot(oc,rd),cc=dot(oc,oc)-pickR*pickR,disc=qb*qb-cc;if(disc<0)continue;float t=-qb-std::sqrt(disc);if(t>.02f&&t<best){best=t;bestIndex=i;}}}grabbed_=bestIndex;if(grabbed_>=0){best=std::max(best,bodies_[grabbed_].r*1.70f+.32f);}grabDepth_=best;if(grabbed_>=0){grabTarget_=ro+rd*best;grabPrevTarget_=grabTarget_;grabTargetVelocity_={};grabLastUpdate_=Clock::now();const char*name=grabbed_==2?"MOVABLE LIGHT":grabbed_==1?"GUMMY":grabbed_==0?"HEAVY METAL":grabbed_==4?"SLIME":"NORMAL BALL";logV7Unlocked(std::string("grab ")+name);}}

    std::array<Vec3,kBodyCount>deformDir_{};