#version 460
layout(location=0) in vec3 vWorld;
layout(location=1) in vec3 vNormal;
layout(location=2) in vec2 vUv;
layout(location=3) flat in int vMat;
layout(location=4) in vec3 vView;
layout(location=0) out vec4 outColor;
layout(set=0,binding=0) uniform sampler2D texConcrete;
layout(set=0,binding=1) uniform sampler2D texNormal;
layout(set=0,binding=2) uniform sampler2D texArm;
layout(push_constant) uniform Push {vec4 ball;vec4 motion;vec4 misc;} pc;

const float PI=3.141592653589793;
float sat(float x){return clamp(x,0.0,1.0);}
vec3 fresnel(float c,vec3 f0){return f0+(1.0-f0)*pow(1.0-c,5.0);}
float Dggx(float ndh,float a){float a2=a*a,d=ndh*ndh*(a2-1.0)+1.0;return a2/max(PI*d*d,1e-4);}
float G1(float ndv,float k){return ndv/max(ndv*(1.0-k)+k,1e-4);}
vec3 film(vec3 x){x=max(x,vec3(0));return clamp((x*(2.51*x+.03))/(x*(2.43*x+.59)+.14),0.0,1.0);}

void main(){
 vec3 N=normalize(vNormal),V=normalize(vView);vec3 base=vec3(.42);float rough=.46,metal=0.0;vec3 emission=vec3(0);float clearcoat=0.0;
 if(vMat==0||vMat==1){
   vec2 uv=vUv*1.15;vec3 al=pow(texture(texConcrete,uv).rgb,vec3(2.2));vec3 nn=texture(texNormal,uv).xyz*2.0-1.0;vec3 T=normalize(abs(N.y)>.7?vec3(1,0,0):vec3(0,1,0)),B=normalize(cross(N,T));T=normalize(cross(B,N));N=normalize(T*nn.x+B*nn.y+N*max(.2,nn.z));vec3 arm=texture(texArm,uv).rgb;rough=clamp(arm.g*.82+.13,.22,.88);base=al*(vMat==1?vec3(.88,.20,.15):vec3(.80,.84,.88));
 } else if(vMat==2){base=vec3(.94,.012,.009);rough=.065;metal=.01;clearcoat=1.0;}
 else if(vMat==3){base=vec3(.045,.05,.055);rough=.29;metal=.76;}
 else if(vMat==4){base=vec3(.11,.024,.016);rough=.20;metal=.50;emission=vec3(1.0,.10,.008)*3.8;}
 else if(vMat==5){base=vec3(1.0,.48,.012);rough=.08;metal=.06;emission=vec3(1.0,.62,.035)*6.4;}
 else if(vMat==6){base=vec3(.82,.018,.012);rough=.16;metal=.52;}
 else if(vMat==7){base=vec3(.025,.20,.88);rough=.065;metal=.15;emission=vec3(.04,.48,3.8)*(4.2+1.7*sin(pc.motion.y*4.0));}

 vec3 L=normalize(vec3(-.42,.82,.36)),H=normalize(L+V);float ndl=max(dot(N,L),0.0),ndv=max(dot(N,V),.001),ndh=max(dot(N,H),0.0),vdh=max(dot(V,H),0.0);float a=max(.045,rough*rough),k=(rough+1.0)*(rough+1.0)/8.0;vec3 f0=mix(vec3(.035),base,metal),F=fresnel(vdh,f0);vec3 spec=Dggx(ndh,a)*G1(ndv,k)*G1(ndl,k)*F/max(4.0*ndl*ndv,.001);vec3 diff=(1.0-F)*(1.0-metal)*base/PI;
 vec3 sun=vec3(1.0,.90,.76)*4.6;vec3 sky=vec3(.20,.42,.76),ground=vec3(.15,.12,.085);float hemi=N.y*.5+.5;vec3 ambient=mix(ground,sky,hemi)*base*.88;vec3 color=(diff+spec)*sun*ndl+ambient+emission;

 // Cheap physically motivated contact grounding: the ball darkens only nearby upward-facing course surfaces.
 if((vMat==0||vMat==1)&&N.y>.25){float radial=length(vWorld.xz-pc.ball.xz);float vertical=max(0.0,pc.ball.y-pc.ball.w-vWorld.y);float contact=(1.0-smoothstep(pc.ball.w*.25,pc.ball.w*1.85+vertical*.65,radial))*exp(-vertical*2.2);color*=1.0-.42*contact;}

 if(vMat==2){
   float rim=pow(1.0-ndv,2.0);float topGlow=.35+.65*max(N.y,0.0);vec3 inner=vec3(.55,.006,.003)*topGlow;
   color+=inner*.52+rim*vec3(1.5,.075,.045);
   color+=fresnel(ndv,vec3(.028))*vec3(.48,.75,1.15)*1.55;
   float fakeTransmission=pow(max(dot(-N,normalize(vec3(.3,-.6,.72))),0.0),3.0);color+=vec3(.48,.015,.008)*fakeTransmission*.42;
 }
 if(clearcoat>0.0){vec3 Fc=fresnel(ndv,vec3(.035));color+=Fc*clearcoat*vec3(.70,.88,1.18)*1.0;}
 float fog=sat((length(vWorld.xz-pc.ball.xz)-15.0)/35.0);color=mix(color,vec3(.45,.68,.90),fog*.58);outColor=vec4(film(color),1.0);
}
