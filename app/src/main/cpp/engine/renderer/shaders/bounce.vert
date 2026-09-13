#version 460
layout(location=0) out vec3 vWorld;
layout(location=1) out vec3 vNormal;
layout(location=2) out vec2 vUv;
layout(location=3) flat out int vMat;
layout(location=4) out vec3 vView;
layout(push_constant) uniform Push { vec4 ball; vec4 motion; vec4 misc; } pc;

const float PI=3.141592653589793;
const int PLATFORM_COUNT=14;
const int BOX_VERTS=36;
const int BALL_U=48, BALL_V=24, BALL_VERTS=BALL_U*BALL_V*6;
const int COIN_U=20, COIN_V=10, COIN_VERTS=COIN_U*COIN_V*6;
const int COINS=10;
const int SPIKES=7, SPIKE_SEG=14, SPIKE_VERTS=SPIKE_SEG*3;
const int PORTAL_U=40, PORTAL_V=12, PORTAL_VERTS=PORTAL_U*PORTAL_V*6;
const int BASE_PLAT=0;
const int BASE_PAD=BASE_PLAT+PLATFORM_COUNT*BOX_VERTS;
const int BASE_CRATE=BASE_PAD+BOX_VERTS;
const int BASE_BALL=BASE_CRATE+3*BOX_VERTS;
const int BASE_COINS=BASE_BALL+BALL_VERTS;
const int BASE_SPIKES=BASE_COINS+COINS*COIN_VERTS;
const int BASE_PORTAL=BASE_SPIKES+SPIKES*SPIKE_VERTS;

vec2 tri(int c){if(c==0)return vec2(0,0);if(c==1)return vec2(1,0);if(c==2)return vec2(1,1);if(c==3)return vec2(0,0);if(c==4)return vec2(1,1);return vec2(0,1);}
vec3 boxPos(int face,vec2 q){if(face==0)return vec3(q.x,q.y,1);if(face==1)return vec3(-q.x,q.y,-1);if(face==2)return vec3(1,q.y,-q.x);if(face==3)return vec3(-1,q.y,q.x);if(face==4)return vec3(q.x,1,-q.y);return vec3(q.x,-1,q.y);}
vec3 boxN(int f){if(f==0)return vec3(0,0,1);if(f==1)return vec3(0,0,-1);if(f==2)return vec3(1,0,0);if(f==3)return vec3(-1,0,0);if(f==4)return vec3(0,1,0);return vec3(0,-1,0);}
void boxGeom(int local,vec3 c,vec3 h,out vec3 p,out vec3 n,out vec2 uv){int f=local/6,co=local-f*6;vec2 q=tri(co)*2.0-1.0;p=c+boxPos(f,q)*h;n=boxN(f);uv=tri(co)*vec2(max(h.x,h.z)*.62,max(h.y,.2)*.75);}

void platformInfo(int i,out vec3 c,out vec3 h,out int mat){
 mat=0;
 if(i==0){c=vec3(0,-.45,1.8);h=vec3(5.8,.45,3.5);} else if(i==1){c=vec3(.8,.05,-2.7);h=vec3(1.9,.35,1.7);}
 else if(i==2){c=vec3(.6,.55,-5.2);h=vec3(3.6,.42,1.6);} else if(i==3){c=vec3(-3.5,.78,-6.8);h=vec3(1.7,.40,1.4);mat=1;}
 else if(i==4){c=vec3(3.5,.92,-7.2);h=vec3(1.8,.40,1.4);mat=1;} else if(i==5){c=vec3(0,1.18,-8.9);h=vec3(3.0,.34,1.1);}
 else if(i==6){c=vec3(-1.9,1.48,-10.9);h=vec3(1.4,.34,1.0);} else if(i==7){c=vec3(1.8,1.72,-12.6);h=vec3(1.5,.34,1.0);}
 else if(i==8){c=vec3(4.2,1.98,-14.6);h=vec3(1.45,.34,1.0);mat=1;} else if(i==9){c=vec3(1.8,2.22,-16.4);h=vec3(1.8,.34,.85);}
 else if(i==10){c=vec3(-1.0,2.48,-18.1);h=vec3(1.7,.34,.85);} else if(i==11){c=vec3(-3.3,2.72,-20);h=vec3(1.55,.34,.95);mat=1;}
 else if(i==12){c=vec3(-.5,3.02,-21.9);h=vec3(2.0,.34,1.0);} else {c=vec3(1.2,3.34,-24.5);h=vec3(4.0,.40,1.8);}
}
vec3 coinPos(int i){
 if(i==0)return vec3(-2.4,.62,1.1);if(i==1)return vec3(2.2,.62,.1);if(i==2)return vec3(.8,1.0,-2.8);if(i==3)return vec3(.6,1.45,-5.2);
 if(i==4)return vec3(-3.4,1.65,-6.8);if(i==5)return vec3(3.5,1.78,-7.2);if(i==6)return vec3(0,2.0,-8.9);if(i==7)return vec3(1.8,2.58,-12.6);
 if(i==8)return vec3(1.8,3.05,-16.4);return vec3(1.2,4.02,-24.4);
}
void sphereGeom(int local,int U,int V,vec3 c,vec3 radii,out vec3 p,out vec3 n,out vec2 uv){int cell=local/6,co=local-cell*6,x=cell%U,y=cell/U;vec2 tc=tri(co);float u=(float(x)+tc.x)/float(U),v=(float(y)+tc.y)/float(V);float th=u*2.0*PI,ph=v*PI;vec3 q=vec3(cos(th)*sin(ph),cos(ph),sin(th)*sin(ph));p=c+q*radii;n=normalize(q/radii);uv=vec2(u,v);}

