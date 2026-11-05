//
// jvm_bridge.c — ACL-PE JNI 进程内 JVM 启动桥（仿造 FCL/Pojav 思路，自研实现）
//
// 解决 Android 10+ W^X：不再用 ProcessBuilder exec java 子进程，
// 而是在当前进程内 dlopen libjvm.so → JNI_CreateJavaVM 创建 JVM → 调用 Minecraft 主类。
//
// 结构（仿造 FCL 的 fcl_loader.c）：
//  - launchJvm         : 核心启动（dlopen + CreateJavaVM + main）
//  - redirectStdio     : stdout/stderr → pipe → logcat（游戏日志可查）
//  - setenv            : 环境变量注入（JAVA_HOME / LD_LIBRARY_PATH 等）
//  - setupExitTrap     : 捕获 System.exit，回传退出码
//
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>

#define TAG "ACLPE-JVM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---- JNI_CreateJavaVM 函数指针 ----
typedef jint (*CreateJavaVMFn)(JavaVM **, void **, void *);

// ---- 退出陷阱状态（仿造 FCL nominal_exit）----
static JavaVM *g_jvm = NULL;
static jobject g_exitBridge = NULL;
static jmethodID g_exitMethod = NULL;

// ---- stdout/stderr 重定向管道 ----
static int g_logPipe[2] = {-1, -1};
static pthread_t g_loggerThread;

// ---------------------------------------------------------------------------
// 日志线程：从管道读游戏 stdout/stderr，转发到 logcat
// ---------------------------------------------------------------------------
static void *logger_thread(void *arg) {
    (void) arg;
    char buffer[2048];
    ssize_t n;
    while (1) {
        memset(buffer, 0, sizeof(buffer));
        n = read(g_logPipe[0], buffer, sizeof(buffer) - 1);
        if (n <= 0) {
            if (n < 0) LOGE("log pipe read failed");
            break;
        }
        buffer[n] = '\0';
        // 去掉行尾换行，避免 logcat 每行多空行
        size_t len = strlen(buffer);
        while (len > 0 && (buffer[len - 1] == '\n' || buffer[len - 1] == '\r')) buffer[--len] = '\0';
        if (len > 0) LOGI("%s", buffer);
    }
    return NULL;
}

// ---------------------------------------------------------------------------
// 核心：在进程内启动 JVM 并调用 Minecraft 主类
// 参数（全部来自 Kotlin 侧，已拼好）：
//   jvmLibPath : libjvm.so 的绝对路径（应用私有目录 env/jre/<slot>/arm64/lib/server/libjvm.so）
//   classpath  : client.jar + 全部依赖库（: 分隔）
//   mainClass  : Minecraft 主类（如 net.minecraft.client.main.Main）
//   jvmArgs    : JVM 参数（-Xmx、-Djava.library.path 等）
//   gameArgs   : 游戏参数（--username、--version 等）
// ---------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_org_aclpe_aclpe_core_launch_JniJvmLauncher_launchJvm(
        JNIEnv *env, jobject thiz,
        jstring jvmLibPath, jstring classpath, jstring mainClass,
        jobjectArray jvmArgs, jobjectArray gameArgs) {
    (void) thiz;

    const char *libPath = (*env)->GetStringUTFChars(env, jvmLibPath, NULL);
    const char *cp = (*env)->GetStringUTFChars(env, classpath, NULL);
    const char *mc = (*env)->GetStringUTFChars(env, mainClass, NULL);

    // 1. dlopen libjvm.so
    void *handle = dlopen(libPath, RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        LOGE("dlopen libjvm.so failed: %s", dlerror());
        (*env)->ReleaseStringUTFChars(env, jvmLibPath, libPath);
        (*env)->ReleaseStringUTFChars(env, classpath, cp);
        (*env)->ReleaseStringUTFChars(env, mainClass, mc);
        return -1;
    }
    LOGI("dlopen libjvm.so OK: %s", libPath);

    // 2. 取 JNI_CreateJavaVM
    CreateJavaVMFn createVM = (CreateJavaVMFn) dlsym(handle, "JNI_CreateJavaVM");
    if (!createVM) {
        LOGE("dlsym JNI_CreateJavaVM failed: %s", dlerror());
        dlclose(handle);
        (*env)->ReleaseStringUTFChars(env, jvmLibPath, libPath);
        (*env)->ReleaseStringUTFChars(env, classpath, cp);
        (*env)->ReleaseStringUTFChars(env, mainClass, mc);
        return -2;
    }

    // 3. 构造 JavaVMOption 数组
    //    基础选项：-Djava.class.path、-Djava.library.path、-XstartOnFirstThread（mac 不需要，Android 忽略）
    jsize jvmArgCount = jvmArgs != NULL ? (*env)->GetArrayLength(env, jvmArgs) : 0;
    int optionCount = (int) jvmArgCount + 1;  // +1 给 classpath
    JavaVMOption *options = (JavaVMOption *) calloc((size_t) optionCount, sizeof(JavaVMOption));
    if (!options) {
        LOGE("calloc options failed");
        dlclose(handle);
        return -3;
    }

    // classpath 选项
    size_t cpLen = strlen("-Djava.class.path=") + strlen(cp) + 1;
    char *cpOption = (char *) malloc(cpLen);
    if (!cpOption) { free(options); dlclose(handle); return -4; }
    snprintf(cpOption, cpLen, "-Djava.class.path=%s", cp);
    options[0].optionString = cpOption;

    // 其余 JVM 参数
    for (int i = 0; i < (int) jvmArgCount; i++) {
        jstring js = (jstring) (*env)->GetObjectArrayElement(env, jvmArgs, i);
        const char *arg = (*env)->GetStringUTFChars(env, js, NULL);
        options[i + 1].optionString = strdup(arg);
        (*env)->ReleaseStringUTFChars(env, js, arg);
        (*env)->DeleteLocalRef(env, js);
    }

    JavaVMInitArgs vmArgs;
    vmArgs.version = JNI_VERSION_1_6;
    vmArgs.nOptions = optionCount;
    vmArgs.options = options;
    vmArgs.ignoreUnrecognized = JNI_TRUE;

    // 4. 创建 JVM
    JavaVM *jvm = NULL;
    JNIEnv *jenv = NULL;
    jint result = createVM(&jvm, (void **) &jenv, &vmArgs);
    if (result != JNI_OK || jvm == NULL || jenv == NULL) {
        LOGE("JNI_CreateJavaVM failed: %d", result);
        free(cpOption);
        for (int i = 1; i < optionCount; i++) free((void *) options[i].optionString);
        free(options);
        dlclose(handle);
        return -5;
    }
    LOGI("JNI_CreateJavaVM OK");

    // 5. 调 main 类
    jclass cls = (*jenv)->FindClass(jenv, mc);
    if (!cls) {
        LOGE("FindClass failed: %s", mc);
        (*jvm)->DestroyJavaVM(jvm);
        return -6;
    }
    jmethodID mainMethod = (*jenv)->GetStaticMethodID(jenv, cls, "main", "([Ljava/lang/String;)V");
    if (!mainMethod) {
        LOGE("GetStaticMethodID main failed");
        (*jvm)->DestroyJavaVM(jvm);
        return -7;
    }

    // 游戏参数 String[]
    jsize gameArgCount = gameArgs != NULL ? (*env)->GetArrayLength(env, gameArgs) : 0;
    jclass stringClass = (*jenv)->FindClass(jenv, "java/lang/String");
    jobjectArray gameArgArray = (*jenv)->NewObjectArray(jenv, gameArgCount, stringClass, NULL);
    for (int i = 0; i < (int) gameArgCount; i++) {
        jstring js = (jstring) (*env)->GetObjectArrayElement(env, gameArgs, i);
        (*jenv)->SetObjectArrayElement(jenv, gameArgArray, i, js);
        (*jenv)->DeleteLocalRef(jenv, js);
    }

    LOGI("calling main: %s", mc);
    (*jenv)->CallStaticVoidMethod(jenv, cls, mainMethod, gameArgArray);

    // main 返回后（正常情况不会），销毁 JVM
    (*jvm)->DestroyJavaVM(jvm);

    // 清理
    (*env)->ReleaseStringUTFChars(env, jvmLibPath, libPath);
    (*env)->ReleaseStringUTFChars(env, classpath, cp);
    (*env)->ReleaseStringUTFChars(env, mainClass, mc);
    free(cpOption);
    for (int i = 1; i < optionCount; i++) free((void *) options[i].optionString);
    free(options);
    dlclose(handle);
    return 0;
}

