#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>

typedef char* (*string_call_fn)(const char*);
typedef char* (*noarg_call_fn)(void);
typedef void (*free_fn)(char*);

static void *bridge_handle;
static string_call_fn bridge_start;
static noarg_call_fn bridge_status;
static noarg_call_fn bridge_stop;
static string_call_fn bridge_exit_node;
static string_call_fn bridge_logout;
static free_fn bridge_free;

static int load_bridge(void) {
    if (bridge_handle != NULL) return 1;
    bridge_handle = dlopen("libtailscalebridge.so", RTLD_NOW | RTLD_LOCAL);
    if (bridge_handle == NULL) return 0;
    bridge_start = (string_call_fn)dlsym(bridge_handle, "NativeStart");
    bridge_status = (noarg_call_fn)dlsym(bridge_handle, "NativeStatus");
    bridge_stop = (noarg_call_fn)dlsym(bridge_handle, "NativeStop");
    bridge_exit_node = (string_call_fn)dlsym(bridge_handle, "NativeSetExitNode");
    bridge_logout = (string_call_fn)dlsym(bridge_handle, "NativeLogout");
    bridge_free = (free_fn)dlsym(bridge_handle, "NativeFree");
    return bridge_start && bridge_status && bridge_stop && bridge_exit_node && bridge_logout && bridge_free;
}

static jstring error_json(JNIEnv *env, const char *message) {
    char buffer[256];
    snprintf(buffer, sizeof(buffer), "{\"ok\":false,\"error\":\"%s\"}", message);
    return (*env)->NewStringUTF(env, buffer);
}

static jstring from_go(JNIEnv *env, char *value) {
    if (value == NULL) return error_json(env, "native call returned no data");
    jstring result = (*env)->NewStringUTF(env, value);
    bridge_free(value);
    return result;
}

static const char *get_string(JNIEnv *env, jstring value) {
    return value == NULL ? "" : (*env)->GetStringUTFChars(env, value, NULL);
}

static void release_string(JNIEnv *env, jstring value, const char *chars) {
    if (value != NULL) (*env)->ReleaseStringUTFChars(env, value, chars);
}

JNIEXPORT jstring JNICALL
Java_com_mallocgfw_app_tailscale_TailscaleNative_start(JNIEnv *env, jclass clazz, jstring config) {
    if (!load_bridge()) return error_json(env, "cannot load Tailscale native bridge");
    const char *chars = get_string(env, config);
    char *value = bridge_start(chars);
    release_string(env, config, chars);
    return from_go(env, value);
}

JNIEXPORT jstring JNICALL
Java_com_mallocgfw_app_tailscale_TailscaleNative_status(JNIEnv *env, jclass clazz) {
    if (!load_bridge()) return error_json(env, "cannot load Tailscale native bridge");
    return from_go(env, bridge_status());
}

JNIEXPORT jstring JNICALL
Java_com_mallocgfw_app_tailscale_TailscaleNative_stop(JNIEnv *env, jclass clazz) {
    if (!load_bridge()) return error_json(env, "cannot load Tailscale native bridge");
    return from_go(env, bridge_stop());
}

JNIEXPORT jstring JNICALL
Java_com_mallocgfw_app_tailscale_TailscaleNative_setExitNode(JNIEnv *env, jclass clazz, jstring node) {
    if (!load_bridge()) return error_json(env, "cannot load Tailscale native bridge");
    const char *chars = get_string(env, node);
    char *value = bridge_exit_node(chars);
    release_string(env, node, chars);
    return from_go(env, value);
}

JNIEXPORT jstring JNICALL
Java_com_mallocgfw_app_tailscale_TailscaleNative_logout(JNIEnv *env, jclass clazz, jstring state_dir) {
    if (!load_bridge()) return error_json(env, "cannot load Tailscale native bridge");
    const char *chars = get_string(env, state_dir);
    char *value = bridge_logout(chars);
    release_string(env, state_dir, chars);
    return from_go(env, value);
}
