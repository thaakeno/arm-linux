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
   vec2 uv=vUv*1.15;vec3 al=pow(texture(texConcrete,uv).rgb,vec3(2.2));vec3 nn=texture(texNormal,uv).xyz*2.0-1.0;vec3 T=normalize(abs(N.y)>.7?vec3(1,0,0):vec3(0,1,0)),B=normalize(cross(N,T));T=normalize(cross(B,N));N=normalize(T*nn.x+B*nn.y+N*max(.2,nn.z));vec3 arm=texture(texArm,uv).rgb;rough=clamp(arm.g*.85+.12,.2,.9);base=al*(vMat==1?vec3(.78,.17,.12):vec3(.74,.78,.82));
 } else if(vMat==2){base=vec3(.92,.018,.012);rough=.09;metal=.02;clearcoat=.95;}
 else if(vMat==3){base=vec3(.055,.06,.065);rough=.32;metal=.72;}
 else if(vMat==4){base=vec3(.09,.025,.018);rough=.24;metal=.45;emission=vec3(1.0,.12,.015)*3.2;}
 else if(vMat==5){base=vec3(1.0,.45,.015);rough=.11;metal=.08;emission=vec3(1.0,.56,.04)*5.8;}
 else if(vMat==6){base=vec3(.75,.025,.02);rough=.18;metal=.48;}
 else if(vMat==7){base=vec3(.035,.22,.78);rough=.08;metal=.12;emission=vec3(.05,.55,3.5)*(4.0+1.5*sin(pc.motion.y*4.0));}

 vec3 L=normalize(vec3(-.42,.82,.36)),H=normalize(L+V);float ndl=max(dot(N,L),0.0),ndv=max(dot(N,V),.001),ndh=max(dot(N,H),0.0),vdh=max(dot(V,H),0.0);float a=max(.05,rough*rough),k=(rough+1.0)*(rough+1.0)/8.0;vec3 f0=mix(vec3(.035),base,metal),F=fresnel(vdh,f0);vec3 spec=Dggx(ndh,a)*G1(ndv,k)*G1(ndl,k)*F/max(4.0*ndl*ndv,.001);vec3 diff=(1.0-F)*(1.0-metal)*base/PI;
 vec3 sun=vec3(1.0,.88,.72)*4.2;vec3 sky=vec3(.18,.36,.62),ground=vec3(.16,.13,.10);float hemi=N.y*.5+.5;vec3 ambient=mix(ground,sky,hemi)*base*.85;vec3 color=(diff+spec)*sun*ndl+ambient+emission;
 if(vMat==2){float rim=pow(1.0-ndv,2.2);vec3 trans=vec3(.32,.008,.004)*(0.5+0.5*max(N.y,0.0));color+=trans*.42+rim*vec3(1.2,.09,.05);color+=fresnel(ndv,vec3(.035))*vec3(.35,.55,.82)*1.4;}
 if(clearcoat>0.0){vec3 Fc=fresnel(ndv,vec3(.04));color+=Fc*clearcoat*vec3(.6,.75,1.0)*.8;}
 float fog=sat((length(vWorld.xz-pc.ball.xz)-12.0)/32.0);color=mix(color,vec3(.40,.62,.83),fog*.72);outColor=vec4(film(color),1.0);
}
