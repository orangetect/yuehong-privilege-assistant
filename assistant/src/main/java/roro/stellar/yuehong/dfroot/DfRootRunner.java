package roro.stellar.yuehong.dfroot;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.net.IpSecAlgorithm;
import android.net.IpSecManager;
import android.net.IpSecTransform;
import android.os.Build;
import roro.stellar.yuehong.shell.KernelSuManagerSelection;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Stages the selected KernelSU manager and runs the upstream DirtyFrag chain. */
public final class DfRootRunner {
    private static final String NATIVE_BIN = "libdfroot.so";
    private static final String BOOTSTRAP_BIN = "libbootstrap.so";
    private static final String WORK_DIRECTORY = "dfroot";
    private static final String BOOTSTRAP_FILE = "bootstrap";
    private static final String KSUD_FILE = "ksud";
    private static final String PREFS_NAME = "dfroot";
    private static final String PREF_SU_MANAGER = "su_manager";
    private static final String PREF_SOFT_REBOOT = "soft_reboot";
    private static final String PREF_AUTO_SOFT_REBOOT = "auto_soft_reboot";
    private static final String PREF_DISABLE_MODULES = "disable_modules";

    private DfRootRunner() {
    }

    public static boolean isArmed() {
        return new File("/dev/df").exists();
    }

    public static int run(Context context, DfRootReporter reporter) throws Exception {
        Context appContext = context.getApplicationContext();
        Context deContext = appContext.createDeviceProtectedStorageContext();
        String managerPackage = KernelSuManagerSelection.INSTANCE.selectedPackage(appContext);
        ApplicationInfo manager = appContext.getPackageManager()
                .getApplicationInfo(managerPackage, 0);

        File work = new File(deContext.getFilesDir(), WORK_DIRECTORY);
        if (!work.exists() && !work.mkdirs()) {
            throw new IOException("unable to create DirtyFrag staging directory");
        }
        File ksud = new File(deContext.getFilesDir().getParentFile(), KSUD_FILE);
        stageManagerKsud(manager, ksud);
        stageBinary(new File(deContext.getApplicationInfo().nativeLibraryDir, BOOTSTRAP_BIN),
                new File(work, BOOTSTRAP_FILE));

        // The native bootstrap reads these DE preferences after the kernel hand-off.
        SharedPreferences prefs = deContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean autoSoftReboot = prefs.getBoolean(PREF_AUTO_SOFT_REBOOT, false);
        if (!prefs.edit()
                .putString(PREF_SU_MANAGER, managerPackage)
                .putBoolean(PREF_SOFT_REBOOT, autoSoftReboot)
                .putBoolean(PREF_DISABLE_MODULES, false)
                .commit()) {
            throw new IOException("unable to persist DirtyFrag preferences");
        }

        File bin = new File(deContext.getApplicationInfo().nativeLibraryDir, NATIVE_BIN);
        if (!bin.isFile()) throw new IOException(NATIVE_BIN + " is missing from nativeLibraryDir");

        reporter.report("DirtyFrag v1.15: upstream KernelSU activation.\n");
        reporter.report("DirtyFrag manager package: " + managerPackage + "\n");
        reporter.report("DirtyFrag model: " + Build.MANUFACTURER + " " + Build.MODEL + "\n");
        reporter.report("DirtyFrag kernel: " + System.getProperty("os.version", "unknown") + "\n\n");

        IpSecManager ipsec = (IpSecManager) deContext.getSystemService(Context.IPSEC_SERVICE);
        if (ipsec == null) throw new IllegalStateException("IpSecManager unavailable");
        IpSecManager.UdpEncapsulationSocket encapSock = ipsec.openUdpEncapsulationSocket();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        IpSecManager.SecurityParameterIndex spiObj =
                ipsec.allocateSecurityParameterIndex(loopback);
        byte[] aesKey = new byte[32];
        new SecureRandom().nextBytes(aesKey);
        DatagramSocket senderSock = new DatagramSocket();
        int senderPort = senderSock.getLocalPort();
        senderSock.close();
        IpSecTransform transform = new IpSecTransform.Builder(deContext)
                .setEncryption(new IpSecAlgorithm(IpSecAlgorithm.CRYPT_AES_CBC, aesKey))
                .setIpv4Encapsulation(encapSock, senderPort)
                .buildTransportModeTransform(loopback, spiObj);
        try {
            return runBinary(bin, reporter, encapSock.getPort(), spiObj.getSpi(),
                    aesKey, senderPort);
        } finally {
            transform.close();
            spiObj.close();
            encapSock.close();
        }
    }

    private static void stageManagerKsud(ApplicationInfo manager, File destination)
            throws IOException {
        File extracted = new File(manager.nativeLibraryDir, "libksud.so");
        if (extracted.isFile()) {
            stageBinary(extracted, destination);
            return;
        }
        List<String> apks = new ArrayList<>();
        if (manager.sourceDir != null) apks.add(manager.sourceDir);
        if (manager.splitSourceDirs != null) {
            for (String split : manager.splitSourceDirs) if (split != null) apks.add(split);
        }
        // Do not accept the first native entry from a multi-ABI APK: ZIP entry
        // order is not stable and could select an incompatible 32-bit ksud.
        String[] abiEntries = {"lib/arm64-v8a/libksud.so", "lib/arm64/libksud.so"};
        for (String apk : apks) {
            try (ZipFile zip = new ZipFile(apk)) {
                for (String abiEntry : abiEntries) {
                    ZipEntry match = zip.getEntry(abiEntry);
                    if (match == null || match.isDirectory()) continue;
                    try (InputStream input = zip.getInputStream(match);
                         FileOutputStream output = new FileOutputStream(destination)) {
                        input.transferTo(output);
                    }
                    if (!destination.setExecutable(true, false)) {
                        throw new IOException("unable to mark staged ksud executable");
                    }
                    return;
                }
            } catch (IOException ignored) {
                // Try the next split APK before reporting a missing library.
            }
        }
        throw new IOException("libksud.so not found for selected manager "
                + manager.packageName);
    }

    private static void stageBinary(File source, File destination) throws IOException {
        if (!source.isFile()) throw new IOException(source + " not found");
        try (InputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            input.transferTo(output);
        }
        if (!destination.setExecutable(true, false)) {
            throw new IOException("unable to mark " + destination + " executable");
        }
    }

    private static int runBinary(File bin, DfRootReporter reporter, int encapPort,
                                 int spi, byte[] aesKey, int senderPort) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(bin.getAbsolutePath());
        command.add("--encap-port");
        command.add(String.valueOf(encapPort));
        command.add("--sender-port");
        command.add(String.valueOf(senderPort));
        command.add("--spi");
        command.add(Integer.toUnsignedString(spi));
        command.add("--aes-key");
        command.add(hex(aesKey));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                reporter.report(line + "\n");
            }
        }
        return process.waitFor();
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte item : value) out.append(String.format("%02x", item & 0xff));
        return out.toString();
    }
}
