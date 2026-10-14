package roro.stellar.yuehong.dfroot;

import android.content.Context;
import android.net.IpSecAlgorithm;
import android.net.IpSecManager;
import android.net.IpSecTransform;
import android.os.Build;

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

/**
 * Stages and runs the DirtyFrag v1.15 executable chain.
 *
 * The native executable is intentionally launched from the APK's extracted
 * native library directory.  The bootstrap hand-off files are staged in the
 * device-protected app directory, whose path is shared with libcxx.S.
 */
public final class DfRootRunner {
    private static final String NATIVE_BIN = "libdfroot.so";
    private static final String BOOTSTRAP_BIN = "libbootstrap.so";
    private static final String SCRIPT_ASSET = "yhroot_ksu_activate.sh";
    private static final String WORK_DIRECTORY = "dfroot";
    private static final String BOOTSTRAP_FILE = "bootstrap";
    private static final String SCRIPT_FILE = "yhroot_ksu_activate.sh";
    private static final String LOG_FILE = "yhroot_ksu_activate.log";

    private DfRootRunner() {
    }

    public static boolean isArmed() {
        return new File("/dev/df").exists();
    }

    public static int run(Context context, DfRootReporter reporter)
            throws Exception {
        if (isArmed()) {
            reporter.report("DirtyFrag is already armed; reboot before retrying.\n");
            return 4;
        }

        Context deContext = context.getApplicationContext()
                .createDeviceProtectedStorageContext();
        File work = new File(deContext.getFilesDir(), WORK_DIRECTORY);
        if (!work.exists() && !work.mkdirs()) {
            throw new IOException("unable to create DirtyFrag staging directory");
        }
        stageBinary(new File(deContext.getApplicationInfo().nativeLibraryDir, BOOTSTRAP_BIN),
                new File(work, BOOTSTRAP_FILE));
        File activationScript = new File(work, SCRIPT_FILE);
        deleteQuietly(activationScript);
        stageAsset(deContext, SCRIPT_ASSET, new File(work, SCRIPT_FILE));
        deleteQuietly(new File(work, LOG_FILE));

        File bin = new File(deContext.getApplicationInfo().nativeLibraryDir, NATIVE_BIN);
        if (!bin.isFile()) throw new IOException(NATIVE_BIN + " is missing from nativeLibraryDir");

        reporter.report("DirtyFrag v1.15: the activation script will discover the installed KernelSU manager.\n");
        reporter.report("DirtyFrag model: " + Build.MANUFACTURER + " " + Build.MODEL + "\n");
        reporter.report("DirtyFrag kernel: " + System.getProperty("os.version", "unknown") + "\n\n");

        IpSecManager ipsec = (IpSecManager) deContext.getSystemService(Context.IPSEC_SERVICE);
        if (ipsec == null) throw new IllegalStateException("IpSecManager unavailable");

        IpSecManager.UdpEncapsulationSocket encapSock =
                ipsec.openUdpEncapsulationSocket();
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
            int rc = runBinary(bin, reporter, encapSock.getPort(), spiObj.getSpi(),
                    aesKey, senderPort);
            reportActivationLog(work, reporter);
            return rc;
        } finally {
            transform.close();
            spiObj.close();
            encapSock.close();
        }
    }

    public static String readActivationLog(Context context) {
        Context deContext = context.getApplicationContext().createDeviceProtectedStorageContext();
        File log = new File(new File(deContext.getFilesDir(), WORK_DIRECTORY), LOG_FILE);
        if (!log.isFile()) return "";
        try (FileInputStream input = new FileInputStream(log)) {
            StringBuilder output = new StringBuilder();
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0; ) {
                if (read > 0) output.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
            return output.toString();
        } catch (IOException ignored) {
            return "";
        }
    }

    public static String verifyRoot() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("/system/bin/sh", "-c", "su -c id")
                .redirectErrorStream(true)
                .start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (output.length() > 0) output.append('\n');
                output.append(line);
            }
        }
        int rc = process.waitFor();
        String result = output.toString().trim();
        return "exit=" + rc + (result.isEmpty() ? "" : "\n" + result);
    }

    private static void reportActivationLog(File work, DfRootReporter reporter) {
        File log = new File(work, LOG_FILE);
        if (!log.isFile()) return;
        reporter.report("\n--- yhroot_ksu_activate.sh ---\n");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(log), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                reporter.report(line + "\n");
            }
        } catch (IOException error) {
            reporter.report("[DirtyFrag] unable to read activation log: " + error + "\n");
        }
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

    private static void stageAsset(Context context, String asset, File destination)
            throws IOException {
        try (InputStream input = context.getAssets().open(asset);
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

    private static void deleteQuietly(File file) {
        if (file.exists()) file.delete();
    }
}
