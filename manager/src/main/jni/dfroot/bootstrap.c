#include <dirent.h>
#include <errno.h>
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
 * the selected activation script performs the APatch or KernelSU hand-off
 * itself.  The kernel trampoline starts this process with an empty
 * environment, so the Java runner also writes a mode marker in DIRTYFRAG_DIR;
 * the environment remains the first choice for callers that can preserve it.
 */
#define DIRTYFRAG_DIR   "/data/user_de/0/roro.stellar.yuehong/files/dfroot"
#define MODE_ENV        "YHROOT_ACTIVATION_MODE"
#define MODE_FILE       DIRTYFRAG_DIR "/.yhroot_activation_mode"
#define KSU_SCRIPT_PATH DIRTYFRAG_DIR "/yhroot_ksu_activate.sh"
#define AP_SCRIPT_PATH  DIRTYFRAG_DIR "/yhroot_ap_activate.sh"
#define GENERIC_SCRIPT_PATH DIRTYFRAG_DIR "/yhroot_activate.sh"
#define KSU_SCRIPT_LOG  DIRTYFRAG_DIR "/yhroot_ksu_activate.log"
#define AP_SCRIPT_LOG   DIRTYFRAG_DIR "/yhroot_ap_activate.log"
#define HELPER_DIR      DIRTYFRAG_DIR "/.yhroot-bin"
#define TIMEOUT_HELPER  HELPER_DIR "/timeout"
#define LOAD_POLICY_HELPER HELPER_DIR "/load_policy"
#define SU_HELPER       HELPER_DIR "/su"
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

static int write_executable(const char *path, const char *contents)
{
    int fd = open(path, O_CREAT | O_WRONLY | O_TRUNC, 0700);
    if (fd < 0) return -1;
    size_t length = strlen(contents);
    ssize_t written = write(fd, contents, length);
    int ok = written == (ssize_t)length && fchmod(fd, 0700) == 0;
    close(fd);
    return ok ? 0 : -1;
}

static int prepare_activation_helpers(void)
{
    static const char timeout_script[] =
        "#!/system/bin/sh\n"
        "LIMIT=\"$1\"\n"
        "shift\n"
        "[ -n \"$LIMIT\" ] && [ $# -gt 0 ] || exit 2\n"
        "COMMAND=\"$1\"\n"
        "shift\n"
        "if [ \"$COMMAND\" = load_policy ]; then\n"
        "  /system/bin/load_policy \"$@\" &\n"
        "else\n"
        "  \"$COMMAND\" \"$@\" &\n"
        "fi\n"
        "PID=$!\n"
        "( /system/bin/sleep \"$LIMIT\"; /system/bin/kill -9 \"$PID\" 2>/dev/null ) &\n"
        "WATCH=$!\n"
        "wait \"$PID\" 2>/dev/null\n"
        "RC=$?\n"
        "/system/bin/kill \"$WATCH\" 2>/dev/null\n"
        "exit \"$RC\"\n";
    static const char load_policy_script[] =
        "#!/system/bin/sh\n"
        "/system/bin/load_policy \"$@\" &\n"
        "PID=$!\n"
        "wait \"$PID\" 2>/dev/null\n"
        "exit $?\n";
    static const char su_script[] =
        "#!/system/bin/sh\n"
        "if [ \"$1\" = -c ]; then\n"
        "  shift\n"
        "  exec /system/bin/sh -c \"${1:-}\"\n"
        "fi\n"
        "exec /system/bin/su \"$@\"\n";
    if (mkdir(HELPER_DIR, 0700) < 0 && errno != EEXIST) return -1;
    if (write_executable(TIMEOUT_HELPER, timeout_script) < 0) return -1;
    if (write_executable(LOAD_POLICY_HELPER, load_policy_script) < 0) return -1;
    if (write_executable(SU_HELPER, su_script) < 0) return -1;

    const char *old_path = getenv("PATH");
    char path[512];
    int n = snprintf(path, sizeof(path), "%s:%s", HELPER_DIR,
                     old_path && *old_path ? old_path : "/system/bin");
    if (n < 0 || (size_t)n >= sizeof(path)) return -1;
    return setenv("PATH", path, 1);
}

static void remove_activation_helpers(void)
{
    unlink(TIMEOUT_HELPER);
    unlink(LOAD_POLICY_HELPER);
    unlink(SU_HELPER);
    rmdir(HELPER_DIR);
}

static void clear_dirtyfrag_markers(void)
{
    static const char *const markers[] = {
        "/dev/df", "/dev/dfm0", "/dev/dfm1", "/dev/dfm2", "/dev/dfm3",
        "/dev/dfm5", "/dev/dfm6", "/dev/dfme2", "/dev/dfmw0", "/dev/dfmw1",
    };
    for (size_t i = 0; i < sizeof(markers) / sizeof(markers[0]); i++)
        unlink(markers[i]);
}

static const char *read_mode_marker(void)
{
    static char mode[16];
    FILE *f = fopen(MODE_FILE, "r");
    if (!f) return NULL;
    if (!fgets(mode, sizeof(mode), f)) {
        fclose(f);
        return NULL;
    }
    fclose(f);
    mode[strcspn(mode, "\r\n \t")] = '\0';
    if (!strcmp(mode, "apatch") || !strcmp(mode, "kernelsu")) return mode;
    return NULL;
}

static const char *activation_mode(void)
{
    const char *mode = getenv(MODE_ENV);
    if (mode && (!strcmp(mode, "apatch") || !strcmp(mode, "kernelsu"))) {
        return mode;
    }
    mode = read_mode_marker();
    return mode ? mode : "kernelsu";
}

static int run_yhroot_activation(void)
{
    const char *mode = activation_mode();
    const int apatch = !strcmp(mode, "apatch");
    const char *script_path = apatch ? AP_SCRIPT_PATH : KSU_SCRIPT_PATH;
    const char *script_log = apatch ? AP_SCRIPT_LOG : KSU_SCRIPT_LOG;
    if (access(script_path, X_OK) != 0) script_path = GENERIC_SCRIPT_PATH;

    /* The selected script discovers the installed manager after DirtyFrag
       gives it root. Keep these values explicit for scripts that use them. */
    unsetenv("YHROOT_KSUD");
    unsetenv("YHROOT_KSU_PACKAGE");
    setenv(MODE_ENV, mode, 1);
    unsetenv("YHROOT_DIRTYFRAG");
    setenv("YHROOT_TMP_ROOT", TMP_ROOT, 1);
    setenv("YHROOT_LOG", script_log, 1);
    setenv("YHROOT_PRESERVE_LOG", "1", 1);
    setenv("YHROOT_SKIP_TMP_CLEANUP", "0", 1);

    /*
     * load_policy can invalidate the SELinux SID of the shell that launched
     * DirtyFrag. Keep the activation script unchanged, but route its timeout
     * and cleanup `su` calls through private helpers. The load_policy child can
     * be killed by the policy transition while the script records the result
     * and continues to restore enforcing mode.
     */
    if (prepare_activation_helpers() < 0) return -1;

    int rc = run_ctx(NULL, (char *const[]){
        "/system/bin/sh", (char *)script_path, NULL
    });
    remove_activation_helpers();
    return rc;
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
    /*
     * The marker files are a per-run mutex and progress channel, not a root
     * state store. Remove them after the module is gone so the next run can
     * arm DirtyFrag again without requiring a reboot.
     */
    clear_dirtyfrag_markers();
    return rc == 0 ? 0 : 1;
}
