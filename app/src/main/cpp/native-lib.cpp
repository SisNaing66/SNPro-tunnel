#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_myanmar_warpvpn_NativeUtils_getCustomApiUrl(
        JNIEnv* env,
        jobject /* this */) {
    std::string customApi = "https://nyeinkokoaung.alwaysdata.net/wg/api.php";
    return env->NewStringUTF(customApi.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_myanmar_warpvpn_NativeUtils_getCfApiBase1(
        JNIEnv* env,
        jobject /* this */) {
    std::string cfApi1 = "https://api.cloudflareclient.com/v0i1909051800";
    return env->NewStringUTF(cfApi1.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_myanmar_warpvpn_NativeUtils_getCfApiBase2(
        JNIEnv* env,
        jobject /* this */) {
    std::string cfApi2 = "https://api.cloudflareclient.com/v0a2109151800";
    return env->NewStringUTF(cfApi2.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_myanmar_warpvpn_NativeUtils_getCfApiBase3(
        JNIEnv* env,
        jobject /* this */) {
    std::string cfApi3 = "https://api.cloudflareclient.com/v0a2409051800";
    return env->NewStringUTF(cfApi3.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_myanmar_warpvpn_AuthManager_getWorkerApiUrl(
        JNIEnv* env,
        jobject /* this */) {
    std::string apiUrl = "https://your-worker-name.subdomain.workers.dev/api/check-license";
    
    return env->NewStringUTF(apiUrl.c_str());
}
