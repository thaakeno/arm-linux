    void cleanupV7(){
        if(!device_)return;if(!deviceLost_.load())vkDeviceWaitIdle(device_);
        destroyTexture(floorAlb_);destroyTexture(floorNormal_);destroyTexture(floorArm_);destroyTexture(floorHeight_);
        destroyTexture(concreteAlb_);destroyTexture(concreteNormal_);destroyTexture(concreteArm_);destroyTexture(concreteHeight_);
        destroyTexture(darkAlb_);destroyTexture(darkNormal_);destroyTexture(darkArm_);destroyTexture(darkHeight_);destroyTexture(environment_);
        destroyAccel(heroAs_);destroyAccel(floorDetailedAs_);destroyBuffer(heroGeom_);destroyBuffer(floorDetailedGeom_);
    }
    void loopV7(){
        try{
            setStatus("Vulkan Studio v7 · loading coherent courtyard…");initVulkan();loadTexturesV7();if(rtSupported_)rebuildTlasV7();rebuildDescriptorsV7();replacePipelineV7();
            logV7("READY · visible HDR courtyard · physical puddle layer · matched local RT geometry");
            auto last=Clock::now(),stat=last,start=last,telemetry=last;uint32_t frames=0;
            while(running_){
                auto now=Clock::now();float dt=std::chrono::duration<float>(now-last).count();last=now;physicsStepV7(dt);updateBodyGpuV7();float t=std::chrono::duration<float>(now-start).count();if(!drawV7(t)){setStatus("ERROR: Vulkan Studio v7 · GPU device lost · renderer stopped safely");logV7("DEVICE LOST · renderer stopped; do not continue RT stress in this session");break;}adaptRtSafetyV7();++frames;
                float e=std::chrono::duration<float>(now-stat).count();if(e>=.5f){float fps=frames/e;bool rt=rtActiveV7();std::ostringstream o;o.setf(std::ios::fixed);o.precision(1);o<<"Vulkan Studio v7 · "<<gpuName_<<" · "<<fps<<" FPS · GPU "<<gpuMs_<<" ms\n";o<<"Q"<<quality_.load()<<" · "<<(stress_.load()?"SAFE STRESS":"COURTYARD")<<" · Physics "<<(physics_.load()?"ON":"OFF")<<" · RT "<<(rt?(stress_.load()?"SAFE HW":"LOCAL HW"):"BYPASSED")<<"\n";o<<"bodies "<<activeBodiesV7()<<" · wet "<<int(wetness_.load()*100)<<"% · hero "<<(heroMaterial_.load()==0?"CONCRETE":"VOLCANIC")<<" · RT budget "<<int(rtBudgetV7()*100)<<"%";setStatus(o.str());frames=0;stat=now;}
                if(std::chrono::duration<float>(now-telemetry).count()>=2.0f){std::ostringstream l;l.setf(std::ios::fixed);l.precision(2);l<<"telemetry · GPU "<<gpuMs_<<" ms · bodies "<<activeBodiesV7()<<" · RT "<<(rtActiveV7()?"ACTIVE":"BYPASSED")<<" · budget "<<rtBudgetV7()<<" · safety "<<rtSafety_.load();logV7(l.str());telemetry=now;}
            }
        }catch(const std::exception&e){setStatus(std::string("ERROR: Vulkan Studio v7: ")+e.what());logV7(std::string("ERROR: ")+e.what());}
        cleanupV7();cleanup();
    }

    AAssetManager*assets_=nullptr;
    TextureV7 floorAlb_{},floorNormal_{},floorArm_{},floorHeight_{};
    TextureV7 concreteAlb_{},concreteNormal_{},concreteArm_{},concreteHeight_{};
    TextureV7 darkAlb_{},darkNormal_{},darkArm_{},darkHeight_{},environment_{};
    HeightV7 floorHeightCpu_{},concreteHeightCpu_{},darkHeightCpu_{};
    Accel heroAs_{},floorDetailedAs_{};Buffer heroGeom_{},floorDetailedGeom_{};
    std::array<Vec3,kBodyCount>omega_{};std::array<float,kBodyCount>spin_{};
    Vec3 grabPrevTarget_{},grabTargetVelocity_{};Clock::time_point grabLastUpdate_=Clock::now();
    std::atomic<float>wetness_{.68f},exposure_{-.18f},rtSafety_{1.0f};std::atomic<int>heroMaterial_{1};
    std::atomic<bool>deviceLost_{false};uint32_t frameSerial_=0;int slowFrames_=0;
};

