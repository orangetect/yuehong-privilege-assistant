package roro.stellar.yuehong.ghostlock;

import roro.stellar.yuehong.R;
import roro.stellar.yuehong.shell.GhostLockOtaApi;
import roro.stellar.yuehong.shell.KernelSuManagerSelection;

import android.app.AlertDialog;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

public class GhostLockActivity extends ComponentActivity {
    private static final String TAG = "GhostLockApp";
    private static final String BINARY_NAME = "libghostlock.so";
    private static final String EXTRACT_NAME = "libextract.so";
    private static final String PREFS = "ghostlock_prefs";
    private static final String PREF_BUILTIN_OFFSET_STATUS = "builtin_offset_status";
    private static final String STATUS_FAILED_PREFIX = "failed:";
    private static final String STATUS_SUCCESS_PREFIX = "success:";
    private static final int MAX_NATIVE_ATTEMPTS = 2;
    private static final long NATIVE_RETRY_DELAY_MS = 1500L;
    private static final String MANAGER_UNAVAILABLE_MARKER =
            "selected manager ksud unavailable after root handoff";
    private static final int COLOR_RED = 0xFFFF6B6B;
    private static final int COLOR_GREEN = 0xFF5FD68A;
    private static final int COLOR_YELLOW = 0xFFFFC94D;
    private static final int COLOR_BLUE = 0xFF60A5FA;
    private static final int COLOR_NONE = -1;
    private static final int ATTACH_XBL = 1;
    private static final int ATTACH_UEFI = 2;
    private static final int ATTACH_VENDOR_BOOT = 4;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean automaticFlowRunning = new AtomicBoolean(false);
    private final StringBuilder logBuffer = new StringBuilder();
    private final Map<View, ValueAnimator> viewAnimators = new HashMap<>();
    private TextView deviceInfo;
    private TextView logView;
    private ScrollView logScroll;
    private Button copyButton;
    private Button autoRootButton;
    private Button localImageButton;
    private EditText otaUrlInput;
    private GhostLockOtaApi ghostLockOtaApi;
    private View rootView;
    private ActivityResultLauncher<String[]> localImagePicker;
    private ActivityResultLauncher<String[]> localBootImagesPicker;
    private LocalImagePart pendingLocalImagePart;
    private File localBootImage;
    private final List<String> localImageExtractorArgs = new ArrayList<>();
    private int pendingLocalImageAttachments;

