#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include "project_neon.h"
#include "com_quietterminal_projectneon_jni_NeonClientJNI.h"
#include "com_quietterminal_projectneon_jni_NeonHostJNI.h"

#ifdef _WIN32
#include <windows.h>
#define THREAD_LOCAL __declspec(thread)
#else
#include <pthread.h>
#define THREAD_LOCAL __thread
#endif

static JavaVM *g_jvm = NULL;
static THREAD_LOCAL char g_error_buffer[512] = {0};
static jclass g_neonClientClass = NULL;
static jclass g_neonHostClass = NULL;

struct NeonClientHandle {
    jobject javaObject;
    PongCallback pongCallback;
    SessionConfigCallback sessionConfigCallback;
    PacketTypeRegistryCallback packetTypeRegistryCallback;
    UnhandledPacketCallback unhandledPacketCallback;
    WrongDestinationCallback wrongDestinationCallback;
};

struct NeonHostHandle {
    jobject javaObject;
    ClientConnectCallback clientConnectCallback;
    ClientDenyCallback clientDenyCallback;
    PingReceivedCallback pingReceivedCallback;
    HostUnhandledPacketCallback unhandledPacketCallback;
};

static void set_error(const char *msg) {
    strncpy(g_error_buffer, msg, sizeof(g_error_buffer) - 1);
    g_error_buffer[sizeof(g_error_buffer) - 1] = '\0';
}

static JNIEnv* get_jni_env() {
    JNIEnv *env = NULL;
    if (g_jvm == NULL) {
        set_error("JVM not initialized");
        return NULL;
    }

    jint result = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_8);
    if (result == JNI_EDETACHED) {
        result = (*g_jvm)->AttachCurrentThread(g_jvm, (void**)&env, NULL);
        if (result != JNI_OK) {
            set_error("Failed to attach thread to JVM");
            return NULL;
        }
    } else if (result != JNI_OK) {
        set_error("Failed to get JNI environment");
        return NULL;
    }
    return env;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    JNIEnv *env;

    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_8) != JNI_OK) {
        return JNI_ERR;
    }

    jclass localClientClass = (*env)->FindClass(env, "com/quietterminal/projectneon/client/NeonClient");
    if (localClientClass == NULL) {
        return JNI_ERR;
    }
    g_neonClientClass = (*env)->NewGlobalRef(env, localClientClass);
    (*env)->DeleteLocalRef(env, localClientClass);

    jclass localHostClass = (*env)->FindClass(env, "com/quietterminal/projectneon/host/NeonHost");
    if (localHostClass == NULL) {
        return JNI_ERR;
    }
    g_neonHostClass = (*env)->NewGlobalRef(env, localHostClass);
    (*env)->DeleteLocalRef(env, localHostClass);

    return JNI_VERSION_1_8;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_8) == JNI_OK) {
        if (g_neonClientClass != NULL) {
            (*env)->DeleteGlobalRef(env, g_neonClientClass);
            g_neonClientClass = NULL;
        }
        if (g_neonHostClass != NULL) {
            (*env)->DeleteGlobalRef(env, g_neonHostClass);
            g_neonHostClass = NULL;
        }
    }
    g_jvm = NULL;
}

