package kr.co.iefriends.pcsx2.activities;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class SingleGameActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().getDecorView().post(this::applyImmersiveMode);

        TextView tv = new TextView(this);
        tv.setText("Installing game assets...\n\nPlease do NOT close the app.");
        tv.setTextSize(20f);
        tv.setGravity(Gravity.CENTER);
        setContentView(tv);

        new Thread(() -> {

            File root = getExternalFilesDir(null);
            if (root == null) {
                runOnUiThread(() ->
                        Toast.makeText(this, "External storage unavailable", Toast.LENGTH_LONG).show()
                );
                return;
            }

            File gameFile = ensureGameInstalled(root);
            ensureBiosInstalled(root);
            writePcsx2Ini(root);
            markBiosSelected(root);

            // Extract textures if the config flag is set and they haven't been
            // extracted yet. Must run before launching so the core finds them.
            if (LocalGameConfig.HAS_TEXTURES) {
                ensureTexturesExtracted(root);
            }

            Uri gameUri = Uri.fromFile(gameFile);

            runOnUiThread(() -> {
                Intent i = new Intent(this, MainActivity.class);
                i.setAction(Intent.ACTION_VIEW);
                i.setData(gameUri);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(i);
                finish();
            });

        }).start();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersiveMode();
    }

    // ==============================
    // IMMERSIVE MODE + CUTOUT
    // ==============================
    private void applyImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(getWindow().getAttributes());
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            }
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
            );
        }
    }

    // ==============================
    // ISO INSTALL FROM ZIP
    // ==============================
    private File ensureGameInstalled(File root) {
        File gamesDir = new File(root, "games");
        if (!gamesDir.exists()) gamesDir.mkdirs();

        File outFile = new File(gamesDir, LocalGameConfig.GAME_NAME);
        if (outFile.exists() && outFile.length() > 0) return outFile;

        try (InputStream assetStream = getAssets().open(LocalGameConfig.ASSET_GAME_PATH);
             ZipInputStream zis = new ZipInputStream(assetStream)) {

            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            boolean extracted = false;

            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()
                        && entry.getName().toLowerCase().endsWith(".iso")) {
                    try (FileOutputStream out = new FileOutputStream(outFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) out.write(buffer, 0, len);
                    }
                    extracted = true;
                    break;
                }
                zis.closeEntry();
            }

            if (!extracted) throw new IOException("No ISO found inside " + LocalGameConfig.GAME_ZIP_NAME);

        } catch (IOException e) {
            throw new RuntimeException("Failed to extract game ISO from ZIP", e);
        }

        return outFile;
    }

    // ==============================
    // BIOS INSTALL
    // ==============================
    private void ensureBiosInstalled(File root) {
        File biosDir = new File(root, "bios");
        biosDir.mkdirs();

        File[] existing = biosDir.listFiles((d, n) -> n.toLowerCase().endsWith(".bin"));
        if (existing != null && existing.length > 0) return;

        try (InputStream in = getAssets().open(LocalGameConfig.ASSET_BIOS_PATH);
             ZipInputStream zis = new ZipInputStream(in)) {

            ZipEntry entry;
            byte[] buffer = new byte[16 * 1024];

            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = new File(entry.getName()).getName();
                if (!name.toLowerCase().endsWith(".bin")) continue;

                File outFile = new File(biosDir, name);
                try (FileOutputStream out = new FileOutputStream(outFile)) {
                    int len;
                    while ((len = zis.read(buffer)) > 0) out.write(buffer, 0, len);
                }
                break;
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to extract BIOS", e);
        }
    }

    // ==============================
    // TEXTURE EXTRACTION
    // Extracts assets/textures.zip into:
    //   <dataRoot>/textures/<GAME_SERIAL>/replacements/
    // Skipped if the target directory already exists and is non-empty.
    // Controlled by LocalGameConfig.HAS_TEXTURES and LocalGameConfig.GAME_SERIAL.
    // ==============================
    private void ensureTexturesExtracted(File root) {
        File targetDir = new File(root,
                "textures/" + LocalGameConfig.GAME_SERIAL + "/replacements");

        // Already extracted — skip
        String[] contents = targetDir.list();
        if (contents != null && contents.length > 0) return;

        targetDir.mkdirs();

        try {
            String apkPath = getApplicationInfo().sourceDir;
            java.util.zip.ZipFile apk = new java.util.zip.ZipFile(apkPath);

            ZipEntry texturesZipEntry = apk.getEntry("assets/textures.zip");
            if (texturesZipEntry == null) {
                android.util.Log.w("SingleGame", "assets/textures.zip not found in APK — skipping");
                apk.close();
                return;
            }

            try (InputStream zipStream = apk.getInputStream(texturesZipEntry);
                 ZipInputStream zip = new ZipInputStream(zipStream)) {

                ZipEntry entry;
                byte[] buffer = new byte[1024 * 1024];

                while ((entry = zip.getNextEntry()) != null) {
                    File outFile = new File(targetDir, entry.getName()).getCanonicalFile();

                    // Security: block path traversal
                    if (!outFile.getPath().startsWith(targetDir.getCanonicalPath())) {
                        zip.closeEntry();
                        continue;
                    }

                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                    } else {
                        outFile.getParentFile().mkdirs();
                        try (FileOutputStream out = new FileOutputStream(outFile)) {
                            int len;
                            while ((len = zip.read(buffer)) > 0) out.write(buffer, 0, len);
                        }
                    }

                    zip.closeEntry();
                }
            }

            apk.close();

        } catch (Exception e) {
            throw new RuntimeException("Failed to extract textures", e);
        }
    }

    // ==============================
    // WRITE PCSX2.ini
    // Reads local_pcsx2_overrides.ini from assets (full replacement),
    // then injects the runtime BIOS path into the [Folders] section.
    // ==============================
    private void writePcsx2Ini(File root) {
        try {
            File iniDir = new File(root, "system/pcsx2/inis");
            if (!iniDir.exists()) iniDir.mkdirs();

            File ini = new File(iniDir, "PCSX2.ini");
            String biosPath = new File(root, "bios").getAbsolutePath();

            StringBuilder sb = new StringBuilder();
            try (InputStream in = getAssets().open("local_pcsx2_overrides.ini");
                 BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
                String line;
                boolean biosInjected = false;
                while ((line = br.readLine()) != null) {
                    // Inject Bios= immediately after [Folders] header
                    if (line.trim().equalsIgnoreCase("[Folders]") && !biosInjected) {
                        sb.append(line).append('\n');
                        sb.append("Bios=").append(biosPath).append('\n');
                        biosInjected = true;
                        continue;
                    }
                    // Strip any Bios= the user left in the file to avoid duplicates
                    if (line.trim().toLowerCase().startsWith("bios=")) continue;

                    sb.append(line).append('\n');
                }
                // Safety: if [Folders] was never found, append it
                if (!biosInjected) {
                    sb.append("\n[Folders]\n");
                    sb.append("Bios=").append(biosPath).append('\n');
                }
            }

            try (FileOutputStream out = new FileOutputStream(ini)) {
                out.write(sb.toString().getBytes());
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to write PCSX2.ini", e);
        }
    }

    // ==============================
    // MARK BIOS SELECTED
    // ==============================
    private void markBiosSelected(File root) {
        File biosDir = new File(root, "bios");
        File[] biosFiles = biosDir.listFiles((d, n) -> n.toLowerCase().endsWith(".bin"));
        if (biosFiles != null && biosFiles.length > 0) {
            getSharedPreferences(LocalGameConfig.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(LocalGameConfig.PREFS_KEY_BIOS, biosFiles[0].getAbsolutePath())
                    .apply();
        }
    }
}