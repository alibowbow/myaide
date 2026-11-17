#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

/* Never substitute rename()/link()/copy+delete: unsupported kernels/filesystems fail closed.
 * RENAME_NOREPLACE prevents target clobber atomically. It does not assert source inode identity:
 * Java must verify the moved identity and retain/report unexpected moves for recovery. */
static void fail(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/io/IOException");
    if (type != NULL) (*env)->ThrowNew(env, type, message);
}
static int name_bytes(JNIEnv *env, jbyteArray input, char output[256]) {
    if (input == NULL) { fail(env, "Missing basename"); return 0; }
    jsize n = (*env)->GetArrayLength(env, input);
    if (n < 1 || n > 255) { fail(env, "Invalid basename length"); return 0; }
    (*env)->GetByteArrayRegion(env, input, 0, n, (jbyte *)output);
    if ((*env)->ExceptionCheck(env)) return 0;
    for (jsize i = 0; i < n; ++i) {
        unsigned char c = (unsigned char)output[i];
        if (c == 0 || c == '/' || c == '\\' || c < 32 || c == 127) { fail(env, "Invalid basename"); return 0; }
    }
    output[n] = '\0';
    if (!strcmp(output, ".") || !strcmp(output, "..")) { fail(env, "Invalid basename"); return 0; }
    return 1;
}
JNIEXPORT void JNICALL Java_dev_jun_gyeot_TrashNative_moveNoReplaceNative(
    JNIEnv *env, jclass ignored, jint source_fd, jbyteArray source, jint target_fd, jbyteArray target) {
    (void)ignored;
    char from[256], to[256];
    if (!name_bytes(env, source, from) || !name_bytes(env, target, to)) return;
    struct stat source_parent, target_parent;
    if (source_fd < 0 || target_fd < 0 || fstat(source_fd, &source_parent) != 0 || fstat(target_fd, &target_parent) != 0 || !S_ISDIR(source_parent.st_mode) || !S_ISDIR(target_parent.st_mode)) {
        fail(env, "Invalid pinned directory handles"); return;
    }
#ifdef __NR_renameat2
    /* Exactly one syscall, fixed RENAME_NOREPLACE (1); EINTR is not blindly retried. */
    if (syscall(__NR_renameat2, source_fd, from, target_fd, to, 1U) == 0) return;
    int error = errno;
    char message[96];
    snprintf(message, sizeof(message), "No-overwrite move failed (errno %d); inspect state before retry", error);
    fail(env, message);
#else
    fail(env, "Atomic no-overwrite move unsupported; no mutation attempted");
#endif
}
