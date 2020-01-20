#include "com_hazelcast_jet_cpp_EntryPoint.h"
#include <dlfcn.h>
#include <ctype.h>
#include <iostream>
using namespace std;

const char* call(const char* param, const char* libraryName) {
    void    *handle;
    const char* (*fptr)(const char*);

    /* open the needed object */
    handle = dlopen(libraryName, RTLD_LOCAL);
    if(handle == NULL) {
        std::cout << "Could not open  " << libraryName << " "<< dlerror() << std::endl;
        return NULL;
    }

    dlerror(); /* clear error code */
    fptr = (const char* (*)(const char*)) dlsym(handle, "hz_process");
    char * err;
    if ((err = dlerror()) != NULL) {
        std::cout << "Could not find symbol of hz_process function  " << std::endl;
        std::cout << err << std::endl;
        dlclose(handle);
        return NULL;
    }

    try{
        const char * result = (*fptr)(param) ;
        return result;
    } catch(...) {
        std::cout << "An error occured while running hz_process " << std::endl;
        dlclose(handle);
    }

    return NULL;
}

JNIEXPORT jstring JNICALL Java_com_hazelcast_jet_cpp_EntryPoint_run
(JNIEnv* env, jobject obj, jstring jparam, jstring jlibraryName) {
    const char* param = env->GetStringUTFChars(jparam,0);
    const char* libraryName = env->GetStringUTFChars(jlibraryName,0);


    const char* result = call(param, libraryName);
    if(result == NULL) {
        env->ReleaseStringUTFChars(jparam, param);
        env->ReleaseStringUTFChars(jlibraryName, libraryName);

        jstring responseJstr= env->NewStringUTF("NULL");
        return responseJstr;
    }

    env->ReleaseStringUTFChars(jparam, param);
    env->ReleaseStringUTFChars(jlibraryName, libraryName);

    jstring responseJstr= env->NewStringUTF(result);
    delete result;
    return responseJstr;
}