    /**
     * Returns the value when it looks like a real device name, else null.
     */
    private static String validDeviceName(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return null;
        }
        String lower = v.toLowerCase(Locale.ROOT);
        if (lower.contains("unknown") || lower.contains("null")) {
            return null;
        }
        return v;
    }

    @SuppressLint("PrivateApi")
    private static String getSystemProperty(String key) {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            Object value = props.getMethod("get", String.class).invoke(null, key);
            return value instanceof String ? (String) value : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * Color a whole log line by its leading marker. The native binary only
     * colors the "[..]" prefix (message text stays default) and the script log
     * is plain text, so per-line coloring is what makes the log readable.
     */
    private static CharSequence colorize(String line) {
        int color = markerColor(line);
        if (color == COLOR_NONE) {
            return line;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder(line);
        sb.setSpan(new ForegroundColorSpan(color), 0, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    /**
     * Marker of the leading "[x] " tag, or 0 if the line has none.
     */
    private static char markerOf(String line) {
        return line.length() > 2 && line.charAt(0) == '[' && line.charAt(2) == ']' ? line.charAt(1) : 0;
    }

    /**
     * True for W1/W2/W3 stage round lines (progress or in-round failure).
     */
    private static boolean isWriteRound(String msg) {
        String stage = msg.startsWith("=== ") ? msg.substring(4) : msg;
        return stage.startsWith("W1") || stage.startsWith("W2") || stage.startsWith("W3") || stage.startsWith("Write 1");
    }

    private static int markerColor(String line) {
        char marker = markerOf(line);
        /* W-round progress renders blue; in-round failures keep red. */
        if (isWriteRound(logMessage(line))) {
            if (marker == '-' || marker == '!') return COLOR_RED;
            return COLOR_BLUE;
        }
        if (marker == '+') return COLOR_GREEN;
        if (marker == '-' || marker == '!') return COLOR_RED;
        if (marker == '*') return COLOR_YELLOW;
        if (line.startsWith("error") || line.startsWith("Error")) return COLOR_RED;
        if (line.startsWith("warning")) return COLOR_YELLOW;
        return COLOR_NONE;
    }

    /**
     * Strip leading "[..] " tags (marker, TIMER) and return the message.
     */
    private static String logMessage(String line) {
        String s = line;
        while (s.startsWith("[")) {
            int end = s.indexOf(']');
            if (end < 0) break;
            s = s.substring(end + 1);
            if (s.startsWith(" ")) s = s.substring(1);
        }
        return s;
    }

    private static String stripAnsi(String input) {
        return input.replaceAll("\u001B\\[[;\\d]*m", "");
    }

    private static void setViewColor(View view, int color) {
        if (view.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) view.getBackground().mutate()).setColor(color);
        } else {
            view.setBackgroundTintList(ColorStateList.valueOf(color));
        }
    }

    private static String firstValidProperty(String... keys) {
        for (String key : keys) {
            String value = validDeviceName(getSystemProperty(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ghostlock);
        localImagePicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onLocalImagePicked);
        localBootImagesPicker = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(), this::onLocalBootImagesPicked);
        setupSystemBars();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::finish);
        }

        rootView = findViewById(R.id.root);
        deviceInfo = findViewById(R.id.deviceInfo);
        logView = findViewById(R.id.logView);
        logScroll = findViewById(R.id.logScroll);
        copyButton = findViewById(R.id.copyButton);
        autoRootButton = findViewById(R.id.autoRootButton);
        localImageButton = findViewById(R.id.localImageButton);
        otaUrlInput = findViewById(R.id.otaUrlInput);
        ghostLockOtaApi = new GhostLockOtaApi(this);

        applyWindowInsetsPadding();
        deviceInfo.setText(buildDeviceSummary());
        applyKernelStatus();
        setRunState(RunState.IDLE);

        copyButton.setOnClickListener(v -> copyLogs());
        autoRootButton.setOnClickListener(v -> startSelectedPrivilege());
        localImageButton.setOnClickListener(v -> beginLocalImageParse());
        installPressMotion(copyButton, autoRootButton, localImageButton);
        playEntryMotion();
    }

    private void startSelectedPrivilege() {
        startAutomaticPrivilege();
    }

    private boolean isBuiltinOffsetBlocked(String kernelRelease) {
        String status = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_BUILTIN_OFFSET_STATUS, "");
        return (STATUS_FAILED_PREFIX + kernelRelease).equals(status);
    }

    private void markBuiltinOffsetFailed(String kernelRelease) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_BUILTIN_OFFSET_STATUS, STATUS_FAILED_PREFIX + kernelRelease)
                .commit();
    }

    private void markBuiltinOffsetSuccess(String kernelRelease) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_BUILTIN_OFFSET_STATUS, STATUS_SUCCESS_PREFIX + kernelRelease)
                .commit();
    }

    private boolean isValidOtaUrl(String value) { return value.startsWith("https://") || value.startsWith("http://"); }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    protected void onDestroy() {
        if (ghostLockOtaApi != null) {
            ghostLockOtaApi.close();
        }
        worker.shutdownNow();
        super.onDestroy();
    }

    private void setupSystemBars() {
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller == null) {
            return;
        }
        int lightStatus = getResources().getBoolean(R.bool.ghostlock_window_light_status_bar) ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0;
        int lightNav = getResources().getBoolean(R.bool.ghostlock_window_light_navigation_bar) ? WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0;
        controller.setSystemBarsAppearance(lightStatus | lightNav, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
    }

    /** 与 Compose 页面一致的轻量入场和按压反馈，不改变点击与无障碍行为。 */
    private void playEntryMotion() {
        rootView.animate().cancel();
        rootView.setAlpha(0f);
        rootView.setTranslationY(dp(12));
        rootView.post(() -> rootView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(260L)
                .setInterpolator(new DecelerateInterpolator())
                .start());
    }

    private void installPressMotion(View... views) {
        for (View view : views) {
            view.setOnTouchListener((target, event) -> {
                if (!target.isEnabled()) {
                    return false;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    target.animate().cancel();
                    target.animate()
                            .scaleX(0.975f)
                            .scaleY(0.975f)
                            .alpha(0.92f)
                            .setDuration(90L)
                            .start();
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP ||
                        event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    target.animate().cancel();
                    target.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(150L)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                }
                return false;
            });
        }
    }

    private void cancelViewAnimation(View view) {
        ValueAnimator running = viewAnimators.remove(view);
        if (running != null) {
            running.cancel();
        }
    }

    /**
     * Smoothly reveal a view: animate its height from 0 to the measured
     * content height while fading in.  The measure uses the parent width and
     * an unconstrained height, so padding and child margins are included in
     * the final size.
     */
    private void animateShow(View view) {
        cancelViewAnimation(view);
        final ViewGroup.LayoutParams lp = view.getLayoutParams();
        int width = view.getParent() instanceof View ? ((View) view.getParent()).getWidth() : 0;
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int target = Math.max(view.getMeasuredHeight(), 1);
        lp.height = 0;
        view.setVisibility(View.VISIBLE);
        view.setAlpha(0f);
        ValueAnimator anim = ValueAnimator.ofInt(0, target);
        anim.addUpdateListener(a -> {
            lp.height = (int) a.getAnimatedValue();
            view.setAlpha(a.getAnimatedFraction());
            view.requestLayout();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                view.setAlpha(1f);
                view.requestLayout();
            }
        });
        anim.setDuration(220);
        viewAnimators.put(view, anim);
        anim.start();
    }

    /**
     * Smoothly hide a view: animate its height to 0 while fading out.
     */
    private void animateHide(View view) {
        cancelViewAnimation(view);
        final ViewGroup.LayoutParams lp = view.getLayoutParams();
        final int start = view.getHeight();
        ValueAnimator anim = ValueAnimator.ofInt(start, 0);
        anim.addUpdateListener(a -> {
            lp.height = (int) a.getAnimatedValue();
            view.setAlpha(1f - a.getAnimatedFraction());
            view.requestLayout();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                view.setVisibility(View.GONE);
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                view.setAlpha(1f);
            }
        });
        anim.setDuration(180);
        viewAnimators.put(view, anim);
        anim.start();
    }

    /** Selects the best available profile, in cache → APK → OTA order. */
    private void startAutomaticPrivilege() {
        if (running.get() || !automaticFlowRunning.compareAndSet(false, true)) {
            return;
        }
        setRunState(RunState.RUNNING);
        String suppliedOta = otaUrlInput.getText().toString().trim();
        if (!suppliedOta.isEmpty()) {
            appendLog("custom full OTA URL will be used only after cached and APK profiles");
        }
        String kernelRelease = System.getProperty("os.version", "");
        worker.execute(() -> {
            GhostLockResolvedProfile cached = GhostLockProfileResolver.loadCached(this, kernelRelease);
            GhostLockResolvedProfile builtin = cached == null
                    ? GhostLockProfileResolver.loadBuiltin(this, kernelRelease)
                    : null;
            ui.post(() -> {
                if (cached != null) {
                    appendLog("profile source=cache; using cached profile before APK built-ins");
                    startExploit(cached, ProfileFallback.APK_BUILTIN_THEN_OTA);
                    return;
                }
                if (builtin != null) {
                    if (isBuiltinOffsetBlocked(kernelRelease)) {
                        appendLog("APK built-in profile previously failed; continuing to full OTA");
                        requestAutomaticOta();
                        return;
                    }
                    markBuiltinOffsetFailed(kernelRelease);
                    appendLog("profile source=APK built-in; exact kernel release matched");
                    startExploit(builtin, ProfileFallback.OTA);
                    return;
                }
                appendLog("no cached or APK built-in profile matches; continuing to full OTA");
                requestAutomaticOta();
            });
        });
    }

    private void requestAutomaticOta() {
        autoRootButton.setText(R.string.ghostlock_action_resolving_ota);
        String suppliedOta = otaUrlInput.getText().toString().trim();
        if (!suppliedOta.isEmpty()) {
            if (!isValidOtaUrl(suppliedOta)) {
                automaticFlowRunning.set(false);
                setRunState(RunState.FAILED);
                toast(R.string.ghostlock_parse_failed_url);
                return;
            }
            appendLog("profile source=full OTA; using supplied URL as the final fallback");
            runExtract(suppliedOta, true);
            return;
        }
        appendLog("requesting current full OTA through protocol v2");
        ghostLockOtaApi.resolve(result -> {
            if (!result.getSuccessful()) {
                automaticFlowRunning.set(false);
                setRunState(RunState.FAILED);
                appendLog("ROM V2 error: " + result.getErrorMessage());
                toast(R.string.ghostlock_auto_ota_failed);
                return;
            }
            String url = result.getOtaUrl();
            otaUrlInput.setText(url);
            appendLog("ROM V2 provider=" + result.getProvider());
            appendLog("ROM V2 OTA: " + url);
            runExtract(url, true);
        });
    }

    private void runExtract(String input, boolean automaticFlow) {
        List<String> arguments = new ArrayList<>();
        arguments.add(input);
        runExtract(arguments, automaticFlow, "full OTA: " + input, GhostLockProfileSource.OTA, false);
    }

    private void runExtract(
            List<String> sourceArguments,
            boolean automaticFlow,
            String sourceDescription,
            GhostLockProfileSource profileSource,
            boolean clearLocalImages) {
        worker.execute(() -> {
            int code = 1;
            GhostLockResolvedProfile profile = null;
            File parsed = new File(getFilesDir(), "ghostlock_profile_parse.tmp");
            try {
                File binary = new File(getApplicationInfo().nativeLibraryDir, EXTRACT_NAME);
                if (!binary.isFile()) {
                    throw new IOException("missing native binary: " + binary.getAbsolutePath());
                }
                File workDir = getFilesDir();
                if (parsed.exists() && !parsed.delete()) {
                    throw new IOException("cannot remove stale profile output");
                }
                List<String> args = new ArrayList<>();
                args.addAll(sourceArguments);
                args.add("--format");
                args.add("conf");
                args.add("--out");
                args.add(parsed.getAbsolutePath());
                args.add("--work-dir");
                args.add(workDir.getAbsolutePath());
                appendLog("extracting upstream HOCON profile from " + sourceDescription);
                code = runExtractProcess(binary, args, workDir);
                appendLog("extract exit code=" + code);
                if (code == 0) {
                    String release = System.getProperty("os.version", "");
                    profile = GhostLockProfileResolver.parseAndCache(
                            this, release, parsed, profileSource);
                    appendLog("validated and cached " + profileSource.name() + " profile for " + release);
                }
            } catch (Throwable error) {
                appendLog("profile extraction failed: " + error.getClass().getSimpleName()
                        + ": " + error.getMessage());
            } finally {
                parsed.delete();
                if (clearLocalImages) {
                    clearLocalImageFiles();
                }
            }
            final int extractCode = code;
            final GhostLockResolvedProfile resolved = profile;
            ui.post(() -> {
                if (resolved != null) {
                    appendLog("profile source=" + profileSource.name() + "; starting upstream GhostLock core");
                    toast(R.string.ghostlock_parse_success);
                    startExploit(resolved, ProfileFallback.NONE);
                } else {
                    if (automaticFlow) {
                        automaticFlowRunning.set(false);
                    }
                    setRunState(RunState.FAILED);
                    toast(parseFailureToast(extractCode));
                }
            });
        });
    }
    private void beginLocalImageParse() {
        if (running.get() || automaticFlowRunning.get()) {
            return;
        }
        clearLocalImageFiles();
        resetLocalImageSelection();
        setRunState(RunState.RUNNING);
        new AlertDialog.Builder(this)
                .setTitle(R.string.ghostlock_local_image_source_title)
                .setItems(R.array.ghostlock_local_image_sources, (dialog, which) -> {
                    launchLocalImagePicker(which == 0 ? LocalImagePart.PAYLOAD : LocalImagePart.BOOT);
                })
                .setOnCancelListener(dialog -> cancelLocalImageParse())
                .show();
    }

    private void launchLocalImagePicker(LocalImagePart part) {
        pendingLocalImagePart = part;
        if (part == LocalImagePart.BOOT) {
            appendLog("select boot.img; optionally multi-select xbl_config.img, uefi.img, or vendor_boot.img");
            localBootImagesPicker.launch(new String[]{"*/*"});
        } else {
            appendLog("select " + part.fileName);
            localImagePicker.launch(new String[]{"*/*"});
        }
    }

    private void onLocalImagePicked(Uri uri) {
        LocalImagePart part = pendingLocalImagePart;
        pendingLocalImagePart = null;
        if (part == null) {
            return;
        }
        if (uri == null) {
            cancelLocalImageParse();
            return;
        }
        stageLocalImage(uri, part);
    }

    private void onLocalBootImagesPicked(List<Uri> uris) {
        LocalImagePart part = pendingLocalImagePart;
        pendingLocalImagePart = null;
        if (part != LocalImagePart.BOOT) {
            return;
        }
        if (uris == null || uris.isEmpty()) {
            cancelLocalImageParse();
            return;
        }
        if (uris.size() == 1) {
            stageLocalImage(uris.get(0), LocalImagePart.BOOT);
            return;
        }
        worker.execute(() -> {
            try {
                List<String> arguments = stageSelectedLocalBootImages(uris);
                ui.post(() -> onLocalBootImagesStaged(arguments));
            } catch (Throwable error) {
                ui.post(() -> failLocalImageParse(error));
            }
        });
    }

    private void stageLocalImage(Uri uri, LocalImagePart part) {
        worker.execute(() -> {
            try {
                File image = copyLocalImage(uri, part.fileName);
                ui.post(() -> onLocalImageStaged(part, image));
            } catch (Throwable error) {
                ui.post(() -> failLocalImageParse(error));
            }
        });
    }

    private List<String> stageSelectedLocalBootImages(List<Uri> uris) throws IOException {
        Map<LocalImagePart, File> staged = new HashMap<>();
        for (Uri uri : uris) {
            String displayName = getLocalImageDisplayName(uri);
            LocalImagePart part = localImagePartForName(displayName);
            if (part == null || part == LocalImagePart.PAYLOAD) {
                throw new IOException("cannot identify selected image by filename: " + displayName);
            }
            if (staged.containsKey(part)) {
                throw new IOException("selected more than one " + part.fileName);
            }
            staged.put(part, copyLocalImage(uri, part.fileName));
        }

        File bootImage = staged.get(LocalImagePart.BOOT);
        if (bootImage == null) {
            throw new IOException("multi-select must include boot.img");
        }

        List<String> arguments = new ArrayList<>();
        arguments.add(bootImage.getAbsolutePath());
        for (LocalImagePart part : new LocalImagePart[]{
                LocalImagePart.XBL, LocalImagePart.UEFI, LocalImagePart.VENDOR_BOOT}) {
            File image = staged.get(part);
            if (image != null) {
                arguments.add(part.extractorArgument);
                arguments.add(image.getAbsolutePath());
            }
        }
        return arguments;
    }

    private String getLocalImageDisplayName(Uri uri) throws IOException {
        try (Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    String displayName = cursor.getString(column);
                    if (displayName != null && !displayName.trim().isEmpty()) {
                        return displayName.trim();
                    }
                }
            }
        } catch (RuntimeException error) {
            throw new IOException("cannot read selected image filename", error);
        }
        String fallback = uri.getLastPathSegment();
        if (fallback == null || fallback.trim().isEmpty()) {
            throw new IOException("selected image has no readable filename");
        }
        return fallback;
    }

    private static LocalImagePart localImagePartForName(String displayName) {
        String name = displayName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        for (LocalImagePart part : LocalImagePart.values()) {
            if (part.fileName.equalsIgnoreCase(name)) {
                return part;
            }
        }
        return null;
    }

    private void onLocalBootImagesStaged(List<String> arguments) {
        for (int index = 1; index + 1 < arguments.size(); index += 2) {
            appendLog(arguments.get(index + 1).substring(
                    arguments.get(index + 1).lastIndexOf(File.separator) + 1) + " attached");
        }
        runExtract(
                arguments,
                false,
                "local boot image with selected attachments",
                GhostLockProfileSource.LOCAL_IMAGE,
                true);
    }

    private File copyLocalImage(Uri uri, String fileName) throws IOException {
        File directory = new File(getCacheDir(), "ghostlock_local_image");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("cannot create image staging directory");
        }
        File target = new File(directory, fileName);
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("cannot open selected document");
            }
            try (FileOutputStream output = new FileOutputStream(target, false)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
        }
        return target;
    }

    private void onLocalImageStaged(LocalImagePart part, File image) {
        appendLog(part.fileName + " ready (" + image.length() + " bytes)");
        switch (part) {
            case PAYLOAD -> {
                List<String> arguments = new ArrayList<>();
                arguments.add(image.getAbsolutePath());
                runExtract(
                        arguments,
                        false,
                        "local payload.bin",
                        GhostLockProfileSource.LOCAL_IMAGE,
                        true);
            }
            case BOOT -> {
                localBootImage = image;
                showLocalImageAttachOptions();
            }
            case XBL -> {
                localImageExtractorArgs.add("--xbl-config");
                localImageExtractorArgs.add(image.getAbsolutePath());
                continueLocalImageSelection();
            }
            case UEFI -> {
                localImageExtractorArgs.add("--uefi");
                localImageExtractorArgs.add(image.getAbsolutePath());
                continueLocalImageSelection();
            }
            case VENDOR_BOOT -> {
                localImageExtractorArgs.add("--vendor-boot");
                localImageExtractorArgs.add(image.getAbsolutePath());
                continueLocalImageSelection();
            }
        }
    }

    private void showLocalImageAttachOptions() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.ghostlock_local_image_attach_title)
                .setItems(R.array.ghostlock_local_image_attachments, (dialog, which) -> {
                    pendingLocalImageAttachments = switch (which) {
                        case 1 -> ATTACH_XBL;
                        case 2 -> ATTACH_UEFI;
                        case 3 -> ATTACH_XBL | ATTACH_UEFI;
                        case 4 -> ATTACH_VENDOR_BOOT;
                        default -> 0;
                    };
                    localImageExtractorArgs.clear();
                    localImageExtractorArgs.add(localBootImage.getAbsolutePath());
                    continueLocalImageSelection();
                })
                .setOnCancelListener(dialog -> cancelLocalImageParse())
                .show();
    }

    private void continueLocalImageSelection() {
        if ((pendingLocalImageAttachments & ATTACH_XBL) != 0) {
            pendingLocalImageAttachments &= ~ATTACH_XBL;
            launchLocalImagePicker(LocalImagePart.XBL);
        } else if ((pendingLocalImageAttachments & ATTACH_UEFI) != 0) {
            pendingLocalImageAttachments &= ~ATTACH_UEFI;
            launchLocalImagePicker(LocalImagePart.UEFI);
        } else if ((pendingLocalImageAttachments & ATTACH_VENDOR_BOOT) != 0) {
            pendingLocalImageAttachments &= ~ATTACH_VENDOR_BOOT;
            launchLocalImagePicker(LocalImagePart.VENDOR_BOOT);
        } else {
            List<String> arguments = new ArrayList<>(localImageExtractorArgs);
            runExtract(
                    arguments,
                    false,
                    "local boot image",
                    GhostLockProfileSource.LOCAL_IMAGE,
                    true);
        }
    }

    private void cancelLocalImageParse() {
        appendLog("local image profile extraction cancelled");
        clearLocalImageFiles();
        resetLocalImageSelection();
        setRunState(RunState.IDLE);
    }

    private void failLocalImageParse(Throwable error) {
        appendLog("local image profile extraction failed: " + error.getClass().getSimpleName()
                + ": " + error.getMessage());
        clearLocalImageFiles();
        resetLocalImageSelection();
        setRunState(RunState.FAILED);
        toast(R.string.ghostlock_parse_failed);
    }

    private void resetLocalImageSelection() {
        pendingLocalImagePart = null;
        pendingLocalImageAttachments = 0;
        localBootImage = null;
        localImageExtractorArgs.clear();
    }

    private void clearLocalImageFiles() {
        File directory = new File(getCacheDir(), "ghostlock_local_image");
        for (LocalImagePart part : LocalImagePart.values()) {
            new File(directory, part.fileName).delete();
        }
        directory.delete();
    }

    /**
     * Map an extractor exit code to the user-facing failure message.
     * 3: pselect route infeasible, 4: missing required offsets, 5: kallsyms
     * recovery failure; -1 means the process was killed on timeout.
     */
    private int parseFailureToast(int code) {
        return switch (code) {
            case 3, 4 -> R.string.ghostlock_parse_failed_route;
            case 5 -> R.string.ghostlock_parse_failed_kallsyms;
            case 6 -> R.string.ghostlock_parse_failed_fixed;
            case -1 -> R.string.ghostlock_parse_timeout;
            default -> R.string.ghostlock_parse_failed;
        };
    }

    private int runExtractProcess(File binary, List<String> args, File workDir) throws IOException, InterruptedException {
        // The extractor recovers kallsyms from the image itself; no root needed.
        List<String> cmd = new ArrayList<>();
        cmd.add(binary.getAbsolutePath());
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        pb.environment().put("GHOSTLOCK_HOME", workDir.getAbsolutePath());
        pb.environment().put("TMPDIR", workDir.getAbsolutePath());
        pb.environment().put("HOME", workDir.getAbsolutePath());
        // Payload downloads and on-device analysis can take a while.
        return runProcess(pb, 1800);
    }

    private void applyWindowInsetsPadding() {
        rootView.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            top = bars.top;
            bottom = bars.bottom;
            int side = dp(14);
            v.setPadding(side, top + dp(8), side, bottom + dp(8));
            return insets;
        });
        rootView.requestApplyInsets();
    }

    private String buildDeviceSummary() {
        return getString(R.string.ghostlock_device_label) + ": " + resolveDeviceName() + "\n" + getString(R.string.ghostlock_kernel_label) + ": " + System.getProperty("os.version", "unknown");
    }

    private void applyKernelStatus() {
        // 支持判断仅在执行链内部使用，不再占用主界面空间显示状态控件。
    }

    /**
     * Sales/market name of the device, matching the language of the current region.
     */
    private String resolveDeviceName() {
        boolean cn = "CN".equalsIgnoreCase(Locale.getDefault().getCountry());
        String marketName = firstValidProperty(cn ? "ro.vendor.oplus.market.name" : "ro.vendor.oplus.market.enname", cn ? "ro.vendor.oplus.market.enname" : "ro.vendor.oplus.market.name", "ro.product.marketname");
        return marketName != null ? marketName : Build.MANUFACTURER + " " + Build.MODEL;
    }

    private void startExploit(GhostLockResolvedProfile profile, ProfileFallback fallback) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        setRunState(RunState.RUNNING);
        // keep the screen awake
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        appendLog("==== start ====");
        appendLog("kernel profile source: " + profile.getSource().name());
        worker.execute(() -> {
            int code = 1;
            boolean nativeStarted = false;
            boolean allowProfileFallback = true;
            try {
                File workDir = getFilesDir();
                File binary = resolveBinary();
                String managerPackage = prepareSelectedKsud(workDir);
                nativeStarted = true;
                code = runBinaryWithRetry(binary, workDir, profile.getBytes(), managerPackage);
                appendLog("exit code=" + code);
            } catch (Throwable t) {
                if (t instanceof ManagerUnavailableException) {
                    allowProfileFallback = false;
                }
                appendLog("error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            } finally {
                int finalCode = code;
                boolean canFallback = nativeStarted && allowProfileFallback;
                ui.post(() -> {
                    running.set(false);
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    if (finalCode == 0) {
                        if (profile.getSource() == GhostLockProfileSource.APK_BUILTIN) {
                            markBuiltinOffsetSuccess(System.getProperty("os.version", ""));
                        }
                        automaticFlowRunning.set(false);
                        setRunState(RunState.SUCCESS);
                    } else if (canFallback && fallback == ProfileFallback.APK_BUILTIN_THEN_OTA) {
                        appendLog("cached profile failed with exit code=" + finalCode
                                + "; trying the exact APK built-in profile next");
                        setRunState(RunState.RUNNING);
                        String release = System.getProperty("os.version", "");
                        worker.execute(() -> {
                            GhostLockResolvedProfile builtin = GhostLockProfileResolver.loadBuiltin(
                                    GhostLockActivity.this, release);
                            ui.post(() -> {
                                if (builtin != null && !isBuiltinOffsetBlocked(release)) {
                                    markBuiltinOffsetFailed(release);
                                    appendLog("profile source=APK built-in; exact kernel release matched");
                                    startExploit(builtin, ProfileFallback.OTA);
                                } else {
                                    appendLog(builtin == null
                                            ? "no valid APK built-in profile; continuing to full OTA"
                                            : "APK built-in profile previously failed; continuing to full OTA");
                                    requestAutomaticOta();
                                }
                            });
                        });
                    } else if (canFallback && fallback == ProfileFallback.OTA) {
                        appendLog("APK built-in profile failed with exit code=" + finalCode
                                + "; falling back to full OTA parsing");
                        setRunState(RunState.RUNNING);
                        requestAutomaticOta();
                    } else {
                        automaticFlowRunning.set(false);
                        setRunState(RunState.FAILED);
                    }
                });
            }
        });
    }

    private void setRunState(RunState state) {
        boolean busy = state == RunState.RUNNING || automaticFlowRunning.get() || running.get();
        autoRootButton.setEnabled(!busy);
        localImageButton.setEnabled(!busy);
        autoRootButton.setText(busy ? R.string.ghostlock_action_running : R.string.ghostlock_action_start_privilege);
    }

    private File resolveBinary() throws IOException {
        File packaged = new File(getApplicationInfo().nativeLibraryDir, BINARY_NAME);
        if (packaged.isFile()) {
            appendLog("binary ready (" + packaged.length() + " bytes)");
            return packaged;
        }
        throw new IOException("missing native binary: " + packaged.getAbsolutePath());
    }

    private String prepareSelectedKsud(File workDir) throws IOException {
        String managerPackage = KernelSuManagerSelection.INSTANCE.selectedPackage(this);
        File ksud = new File(workDir, "ksud");
        if (ksud.exists() && !ksud.delete()) {
            throw new IOException("cannot remove stale selected manager ksud");
        }
        if (!KernelSuManagerSelection.INSTANCE.copySelectedKsud(this, ksud)) {
            if (ksud.exists() && !ksud.delete()) {
                throw new IOException("cannot remove incomplete selected manager ksud");
            }
            appendLog("manager libksud.so is not readable before root; will resolve "
                    + managerPackage + " after root handoff");
            return managerPackage;
        }
        try {
            Os.chmod(ksud.getAbsolutePath(), 448);
        } catch (ErrnoException error) {
            throw new IOException("cannot make selected ksud executable", error);
        }
        appendLog("staged selected manager ksud: " + managerPackage);
        return managerPackage;
    }

    private int runBinary(File binary, File workDir, byte[] profileBlob, String managerPackage)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(binary.getAbsolutePath());
        command.add("--ghostlock-app-call");
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        pb.environment().put("GHOSTLOCK_HOME", workDir.getAbsolutePath());
        pb.environment().put("GHOSTLOCK_KSU_LOG", new File(workDir, ".ghostlock_ksu.log").getAbsolutePath());
        pb.environment().put("YHROOT_KSU_PACKAGE", managerPackage);
        pb.environment().put("TMPDIR", workDir.getAbsolutePath());
        pb.environment().put("HOME", workDir.getAbsolutePath());
        appendLog("selected KernelSU manager package: " + managerPackage);
        int code = runProcess(pb, profileBlob, 300);
        appendKernelSuLog(new File(workDir, ".ghostlock_ksu.log"));
        return code;
    }

    private void appendKernelSuLog(File logFile) {
        if (!logFile.isFile()) return;
        appendLog("==== upstream KernelSU handoff log ====");
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                appendLog("[ksu] " + line);
            }
        } catch (IOException error) {
            appendLog("could not read KernelSU handoff log: " + error.getMessage());
        }
    }

    /** Retry once in a fresh upstream process, preserving the resolved profile. */
    private int runBinaryWithRetry(
            File binary, File workDir, byte[] profileBlob, String managerPackage)
            throws IOException, InterruptedException {
        int code = 1;
        for (int attempt = 1; attempt <= MAX_NATIVE_ATTEMPTS; attempt++) {
            File handoffLog = new File(workDir, ".ghostlock_ksu.log");
            if (handoffLog.isFile() && !handoffLog.delete()) {
                appendLog("warning: stale KernelSU handoff log could not be removed");
            }
            appendLog("native attempt " + attempt + "/" + MAX_NATIVE_ATTEMPTS);
            code = runBinary(binary, workDir, profileBlob, managerPackage);
            if (code == 0) {
                return 0;
            }
            if (containsManagerUnavailableMarker(new File(workDir, ".ghostlock_ksu.log"))) {
                throw new ManagerUnavailableException(managerPackage);
            }
            if (!isRetryableNativeExit(code) || attempt == MAX_NATIVE_ATTEMPTS) {
                break;
            }
            appendLog("native attempt " + attempt + " ended with " + describeNativeExit(code)
                    + "; retrying in a fresh process");
            Thread.sleep(NATIVE_RETRY_DELAY_MS);
        }
        return code;
    }

    private static boolean containsManagerUnavailableMarker(File logFile) {
        if (!logFile.isFile()) {
            return false;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(MANAGER_UNAVAILABLE_MARKER)) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            return false;
        }
        return false;
    }

    private static final class ManagerUnavailableException extends IOException {
        ManagerUnavailableException(String packageName) {
            super("selected KernelSU manager has no executable ksud after root handoff: "
                    + packageName);
        }
    }
    private static boolean isRetryableNativeExit(int code) {
        return code == 1 || code == 137 || code == -1;
    }

    private static String describeNativeExit(int code) {
        if (code == 137) return "SIGKILL/low-memory exit 137";
        if (code == -1) return "timeout";
        return "exit " + code;
    }

    private int runProcess(ProcessBuilder pb) throws IOException, InterruptedException {
        return runProcess(pb, 300);
    }

    private int runProcess(ProcessBuilder pb, long timeoutSeconds) throws IOException, InterruptedException {
        return runProcess(pb, null, timeoutSeconds);
    }

    private int runProcess(ProcessBuilder pb, byte[] stdin, long timeoutSeconds)
            throws IOException, InterruptedException {
        Process process = pb.start();
        try (OutputStream stream = process.getOutputStream()) {
            if (stdin != null) {
                stream.write(stdin);
                stream.flush();
            }
        }
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    appendLog(line);
                }
            } catch (IOException ignored) {
            }
        }, "ghostlock-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
        // Drain buffered output, then force-unblock the reader: a late-load
        // daemon (zygisk) can inherit our pipe and keep it open forever.
        try {
            reader.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
        }
        try {
            reader.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return finished ? process.exitValue() : -1;
    }

    private void copyLogs() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm == null) {
            return;
        }
        String text;
        synchronized (logBuffer) {
            text = logBuffer.toString();
        }
        cm.setPrimaryClip(ClipData.newPlainText("ghostlock-log", text));
        Toast.makeText(this, R.string.ghostlock_copied, Toast.LENGTH_SHORT).show();
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    private void appendLog(String line) {
        if (line == null) {
            return;
        }
        final String msg = line.endsWith("\n") ? line : line + "\n";
        final String plain = stripAnsi(msg);
        synchronized (logBuffer) {
            logBuffer.append(plain);
        }
        final CharSequence display = colorize(plain);
        ui.post(() -> {
            logView.append(display);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
        android.util.Log.i(TAG, plain.trim());
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private enum RunState {
        IDLE, RUNNING, SUCCESS, FAILED
    }

    private enum LocalImagePart {
        PAYLOAD("payload.bin", null),
        BOOT("boot.img", null),
        XBL("xbl_config.img", "--xbl-config"),
        UEFI("uefi.img", "--uefi"),
        VENDOR_BOOT("vendor_boot.img", "--vendor-boot");

        final String fileName;
        final String extractorArgument;

        LocalImagePart(String fileName, String extractorArgument) {
            this.fileName = fileName;
            this.extractorArgument = extractorArgument;
        }
    }

    private enum ProfileFallback {
        NONE,
        APK_BUILTIN_THEN_OTA,
        OTA,
    }
}
