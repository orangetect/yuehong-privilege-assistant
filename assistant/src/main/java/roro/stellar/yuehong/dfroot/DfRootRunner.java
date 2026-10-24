package roro.stellar.yuehong.dfroot;

import android.content.Context;
import android.content.SharedPreferences;
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

    public static synchronized int run(Context context, DfRootReporter reporter) throws Exception {
        Context appContext = context.getApplicationContext();
        Context deContext = appContext.createDeviceProtectedStorageContext();
        if (isArmed()) {
            reporter.report("already hooked, skipping\n");
            return 0;
        }
        String selectedPackage = KernelSuManagerSelection.INSTANCE.selectedPackage(appContext);
        roro.stellar.yuehong.shell.KernelSuManagerTarget manager =
                KernelSuManagerSelection.INSTANCE.selectedManagerTarget(appContext);
        if (manager == null) {
            throw new IOException("No compatible ksud found for selected manager "
                    + selectedPackage);
        }
        String managerPackage = manager.getPackageName();
        if (!selectedPackage.equals(managerPackage)) {
            throw new IOException("Recognized ksud does not match selected manager "
                    + selectedPackage);
        }

        File work = new File(deContext.getFilesDir(), WORK_DIRECTORY);
        if (!work.exists() && !work.mkdirs()) {
            throw new IOException("unable to create DirtyFrag staging directory");
        }
        File ksud = new File(deContext.getFilesDir().getParentFile(), KSUD_FILE);
        stageManagerKsud(new File(manager.getLibKsudPath()), ksud);
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

        reporter.report("DirtyFrag v1.16: upstream KernelSU activation.\n");
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

    private static void stageManagerKsud(File source, File destination) throws IOException {
        if (!source.isFile()) throw new IOException("selected ksud is missing: " + source);
        stageBinary(source, destination);
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
