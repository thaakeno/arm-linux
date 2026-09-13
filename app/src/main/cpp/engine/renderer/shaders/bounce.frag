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
float hash21(vec2 p){p=fract(p*vec2(123.34,345.45));p+=dot(p,p+34.345);return fract(p.x*p.y);}
vec3 fresnel(float c,vec3 f0){return f0+(1.0-f0)*pow(1.0-c,5.0);}
float Dggx(float ndh,float a){float a2=a*a,d=ndh*ndh*(a2-1.0)+1.0;return a2/max(PI*d*d,1e-4);}
float G1(float ndv,float k){return ndv/max(ndv*(1.0-k)+k,1e-4);}
vec3 film(vec3 x){x=max(x,vec3(0));return clamp((x*(2.51*x+.03))/(x*(2.43*x+.59)+.14),0.0,1.0);}

void main(){
 vec3 N=normalize(vNormal),V=normalize(vView);vec3 base=vec3(.42);float rough=.46,metal=0.0,clearcoat=0.0;vec3 emission=vec3(0);

 if(vMat==0||vMat==1){
   vec2 uv=vUv*1.08;vec3 al=pow(texture(texConcrete,uv).rgb,vec3(2.2));vec3 nn=texture(texNormal,uv).xyz*2.0-1.0;
   vec3 T=normalize(abs(N.y)>.7?vec3(1,0,0):vec3(0,1,0)),B=normalize(cross(N,T));T=normalize(cross(B,N));N=normalize(T*nn.x*.72+B*nn.y*.72+N*max(.32,nn.z));
   vec3 arm=texture(texArm,uv).rgb;rough=clamp(arm.g*.72+.17,.25,.86);
   base=al*(vMat==1?vec3(.92,.16,.11):vec3(.92,.96,1.0));
   if(vMat==1){rough=.42;clearcoat=.08;}
 }
 else if(vMat==2){
   // v13-inspired gummy/rubber hero: saturated body, broad highlight, clear coat and soft internal glow.
   float micro=.92+.08*hash21(floor(vUv*420.0));base=vec3(.68,.006,.004)*micro;rough=.17;metal=.0;clearcoat=.72;
 }
 else if(vMat==3){
   float stripe=step(.5,fract((vUv.x+vUv.y)*6.0));base=mix(vec3(.035,.042,.047),vec3(.92,.48,.035),stripe*.86);rough=.34;metal=.72;clearcoat=.05;
 }
 else if(vMat==4){
   base=vec3(.055,.060,.067);rough=.24;metal=.58;clearcoat=.14;
   vec2 p=fract(vUv*1.9)-.5;float band=1.0-smoothstep(.05,.12,abs(abs(p.x)-abs(p.y)*.62-.10));float pulse=.92+.08*sin(pc.motion.y*5.0);emission=vec3(5.4,.34,.035)*band*pulse;
 }
 else if(vMat==5){base=vec3(1.0,.43,.008);rough=.10;clearcoat=.65;emission=vec3(7.2,3.3,.12)*(1.0+.12*sin(pc.motion.y*4.3));}
 else if(vMat==6){base=vec3(.82,.018,.012);rough=.20;metal=.42;clearcoat=.16;}
 else if(vMat==7){base=vec3(.025,.16,.72);rough=.075;clearcoat=.78;emission=vec3(.06,2.0,9.5)*(1.05+.18*sin(pc.motion.y*3.6));}

 vec3 L=normalize(vec3(-.42,.82,.36)),H=normalize(L+V);float ndl=max(dot(N,L),0.0),ndv=max(dot(N,V),.001),ndh=max(dot(N,H),0.0),vdh=max(dot(V,H),0.0);
 float a=max(.045,rough*rough),k=(rough+1.0)*(rough+1.0)/8.0;vec3 f0=mix(vec3(.038),base,metal),F=fresnel(vdh,f0);
 vec3 spec=Dggx(ndh,a)*G1(ndv,k)*G1(ndl,k)*F/max(4.0*ndl*ndv,.001);vec3 diff=(1.0-F)*(1.0-metal)*base/PI;
 vec3 sun=vec3(1.0,.90,.73)*5.4;vec3 sky=vec3(.27,.54,.88),ground=vec3(.20,.17,.12);float hemi=N.y*.5+.5;
 vec3 ambient=mix(ground,sky,hemi)*base*1.02;
 vec3 color=(diff+spec)*sun*ndl+ambient+emission;

 // Hero/contact grounding.
 if((vMat==0||vMat==1)&&N.y>.22){float radial=length(vWorld.xz-pc.ball.xz);float vertical=max(0.0,pc.ball.y-pc.ball.w-vWorld.y);float contact=(1.0-smoothstep(pc.ball.w*.22,pc.ball.w*1.7+vertical*.65,radial))*exp(-vertical*2.8);color*=1.0-.46*contact;}

 if(vMat==2){
   float rim=pow(1.0-ndv,2.15);float back=max(dot(-N,L),0.0);float top=.45+.55*max(N.y,0.0);
   color+=vec3(.24,.002,.001)*top;
   color+=rim*vec3(1.55,.055,.028);
   color+=vec3(.72,.020,.006)*pow(back,2.0)*.42;
   color+=fresnel(ndv,vec3(.032))*vec3(.52,.76,1.0)*.78;
 }
 if(clearcoat>0.0){vec3 Fc=fresnel(ndv,vec3(.04));color+=Fc*clearcoat*vec3(.72,.90,1.18)*.82;}

 // Warm sunlight and atmospheric depth without the old HDRI/skybox look.
 float d=length(vWorld-pc.ball.xyz);float fog=sat((d-18.0)/42.0);vec3 haze=vec3(.49,.72,.91);color=mix(color,haze,fog*.45);
 outColor=vec4(pow(film(color*1.08),vec3(1.0/2.2)),1.0);
}
