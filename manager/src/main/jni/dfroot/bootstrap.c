#include <dirent.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#define BLKROSET   0x125d

/*
 * DirtyFrag 1.15 hands control to this bootstrap after the kernel module is
 * loaded. YueHong deliberately does not import DirtyFrag's SU-manager picker:
 * the existing yhroot_ksu_activate.sh discovers libksud.so and performs the
 * KernelSU hand-off itself.
 */
#define DIRTYFRAG_DIR   "/data/user_de/0/roro.stellar.yuehong/files/dfroot"
#define SCRIPT_PATH     DIRTYFRAG_DIR "/yhroot_ksu_activate.sh"
#define SCRIPT_LOG      DIRTYFRAG_DIR "/yhroot_ksu_activate.log"
#define TMP_ROOT        "/data/local/tmp/yhroot-dirtyfrag"

static int adopt_zygote_env(void)
{
    FILE *f = popen("pidof zygote64 zygote", "r");
    if (!f) return -1;
    int pid = 0;
    fscanf(f, "%d", &pid);
    pclose(f);
    if (!pid) return -1;

    char path[32];
    snprintf(path, sizeof(path), "/proc/%d/environ", pid);
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    static char buf[16384];
    int n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = '\0';
    for (char *p = buf, *end = buf + n; p < end; p += strlen(p) + 1)
        putenv(p);
    return 0;
}

static int should_ro(const char *name)
{
    size_t len = strlen(name);
    if (!strcmp(name, "super"))  return 1;
    if (!strcmp(name, "misc"))   return 1;
    if (!strcmp(name, "steady")) return 1;
    if (len >= 2 && name[len - 2] == '_' &&
        (name[len - 1] == 'a' || name[len - 1] == 'b'))
        return 1;
    return 0;
}

static int set_partitions_ro(void)
{
    DIR *dir = opendir("/dev/block/by-name");
    if (!dir) return -1;

    struct dirent *ent;
    while ((ent = readdir(dir))) {
        if (!should_ro(ent->d_name)) continue;

        char path[128];
        snprintf(path, sizeof(path), "/dev/block/by-name/%s", ent->d_name);
        int fd = open(path, O_RDONLY);
        if (fd < 0) continue;

        struct stat st;
        if (fstat(fd, &st) == 0 && S_ISBLK(st.st_mode)) {
            int on = 1;
            ioctl(fd, BLKROSET, &on);
        }
        close(fd);
    }

    closedir(dir);
    return 0;
}

static int run_ctx(const char *ctx, char *const argv[])
{
    pid_t pid = fork();
    if (pid < 0) return -1;
    if (pid == 0) {
        if (ctx) {
            int fd = open("/proc/self/attr/exec", O_WRONLY);
            if (fd >= 0) {
                write(fd, ctx, strlen(ctx));
                close(fd);
            }
        }
        execv(argv[0], argv);
        _exit(127);
    }

    int status;
    if (waitpid(pid, &status, 0) < 0) return -1;
    return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}

static void touch(const char *path)
{
    int fd = open(path, O_CREAT | O_WRONLY, 0666);
    if (fd >= 0) close(fd);
}

static int run_yhroot_activation(void)
{
    /* The script discovers ksud and its package after DirtyFrag gives it root. */
    unsetenv("YHROOT_KSUD");
    unsetenv("YHROOT_KSU_PACKAGE");
    /* Run the existing script through policy reload, cleanup and enforcing. */
    unsetenv("YHROOT_DIRTYFRAG");
    setenv("YHROOT_TMP_ROOT", TMP_ROOT, 1);
    setenv("YHROOT_LOG", SCRIPT_LOG, 1);
    setenv("YHROOT_PRESERVE_LOG", "1", 1);
    setenv("YHROOT_SKIP_TMP_CLEANUP", "0", 1);

    return run_ctx(NULL, (char *const[]){
        "/system/bin/sh", (char *)SCRIPT_PATH, NULL
    });
}

int main(void)
{
    /* Marker sequence consumed by the YueHong runner. */
    touch("/dev/dfm0");
    touch("/dev/dfm1");
    if (adopt_zygote_env()) touch("/dev/dfmw0");

    touch("/dev/dfm2");
    touch("/dev/dfm3");
    if (set_partitions_ro()) touch("/dev/dfmw1");

    touch("/dev/dfm5");
    int rc = run_yhroot_activation();
    if (rc == 0)
        touch("/dev/dfm6");
    else
        touch("/dev/dfme2");

    /* DirtyFrag's module has a real module_exit after the hand-off. */
    run_ctx(NULL, (char *const[]){ "/system/bin/rmmod", "dfroot", NULL });
    return rc == 0 ? 0 : 1;
}