JNIEXPORT jlong JNICALL Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientNew(JNIEnv *env, jclass cls, jstring name) {
    if (name == NULL) {
        set_error("Client name cannot be null");
        return 0;
    }

    const char *nameStr = (*env)->GetStringUTFChars(env, name, NULL);
    if (nameStr == NULL) {
        set_error("Failed to get client name string");
        return 0;
    }

    jmethodID constructor = (*env)->GetMethodID(env, g_neonClientClass, "<init>", "(Ljava/lang/String;)V");
    if (constructor == NULL) {
        (*env)->ReleaseStringUTFChars(env, name, nameStr);
        set_error("Failed to find NeonClient constructor");
        return 0;
    }

    jobject clientObj = (*env)->NewObject(env, g_neonClientClass, constructor, name);
    if (clientObj == NULL) {
        (*env)->ReleaseStringUTFChars(env, name, nameStr);
        set_error("Failed to create NeonClient instance");
        return 0;
    }

    NeonClientHandle *handle = (NeonClientHandle*)malloc(sizeof(NeonClientHandle));
    if (handle == NULL) {
        (*env)->DeleteLocalRef(env, clientObj);
        (*env)->ReleaseStringUTFChars(env, name, nameStr);
        set_error("Failed to allocate client handle");
        return 0;
    }

    handle->javaObject = (*env)->NewGlobalRef(env, clientObj);
    handle->pongCallback = NULL;
    handle->sessionConfigCallback = NULL;
    handle->packetTypeRegistryCallback = NULL;
    handle->unhandledPacketCallback = NULL;
    handle->wrongDestinationCallback = NULL;

    (*env)->DeleteLocalRef(env, clientObj);
    (*env)->ReleaseStringUTFChars(env, name, nameStr);

    return (jlong)(intptr_t)handle;
}

JNIEXPORT jboolean JNICALL Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientConnect(JNIEnv *env, jclass cls, jlong clientPtr, jint sessionId, jstring relayAddr) {
    if (clientPtr == 0) {
        set_error("Invalid client handle");
        return JNI_FALSE;
    }

    NeonClientHandle *handle = (NeonClientHandle*)(intptr_t)clientPtr;

    const char *addrStr = (*env)->GetStringUTFChars(env, relayAddr, NULL);
    if (addrStr == NULL) {
        set_error("Failed to get relay address string");
        return JNI_FALSE;
    }

    char host[256];
    int port;
    if (sscanf(addrStr, "%255[^:]:%d", host, &port) != 2) {
        (*env)->ReleaseStringUTFChars(env, relayAddr, addrStr);
        set_error("Invalid relay address format (expected host:port)");
        return JNI_FALSE;
    }

    jmethodID connectMethod = (*env)->GetMethodID(env, g_neonClientClass, "connect", "(ILjava/lang/String;I)V");
    if (connectMethod == NULL) {
        (*env)->ReleaseStringUTFChars(env, relayAddr, addrStr);
        set_error("Failed to find connect method");
        return JNI_FALSE;
    }

    jstring hostStr = (*env)->NewStringUTF(env, host);
    if (hostStr == NULL) {
        (*env)->ReleaseStringUTFChars(env, relayAddr, addrStr);
        set_error("Failed to create host string");
        return JNI_FALSE;
    }

    (*env)->CallVoidMethod(env, handle->javaObject, connectMethod, sessionId, hostStr, port);

    (*env)->DeleteLocalRef(env, hostStr);
    (*env)->ReleaseStringUTFChars(env, relayAddr, addrStr);

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        set_error("Exception during connect");
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

JNIEXPORT jint JNICALL Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientProcessPackets(JNIEnv *env, jclass cls, jlong clientPtr) {
    if (clientPtr == 0) {
        set_error("Invalid client handle");
        return -1;
    }

    NeonClientHandle *handle = (NeonClientHandle*)(intptr_t)clientPtr;

    jmethodID processMethod = (*env)->GetMethodID(env, g_neonClientClass, "processPackets", "()I");
    if (processMethod == NULL) {
        set_error("Failed to find processPackets method");
        return -1;
    }

    jint result = (*env)->CallIntMethod(env, handle->javaObject, processMethod);

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        set_error("Exception during processPackets");
        return -1;
    }

    return result;
}

JNIEXPORT jint JNICALL Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientGetId(JNIEnv *env, jclass cls, jlong clientPtr) {
    if (clientPtr == 0) {
        set_error("Invalid client handle");
        return -1;
    }

    NeonClientHandle *handle = (NeonClientHandle*)(intptr_t)clientPtr;

    jmethodID getIdMethod = (*env)->GetMethodID(env, g_neonClientClass, "getClientId", "()I");
    if (getIdMethod == NULL) {
        set_error("Failed to find getClientId method");
        return -1;
    }

    return (*env)->CallIntMethod(env, handle->javaObject, getIdMethod);
}

