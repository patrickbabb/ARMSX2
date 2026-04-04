package kr.co.iefriends.pcsx2.activities;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class SingleGameActivity extends Activity {

    // ===== CONFIG =====
    private static final String GAME_NAME = "game.iso";
    private static final String GAME_ZIP_NAME = "game.zip";
    private static final String BIOS_ZIP_NAME = "bios.zip";

    private static final String ASSET_GAME_PATH = "resources/games/" + GAME_ZIP_NAME;
    private static final String ASSET_BIOS_PATH = BIOS_ZIP_NAME;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ==============================
        // INSTALL SCREEN UI
        // ==============================
        TextView tv = new TextView(this);
        tv.setText("Installing game assets...\n\nPlease do NOT close the app.");
        tv.setTextSize(20f);
        tv.setGravity(Gravity.CENTER);
        setContentView(tv);

        // ==============================
        // RUN INSTALL IN BACKGROUND
        // ==============================
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
            forceBiosPath(root);
            markBiosSelected(root);

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

    // ==============================
    // ISO INSTALL FROM ZIP
    // ==============================
    private File ensureGameInstalled(File root) {

        File gamesDir = new File(root, "games");
        if (!gamesDir.exists()) gamesDir.mkdirs();

        File outFile = new File(gamesDir, GAME_NAME);

        if (outFile.exists() && outFile.length() > 0) {
            return outFile;
        }

        try (InputStream assetStream = getAssets().open(ASSET_GAME_PATH);
             ZipInputStream zis = new ZipInputStream(assetStream)) {

            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            boolean extracted = false;

            while ((entry = zis.getNextEntry()) != null) {

                if (!entry.isDirectory()
                        && entry.getName().toLowerCase().endsWith(".iso")) {

                    try (FileOutputStream out = new FileOutputStream(outFile)) {

                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            out.write(buffer, 0, len);
                        }
                    }

                    extracted = true;
                    break;
                }

                zis.closeEntry();
            }

            if (!extracted) {
                throw new IOException("No ISO found inside game.zip");
            }

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

        try (InputStream in = getAssets().open(ASSET_BIOS_PATH);
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
                    while ((len = zis.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                }

                break;
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to extract BIOS", e);
        }
    }

    // ==============================
    // FORCE PCSX2 BIOS PATH
    // ==============================
    private void forceBiosPath(File root) {
        try {
            File iniDir = new File(root, "system/pcsx2/inis");
            if (!iniDir.exists()) iniDir.mkdirs();

            File ini = new File(iniDir, "PCSX2.ini");

            String biosPath = new File(root, "bios").getAbsolutePath();

            String config =
                    "[Folders]\n" +
                            "Bios=" + biosPath + "\n";

            try (FileOutputStream out = new FileOutputStream(ini)) {
                out.write(config.getBytes());
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to write PCSX2.ini", e);
        }
    }

    private void markBiosSelected(File root) {

        File biosDir = new File(root, "bios");
        File[] biosFiles = biosDir.listFiles((d, n) -> n.toLowerCase().endsWith(".bin"));

        if (biosFiles != null && biosFiles.length > 0) {
            getSharedPreferences("armsx2", MODE_PRIVATE)
                    .edit()
                    .putString("selected_bios", biosFiles[0].getAbsolutePath())
                    .apply();
        }
    }
}
