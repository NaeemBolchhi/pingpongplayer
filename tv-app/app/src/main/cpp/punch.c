// Frees the disk space of a byte range inside a file without changing its size (a "hole").
// Android's Java API doesn't expose fallocate() with flags, so the torrent window mode calls this.
#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <linux/falloc.h>

#ifndef FALLOC_FL_KEEP_SIZE
#define FALLOC_FL_KEEP_SIZE 0x01
#endif
#ifndef FALLOC_FL_PUNCH_HOLE
#define FALLOC_FL_PUNCH_HOLE 0x02
#endif

// Returns 0 on success, otherwise a negative errno value.
JNIEXPORT jint JNICALL
Java_nb_pingpong_pong_HolePunch_nativePunch(JNIEnv *env, jclass clazz, jstring jpath, jlong offset, jlong length) {
    (void) clazz;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (path == NULL) return -ENOMEM;
    int rc;
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) {
        rc = -errno;
    } else {
        rc = fallocate64(fd, FALLOC_FL_PUNCH_HOLE | FALLOC_FL_KEEP_SIZE, (off64_t) offset, (off64_t) length) == 0
                ? 0 : -errno;
        close(fd);
    }
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return rc;
}