// ---------------------------------------------------------------------------
// 重定向 stdout/stderr 到 logcat（游戏日志可查）
// ---------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_org_aclpe_aclpe_core_launch_JniJvmLauncher_redirectStdio(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    if (g_logPipe[0] != -1) return 0;  // 已重定向
    if (pipe(g_logPipe) < 0) {
        LOGE("pipe failed");
        return 1;
    }
    if (dup2(g_logPipe[1], STDOUT_FILENO) != STDOUT_FILENO ||
        dup2(g_logPipe[1], STDERR_FILENO) != STDERR_FILENO) {
        LOGE("dup2 failed");
        return 2;
    }
    close(g_logPipe[1]);
    int rc = pthread_create(&g_loggerThread, NULL, logger_thread, NULL);
    if (rc != 0) {
        LOGE("pthread_create failed: %d", rc);
        return 3;
    }
    pthread_detach(g_loggerThread);
    LOGI("stdio redirected to logcat");
    return 0;
}

// ---------------------------------------------------------------------------
// 环境变量注入（仿造 FCL setenv）
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_org_aclpe_aclpe_core_launch_JniJvmLauncher_setenv(
        JNIEnv *env, jobject thiz, jstring name, jstring value) {
    (void) thiz;
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    const char *v = (*env)->GetStringUTFChars(env, value, NULL);
    setenv(n, v, 1);
    (*env)->ReleaseStringUTFChars(env, name, n);
    (*env)->ReleaseStringUTFChars(env, value, v);
}

// ---------------------------------------------------------------------------
// 退出陷阱：捕获游戏 System.exit，把退出码回传 Kotlin
// ---------------------------------------------------------------------------
static void nominal_exit(int code) {
    JNIEnv *env = NULL;
    if (g_jvm != NULL) {
        jint rc = (*g_jvm)->GetEnv(g_jvm, (void **) &env, JNI_VERSION_1_6);
        if (rc == JNI_EDETACHED) {
            (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
        }
    }
    if (env != NULL && g_exitBridge != NULL && g_exitMethod != NULL && code != 0) {
        (*env)->CallVoidMethod(env, g_exitBridge, g_exitMethod, (jint) code);
    }
    LOGI("game exit trap: code=%d", code);
    // 回退：直接退出进程（游戏结束）
    _exit(code);
}

JNIEXPORT void JNICALL
Java_org_aclpe_aclpe_core_launch_JniJvmLauncher_setupExitTrap(
        JNIEnv *env, jobject thiz, jobject bridge) {
    (void) thiz;
    g_exitBridge = (*env)->NewGlobalRef(env, bridge);
    (*env)->GetJavaVM(env, &g_jvm);
    jclass cls = (*env)->FindClass(env, "org/aclpe/aclpe/core/launch/JniJvmLauncher");
    g_exitMethod = (*env)->GetMethodID(env, cls, "onGameExit", "(I)V");
}