JNIEXPORT jint JNICALL Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientGetSessionId(JNIEnv *env, jclass cls, jlong clientPtr) {
    if (clientPtr == 0) {
        set_error("Invalid client handle");
        return -1;
    }

    NeonClientHandle *handle = (NeonClientHandle*)(intptr_t)clientPtr;

    jmethodID getSessionMethod = (*env)->GetMethodID(env, g_neonClientClass, "getSessionId", "()I");
    if (getSessionMethod == NULL) {
        set_error("Failed to find getSessionId method");
        return -1;
    }

    return (*env)->CallIntMethod(env, handle->javaObject, getSessionMethod);
}

JNIEXPORT jboolean JNICALL Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientIsConnected(JNIEnv *env, jclass cls, jlong clientPtr) {
    if (clientPtr == 0) {
        return JNI_FALSE;
    }

    NeonClientHandle *handle = (NeonClientHandle*)(intptr_t)clientPtr;

    jmethodID isConnectedMethod = (*env)->GetMethodID(env, g_neonClientClass, "isConnected", "()Z");
    if (isConnectedMethod == NULL) {
        return JNI_FALSE;
    }

    return (*env)->CallBooleanMethod(env, handle->javaObject, isConnectedMethod);
}

JNIEXPORT jboolean JNICALL Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientSendPing(JNIEnv *env, jclass cls, jlong clientPtr) {
    if (clientPtr == 0) {
        set_error("Invalid client handle");
        return JNI_FALSE;
    }

    NeonClientHandle *handle = (NeonClientHandle*)(intptr_t)clientPtr;

    jmethodID sendPingMethod = (*env)->GetMethodID(env, g_neonClientClass, "sendPing", "()V");
    if (sendPingMethod == NULL) {
        set_error("Failed to find sendPing method");
        return JNI_FALSE;
    }

    (*env)->CallVoidMethod(env, handle->javaObject, sendPingMethod);

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        set_error("Exception during sendPing");
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientSetAutoPing(JNIEnv *env, jclass cls, jlong clientPtr, jboolean enabled) {
    if (clientPtr == 0) {
        set_error("Invalid client handle");
        return;
    }

    NeonClientHandle *handle = (NeonClientHandle*)(intptr_t)clientPtr;

    jmethodID setAutoPingMethod = (*env)->GetMethodID(env, g_neonClientClass, "setAutoPing", "(Z)V");
    if (setAutoPingMethod == NULL) {
        set_error("Failed to find setAutoPing method");
        return;
    }

    (*env)->CallVoidMethod(env, handle->javaObject, setAutoPingMethod, enabled);
}

JNIEXPORT void JNICALL Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientSetPongCallback(JNIEnv *env, jclass cls, jlong clientPtr, jlong callback) {
    if (clientPtr == 0) {
        set_error("Invalid client handle");
        return;
    }

    NeonClientHandle *handle = (NeonClientHandle*)(intptr_t)clientPtr;
    handle->pongCallback = (PongCallback)(intptr_t)callback;
}

JNIEXPORT void JNICALL Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientSetSessionConfigCallback(JNIEnv *env, jclass cls, jlong clientPtr, jlong callback) {
    if (clientPtr == 0) {
        set_error("Invalid client handle");
        return;
    }

    NeonClientHandle *handle = (NeonClientHandle*)(intptr_t)clientPtr;
    handle->sessionConfigCallback = (SessionConfigCallback)(intptr_t)callback;
}

JNIEXPORT void JNICALL Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientSetPacketTypeRegistryCallback(JNIEnv *env, jclass cls, jlong clientPtr, jlong callback) {
    if (clientPtr == 0) {
        set_error("Invalid client handle");
        return;
    }

    NeonClientHandle *handle = (NeonClientHandle*)(intptr_t)clientPtr;
    handle->packetTypeRegistryCallback = (PacketTypeRegistryCallback)(intptr_t)callback;
}

JNIEXPORT void JNICALL Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientSetUnhandledPacketCallback(JNIEnv *env, jclass cls, jlong clientPtr, jlong callback) {
    if (clientPtr == 0) {
        set_error("Invalid client handle");
        return;
    }

    NeonClientHandle *handle = (NeonClientHandle*)(intptr_t)clientPtr;
    handle->unhandledPacketCallback = (UnhandledPacketCallback)(intptr_t)callback;
}

JNIEXPORT void JNICALL Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientSetWrongDestinationCallback(JNIEnv *env, jclass cls, jlong clientPtr, jlong callback) {
    if (clientPtr == 0) {
        set_error("Invalid client handle");
        return;
    }

    NeonClientHandle *handle = (NeonClientHandle*)(intptr_t)clientPtr;
    handle->wrongDestinationCallback = (WrongDestinationCallback)(intptr_t)callback;
}

JNIEXPORT void JNICALL Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientFree(JNIEnv *env, jclass cls, jlong clientPtr) {
    if (clientPtr == 0) {
        return;
    }

    NeonClientHandle *handle = (NeonClientHandle*)(intptr_t)clientPtr;

    jmethodID closeMethod = (*env)->GetMethodID(env, g_neonClientClass, "close", "()V");
    if (closeMethod != NULL) {
        (*env)->CallVoidMethod(env, handle->javaObject, closeMethod);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
    }

    (*env)->DeleteGlobalRef(env, handle->javaObject);
    free(handle);
}

JNIEXPORT jstring JNICALL Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonGetLastError(JNIEnv *env, jclass cls) {
    if (g_error_buffer[0] == '\0') {
        return NULL;
    }
    return (*env)->NewStringUTF(env, g_error_buffer);
}

JNIEXPORT jlong JNICALL Java_com_quietterminal_projectneon_jni_NeonHostJNI_neonHostNew(JNIEnv *env, jclass cls, jint sessionId, jstring relayAddr) {
    if (relayAddr == NULL) {
        set_error("Relay address cannot be null");
        return 0;
    }

    const char *addrStr = (*env)->GetStringUTFChars(env, relayAddr, NULL);
    if (addrStr == NULL) {
        set_error("Failed to get relay address string");
        return 0;
    }

    char host[256];
    int port;
    if (sscanf(addrStr, "%255[^:]:%d", host, &port) != 2) {
        (*env)->ReleaseStringUTFChars(env, relayAddr, addrStr);
        set_error("Invalid relay address format (expected host:port)");
        return 0;
    }

    jmethodID constructor = (*env)->GetMethodID(env, g_neonHostClass, "<init>", "(ILjava/lang/String;I)V");
    if (constructor == NULL) {
        (*env)->ReleaseStringUTFChars(env, relayAddr, addrStr);
        set_error("Failed to find NeonHost constructor");
        return 0;
    }

    jstring hostStr = (*env)->NewStringUTF(env, host);
    if (hostStr == NULL) {
        (*env)->ReleaseStringUTFChars(env, relayAddr, addrStr);
        set_error("Failed to create host string");
        return 0;
    }

    jobject hostObj = (*env)->NewObject(env, g_neonHostClass, constructor, sessionId, hostStr, port);
    (*env)->DeleteLocalRef(env, hostStr);
    (*env)->ReleaseStringUTFChars(env, relayAddr, addrStr);

    if (hostObj == NULL) {
        set_error("Failed to create NeonHost instance");
        return 0;
    }

    NeonHostHandle *handle = (NeonHostHandle*)malloc(sizeof(NeonHostHandle));
    if (handle == NULL) {
        (*env)->DeleteLocalRef(env, hostObj);
        set_error("Failed to allocate host handle");
        return 0;
    }

    handle->javaObject = (*env)->NewGlobalRef(env, hostObj);
    handle->clientConnectCallback = NULL;
    handle->clientDenyCallback = NULL;
    handle->pingReceivedCallback = NULL;
    handle->unhandledPacketCallback = NULL;

    (*env)->DeleteLocalRef(env, hostObj);

    return (jlong)(intptr_t)handle;
}

JNIEXPORT jboolean JNICALL Java_com_quietterminal_projectneon_jni_NeonHostJNI_neonHostStart(JNIEnv *env, jclass cls, jlong hostPtr) {
    if (hostPtr == 0) {
        set_error("Invalid host handle");
        return JNI_FALSE;
    }

    NeonHostHandle *handle = (NeonHostHandle*)(intptr_t)hostPtr;

    jmethodID startMethod = (*env)->GetMethodID(env, g_neonHostClass, "start", "()V");
    if (startMethod == NULL) {
        set_error("Failed to find start method");
        return JNI_FALSE;
    }

    (*env)->CallVoidMethod(env, handle->javaObject, startMethod);

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        set_error("Exception during start");
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

JNIEXPORT jint JNICALL Java_com_quietterminal_projectneon_jni_NeonHostJNI_neonHostProcessPackets(JNIEnv *env, jclass cls, jlong hostPtr) {
    if (hostPtr == 0) {
        set_error("Invalid host handle");
        return -1;
    }

    NeonHostHandle *handle = (NeonHostHandle*)(intptr_t)hostPtr;

    jmethodID processMethod = (*env)->GetMethodID(env, g_neonHostClass, "processPackets", "()I");
    if (processMethod == NULL) {
        set_error("Failed to find processPackets method");
        return -1;
    }

    jint result = (*env)->CallIntMethod(env, handle->javaObject, processMethod);

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        set_error("Exception during processPackets");
        return -1;
    }

    return result;
}

JNIEXPORT jint JNICALL Java_com_quietterminal_projectneon_jni_NeonHostJNI_neonHostGetSessionId(JNIEnv *env, jclass cls, jlong hostPtr) {
    if (hostPtr == 0) {
        set_error("Invalid host handle");
        return -1;
    }

    NeonHostHandle *handle = (NeonHostHandle*)(intptr_t)hostPtr;

    jmethodID getSessionMethod = (*env)->GetMethodID(env, g_neonHostClass, "getSessionId", "()I");
    if (getSessionMethod == NULL) {
        set_error("Failed to find getSessionId method");
        return -1;
    }

    return (*env)->CallIntMethod(env, handle->javaObject, getSessionMethod);
}

JNIEXPORT jint JNICALL Java_com_quietterminal_projectneon_jni_NeonHostJNI_neonHostGetClientCount(JNIEnv *env, jclass cls, jlong hostPtr) {
    if (hostPtr == 0) {
        set_error("Invalid host handle");
        return 0;
    }

    NeonHostHandle *handle = (NeonHostHandle*)(intptr_t)hostPtr;

    jmethodID getClientCountMethod = (*env)->GetMethodID(env, g_neonHostClass, "getClientCount", "()I");
    if (getClientCountMethod == NULL) {
        set_error("Failed to find getClientCount method");
        return 0;
    }

    return (*env)->CallIntMethod(env, handle->javaObject, getClientCountMethod);
}

JNIEXPORT void JNICALL Java_com_quietterminal_projectneon_jni_NeonHostJNI_neonHostSetClientConnectCallback(JNIEnv *env, jclass cls, jlong hostPtr, jlong callback) {
    if (hostPtr == 0) {
        set_error("Invalid host handle");
        return;
    }

    NeonHostHandle *handle = (NeonHostHandle*)(intptr_t)hostPtr;
    handle->clientConnectCallback = (ClientConnectCallback)(intptr_t)callback;
}

JNIEXPORT void JNICALL Java_com_quietterminal_projectneon_jni_NeonHostJNI_neonHostSetClientDenyCallback(JNIEnv *env, jclass cls, jlong hostPtr, jlong callback) {
    if (hostPtr == 0) {
        set_error("Invalid host handle");
        return;
    }

    NeonHostHandle *handle = (NeonHostHandle*)(intptr_t)hostPtr;
    handle->clientDenyCallback = (ClientDenyCallback)(intptr_t)callback;
}

JNIEXPORT void JNICALL Java_com_quietterminal_projectneon_jni_NeonHostJNI_neonHostSetPingReceivedCallback(JNIEnv *env, jclass cls, jlong hostPtr, jlong callback) {
    if (hostPtr == 0) {
        set_error("Invalid host handle");
        return;
    }

    NeonHostHandle *handle = (NeonHostHandle*)(intptr_t)hostPtr;
    handle->pingReceivedCallback = (PingReceivedCallback)(intptr_t)callback;
}

JNIEXPORT void JNICALL Java_com_quietterminal_projectneon_jni_NeonHostJNI_neonHostSetUnhandledPacketCallback(JNIEnv *env, jclass cls, jlong hostPtr, jlong callback) {
    if (hostPtr == 0) {
        set_error("Invalid host handle");
        return;
    }

    NeonHostHandle *handle = (NeonHostHandle*)(intptr_t)hostPtr;
    handle->unhandledPacketCallback = (HostUnhandledPacketCallback)(intptr_t)callback;
}

JNIEXPORT void JNICALL Java_com_quietterminal_projectneon_jni_NeonHostJNI_neonHostFree(JNIEnv *env, jclass cls, jlong hostPtr) {
    if (hostPtr == 0) {
        return;
    }

    NeonHostHandle *handle = (NeonHostHandle*)(intptr_t)hostPtr;

    jmethodID closeMethod = (*env)->GetMethodID(env, g_neonHostClass, "close", "()V");
    if (closeMethod != NULL) {
        (*env)->CallVoidMethod(env, handle->javaObject, closeMethod);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
    }

    (*env)->DeleteGlobalRef(env, handle->javaObject);
    free(handle);
}

NeonClientHandle* neon_client_new(const char* name) {
    JNIEnv *env = get_jni_env();
    if (env == NULL) {
        return NULL;
    }

    jstring nameStr = (*env)->NewStringUTF(env, name);
    if (nameStr == NULL) {
        set_error("Failed to create name string");
        return NULL;
    }

    jlong ptr = Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientNew(env, NULL, nameStr);
    (*env)->DeleteLocalRef(env, nameStr);

    return (NeonClientHandle*)(intptr_t)ptr;
}

bool neon_client_connect(NeonClientHandle* client, uint32_t session_id, const char* relay_addr) {
    if (client == NULL) {
        set_error("Invalid client handle");
        return false;
    }

    JNIEnv *env = get_jni_env();
    if (env == NULL) {
        return false;
    }

    jstring addrStr = (*env)->NewStringUTF(env, relay_addr);
    if (addrStr == NULL) {
        set_error("Failed to create relay address string");
        return false;
    }

    jboolean result = Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientConnect(
        env, NULL, (jlong)(intptr_t)client, session_id, addrStr);

    (*env)->DeleteLocalRef(env, addrStr);
    return result == JNI_TRUE;
}

int neon_client_process_packets(NeonClientHandle* client) {
    if (client == NULL) {
        set_error("Invalid client handle");
        return -1;
    }

    JNIEnv *env = get_jni_env();
    if (env == NULL) {
        return -1;
    }

    return Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientProcessPackets(
        env, NULL, (jlong)(intptr_t)client);
}

uint8_t neon_client_get_id(NeonClientHandle* client) {
    if (client == NULL) {
        return 0;
    }

    JNIEnv *env = get_jni_env();
    if (env == NULL) {
        return 0;
    }

    return (uint8_t)Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientGetId(
        env, NULL, (jlong)(intptr_t)client);
}

uint32_t neon_client_get_session_id(NeonClientHandle* client) {
    if (client == NULL) {
        return 0;
    }

    JNIEnv *env = get_jni_env();
    if (env == NULL) {
        return 0;
    }

    return (uint32_t)Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientGetSessionId(
        env, NULL, (jlong)(intptr_t)client);
}

bool neon_client_is_connected(NeonClientHandle* client) {
    if (client == NULL) {
        return false;
    }

    JNIEnv *env = get_jni_env();
    if (env == NULL) {
        return false;
    }

    return Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientIsConnected(
        env, NULL, (jlong)(intptr_t)client) == JNI_TRUE;
}

bool neon_client_send_ping(NeonClientHandle* client) {
    if (client == NULL) {
        set_error("Invalid client handle");
        return false;
    }

    JNIEnv *env = get_jni_env();
    if (env == NULL) {
        return false;
    }

    return Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientSendPing(
        env, NULL, (jlong)(intptr_t)client) == JNI_TRUE;
}

void neon_client_set_auto_ping(NeonClientHandle* client, bool enabled) {
    if (client == NULL) {
        return;
    }

    JNIEnv *env = get_jni_env();
    if (env == NULL) {
        return;
    }

    Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientSetAutoPing(
        env, NULL, (jlong)(intptr_t)client, enabled ? JNI_TRUE : JNI_FALSE);
}

void neon_client_set_pong_callback(NeonClientHandle* client, PongCallback callback) {
    if (client == NULL) {
        return;
    }

    JNIEnv *env = get_jni_env();
    if (env == NULL) {
        return;
    }

    Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientSetPongCallback(
        env, NULL, (jlong)(intptr_t)client, (jlong)(intptr_t)callback);
}

void neon_client_set_session_config_callback(NeonClientHandle* client, SessionConfigCallback callback) {
    if (client == NULL) {
        return;
    }

    JNIEnv *env = get_jni_env();
    if (env == NULL) {
        return;
    }

    Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientSetSessionConfigCallback(
        env, NULL, (jlong)(intptr_t)client, (jlong)(intptr_t)callback);
}

void neon_client_set_packet_type_registry_callback(NeonClientHandle* client, PacketTypeRegistryCallback callback) {
    if (client == NULL) {
        return;
    }

    JNIEnv *env = get_jni_env();
    if (env == NULL) {
        return;
    }

    Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientSetPacketTypeRegistryCallback(
        env, NULL, (jlong)(intptr_t)client, (jlong)(intptr_t)callback);
}

void neon_client_set_unhandled_packet_callback(NeonClientHandle* client, UnhandledPacketCallback callback) {
    if (client == NULL) {
        return;
    }

    JNIEnv *env = get_jni_env();
    if (env == NULL) {
        return;
    }

    Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientSetUnhandledPacketCallback(
        env, NULL, (jlong)(intptr_t)client, (jlong)(intptr_t)callback);
}

void neon_client_set_wrong_destination_callback(NeonClientHandle* client, WrongDestinationCallback callback) {
    if (client == NULL) {
        return;
    }

    JNIEnv *env = get_jni_env();
    if (env == NULL) {
        return;
    }

    Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientSetWrongDestinationCallback(
        env, NULL, (jlong)(intptr_t)client, (jlong)(intptr_t)callback);
}

void neon_client_free(NeonClientHandle* client) {
    if (client == NULL) {
        return;
    }

    JNIEnv *env = get_jni_env();
    if (env == NULL) {
        return;
    }

    Java_com_quietterminal_projectneon_jni_NeonClientJNI_neonClientFree(
        env, NULL, (jlong)(intptr_t)client);
}

NeonHostHandle* neon_host_new(uint32_t session_id, const char* relay_addr) {
    JNIEnv *env = get_jni_env();
    if (env == NULL) {
        return NULL;
    }

    jstring addrStr = (*env)->NewStringUTF(env, relay_addr);
    if (addrStr == NULL) {
        set_error("Failed to create relay address string");
        return NULL;
    }

    jlong ptr = Java_com_quietterminal_projectneon_jni_NeonHostJNI_neonHostNew(env, NULL, session_id, addrStr);
    (*env)->DeleteLocalRef(env, addrStr);

    return (NeonHostHandle*)(intptr_t)ptr;
}

bool neon_host_start(NeonHostHandle* host) {
    if (host == NULL) {
        set_error("Invalid host handle");
        return false;
    }

    JNIEnv *env = get_jni_env();
    if (env == NULL) {
        return false;
    }

    return Java_com_quietterminal_projectneon_jni_NeonHostJNI_neonHostStart(
        env, NULL, (jlong)(intptr_t)host) == JNI_TRUE;
}

int neon_host_process_packets(NeonHostHandle* host) {
    if (host == NULL) {
        set_error("Invalid host handle");
        return -1;
    }

    JNIEnv *env = get_jni_env();
    if (env == NULL) {
        return -1;
    }

    return Java_com_quietterminal_projectneon_jni_NeonHostJNI_neonHostProcessPackets(
        env, NULL, (jlong)(intptr_t)host);
}

uint32_t neon_host_get_session_id(NeonHostHandle* host) {
    if (host == NULL) {
        return 0;
    }

    JNIEnv *env = get_jni_env();
    if (env == NULL) {
        return 0;
    }

    return (uint32_t)Java_com_quietterminal_projectneon_jni_NeonHostJNI_neonHostGetSessionId(
        env, NULL, (jlong)(intptr_t)host);
}

size_t neon_host_get_client_count(NeonHostHandle* host) {
    if (host == NULL) {
        return 0;
    }

    JNIEnv *env = get_jni_env();
    if (env == NULL) {
        return 0;
    }

    return (size_t)Java_com_quietterminal_projectneon_jni_NeonHostJNI_neonHostGetClientCount(
        env, NULL, (jlong)(intptr_t)host);
}

void neon_host_set_client_connect_callback(NeonHostHandle* host, ClientConnectCallback callback) {
    if (host == NULL) {
        return;
    }

    JNIEnv *env = get_jni_env();
    if (env == NULL) {
        return;
    }

    Java_com_quietterminal_projectneon_jni_NeonHostJNI_neonHostSetClientConnectCallback(
        env, NULL, (jlong)(intptr_t)host, (jlong)(intptr_t)callback);
}

void neon_host_set_client_deny_callback(NeonHostHandle* host, ClientDenyCallback callback) {
    if (host == NULL) {
        return;
    }

    JNIEnv *env = get_jni_env();
    if (env == NULL) {
        return;
    }

    Java_com_quietterminal_projectneon_jni_NeonHostJNI_neonHostSetClientDenyCallback(
        env, NULL, (jlong)(intptr_t)host, (jlong)(intptr_t)callback);
}

void neon_host_set_ping_received_callback(NeonHostHandle* host, PingReceivedCallback callback) {
    if (host == NULL) {
        return;
    }

    JNIEnv *env = get_jni_env();
    if (env == NULL) {
        return;
    }

    Java_com_quietterminal_projectneon_jni_NeonHostJNI_neonHostSetPingReceivedCallback(
        env, NULL, (jlong)(intptr_t)host, (jlong)(intptr_t)callback);
}

void neon_host_set_unhandled_packet_callback(NeonHostHandle* host, HostUnhandledPacketCallback callback) {
    if (host == NULL) {
        return;
    }

    JNIEnv *env = get_jni_env();
    if (env == NULL) {
        return;
    }

    Java_com_quietterminal_projectneon_jni_NeonHostJNI_neonHostSetUnhandledPacketCallback(
        env, NULL, (jlong)(intptr_t)host, (jlong)(intptr_t)callback);
}

void neon_host_free(NeonHostHandle* host) {
    if (host == NULL) {
        return;
    }

    JNIEnv *env = get_jni_env();
    if (env == NULL) {
        return;
    }

    Java_com_quietterminal_projectneon_jni_NeonHostJNI_neonHostFree(
        env, NULL, (jlong)(intptr_t)host);
}

const char* neon_get_last_error(void) {
    if (g_error_buffer[0] == '\0') {
        return NULL;
    }
    return g_error_buffer;
}