VulkanStudioV7*ptrV7(jlong h){return reinterpret_cast<VulkanStudioV7*>(static_cast<intptr_t>(h));}
}

extern "C" JNIEXPORT jlong JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeCreate(JNIEnv*e,jobject thiz,jobject surface){
    ANativeWindow*w=ANativeWindow_fromSurface(e,surface);if(!w)return 0;jclass cls=e->GetObjectClass(thiz);jmethodID mid=e->GetMethodID(cls,"getAssets","()Landroid/content/res/AssetManager;");jobject amObj=e->CallObjectMethod(thiz,mid);AAssetManager*assets=AAssetManager_fromJava(e,amObj);e->DeleteLocalRef(amObj);e->DeleteLocalRef(cls);try{auto*r=new VulkanStudioV7(w,assets);r->start();return static_cast<jlong>(reinterpret_cast<intptr_t>(r));}catch(...){ANativeWindow_release(w);return 0;}
}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeDestroy(JNIEnv*,jobject,jlong h){delete ptrV7(h);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeResize(JNIEnv*,jobject,jlong h,jint w,jint he){if(auto*r=ptrV7(h))r->resize(w,he);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeRotate(JNIEnv*,jobject,jlong h,jfloat y,jfloat p){if(auto*r=ptrV7(h))r->rotate(y,p);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeZoom(JNIEnv*,jobject,jlong h,jfloat s){if(auto*r=ptrV7(h))r->zoom(s);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeSetStress(JNIEnv*,jobject,jlong h,jboolean v){if(auto*r=ptrV7(h))r->setStress(v);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeSetPhysics(JNIEnv*,jobject,jlong h,jboolean v){if(auto*r=ptrV7(h))r->setPhysics(v);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeSetRt(JNIEnv*,jobject,jlong h,jboolean v){if(auto*r=ptrV7(h))r->setRt(v);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeSetQuality(JNIEnv*,jobject,jlong h,jint q){if(auto*r=ptrV7(h))r->setQuality(q);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeSetWetness(JNIEnv*,jobject,jlong h,jfloat v){if(auto*r=ptrV7(h))r->setWetness(v);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeSetExposure(JNIEnv*,jobject,jlong h,jfloat v){if(auto*r=ptrV7(h))r->setExposure(v);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeSetHeroMaterial(JNIEnv*,jobject,jlong h,jint v){if(auto*r=ptrV7(h))r->setHeroMaterial(v);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeResetPhysics(JNIEnv*,jobject,jlong h){if(auto*r=ptrV7(h))r->resetPhysics();}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabStart(JNIEnv*,jobject,jlong h,jfloat x,jfloat y){if(auto*r=ptrV7(h))r->grabStart(x,y);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabLightStart(JNIEnv*,jobject,jlong h,jfloat x,jfloat y){if(auto*r=ptrV7(h))r->grabLightStart(x,y);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabMove(JNIEnv*,jobject,jlong h,jfloat x,jfloat y){if(auto*r=ptrV7(h))r->grabMove(x,y);}
extern "C" JNIEXPORT void JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeGrabEnd(JNIEnv*,jobject,jlong h){if(auto*r=ptrV7(h))r->grabEnd();}
extern "C" JNIEXPORT jstring JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeStatus(JNIEnv*e,jobject,jlong h){std::string s=ptrV7(h)?ptrV7(h)->status():"renderer stopped";return e->NewStringUTF(s.c_str());}
extern "C" JNIEXPORT jstring JNICALL Java_com_example_dreamlinux_NativeCubeActivity_nativeLogs(JNIEnv*e,jobject,jlong h){std::string s=ptrV7(h)?ptrV7(h)->logs():"";return e->NewStringUTF(s.c_str());}