mat4 viewProj(vec3 eye,vec3 target,float aspect){
 vec3 f=normalize(target-eye),r=normalize(cross(f,vec3(0,1,0))),u=cross(r,f);
 mat4 v=mat4(vec4(r.x,u.x,-f.x,0),vec4(r.y,u.y,-f.y,0),vec4(r.z,u.z,-f.z,0),vec4(-dot(r,eye),-dot(u,eye),dot(f,eye),1));
 float zN=.06,zF=100.0,t=1.0/tan(radians(58.0)*.5);mat4 p=mat4(vec4(t/aspect,0,0,0),vec4(0,t,0,0),vec4(0,0,zF/(zN-zF),-1),vec4(0,0,(zN*zF)/(zN-zF),0));return p*v;
}

void main(){
 int id=gl_VertexIndex;vec3 P=vec3(0),N=vec3(0,1,0);vec2 uv=vec2(0);int M=0;
 if(id<BASE_PAD){
   int bi=id/BOX_VERTS,l=id-bi*BOX_VERTS;vec3 c,h;int mat;platformInfo(bi,c,h,mat);boxGeom(l,c,h,P,N,uv);M=mat;
   // Turn the second platform into a visible launch ramp without changing the compact physics broadphase.
   if(bi==1){float k=clamp((P.z-(c.z-h.z))/(2.0*h.z),0.0,1.0);P.y+=k*.72;N=normalize(vec3(N.x,N.y+.42*abs(N.y),N.z-.42*abs(N.y)));}
 }
 else if(id<BASE_CRATE){boxGeom(id-BASE_PAD,vec3(2.15,.03,1.05),vec3(1.05,.10,1.05),P,N,uv);M=4;}
 else if(id<BASE_BALL){int q=(id-BASE_CRATE)/BOX_VERTS,l=(id-BASE_CRATE)-q*BOX_VERTS;vec3 cc=q==0?vec3(-3.4,.38,-.4):(q==1?vec3(-2.15,.38,-.4):vec3(3.35+sin(pc.motion.y*1.15)*1.05,.48,-5.15));boxGeom(l,cc,vec3(.52,.52,.52),P,N,uv);M=3;}
 else if(id<BASE_COINS){float s=clamp(pc.motion.x,-.18,.38);vec3 rr=vec3(pc.ball.w*inversesqrt(max(.45,1.0-s)),pc.ball.w*(1.0-s),pc.ball.w*inversesqrt(max(.45,1.0-s)));sphereGeom(id-BASE_BALL,BALL_U,BALL_V,pc.ball.xyz,rr,P,N,uv);M=2;}
 else if(id<BASE_SPIKES){int rel=id-BASE_COINS,ci=rel/COIN_VERTS,l=rel-ci*COIN_VERTS;int mask=int(pc.motion.w+.5);if((mask&(1<<ci))!=0){gl_Position=vec4(2,2,2,1);return;}sphereGeom(l,COIN_U,COIN_V,coinPos(ci),vec3(.24),P,N,uv);M=5;}
 else if(id<BASE_PORTAL){int rel=id-BASE_SPIKES,si=rel/SPIKE_VERTS,l=rel-si*SPIKE_VERTS,seg=l/3,co=l-seg*3;float a0=2.0*PI*float(seg)/float(SPIKE_SEG),a1=2.0*PI*float(seg+1)/float(SPIKE_SEG);vec3 base=vec3(-2.05+float(si)*.68,.12,-8.85);vec3 p0=base+vec3(cos(a0)*.28,0,sin(a0)*.28),p1=base+vec3(cos(a1)*.28,0,sin(a1)*.28),tip=base+vec3(0,1.0,0);P=co==0?p0:(co==1?p1:tip);N=normalize(vec3(P.x-base.x,.35,P.z-base.z));uv=vec2(float(seg)/float(SPIKE_SEG),co==2?1:0);M=6;}
 else {int rel=id-BASE_PORTAL,cell=rel/6,co=rel-cell*6,iu=cell%PORTAL_U,iv=cell/PORTAL_U;vec2 tc=tri(co);float u=(float(iu)+tc.x)/float(PORTAL_U)*2.0*PI,v=(float(iv)+tc.y)/float(PORTAL_V)*2.0*PI;float R=1.20,r=.13;vec3 c=vec3(1.2,4.50,-25.4);vec3 q=vec3((R+r*cos(v))*cos(u),r*sin(v),(R+r*cos(v))*sin(u));P=c+vec3(q.x,q.z,q.y);N=normalize(vec3(cos(v)*cos(u),sin(v),cos(v)*sin(u)).xzy);uv=vec2(u/(2.0*PI),v/(2.0*PI));M=7;}

 // Close over-the-shoulder framing: the ball stays large and the next several obstacles fill the screen.
 vec3 target=pc.ball.xyz+vec3(.45,.28,-3.7);
 vec3 eye=pc.ball.xyz+vec3(-3.55,2.75,6.45);
 mat4 vp=viewProj(eye,target,max(pc.motion.z,.2));
 gl_Position=vp*vec4(P,1);vWorld=P;vNormal=N;vUv=uv;vMat=M;vView=eye-P;
}
