/*
 * Moves the wall clock of a process by FAKE_OFFSET_SECONDS, for running the server and the dev
 * login provider "on another day" without changing the machine's clock. Used through
 * `with-clock.sh`, which builds this file and sets LD_PRELOAD.
 *
 * Only the real-time clock moves (time, gettimeofday, clock_gettime on CLOCK_REALTIME); monotonic
 * clocks keep running as they are, so timeouts behave. It works for programs that call the C
 * library for the time (the JVM, Python), not for static binaries.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdlib.h>
#include <sys/time.h>
#include <time.h>

static long offset(void) {
    static long cached = -1;
    if (cached < 0) {
        const char *text = getenv("FAKE_OFFSET_SECONDS");
        cached = text ? atol(text) : 0;
    }
    return cached;
}

int clock_gettime(clockid_t clock, struct timespec *out) {
    static int (*real)(clockid_t, struct timespec *);
    if (!real) real = dlsym(RTLD_NEXT, "clock_gettime");
    int result = real(clock, out);
    if (clock == CLOCK_REALTIME || clock == CLOCK_REALTIME_COARSE) out->tv_sec += offset();
    return result;
}

int gettimeofday(struct timeval *out, void *zone) {
    static int (*real)(struct timeval *, void *);
    if (!real) real = dlsym(RTLD_NEXT, "gettimeofday");
    int result = real(out, zone);
    out->tv_sec += offset();
    return result;
}

time_t time(time_t *out) {
    struct timespec now;
    clock_gettime(CLOCK_REALTIME, &now);
    if (out) *out = now.tv_sec;
    return now.tv_sec;
}
