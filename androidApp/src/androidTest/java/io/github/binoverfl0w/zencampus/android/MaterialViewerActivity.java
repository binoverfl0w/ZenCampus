package io.github.binoverfl0w.zencampus.android;

import android.app.Activity;
import android.os.Bundle;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.widget.TextView;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Runs as the test APK's separate UID to verify real URI read grants. */
public class MaterialViewerActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String text;
        try {
            Uri uri = getIntent().getData();
            String name = "";
            try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
            }
            byte[] header = new byte[5];
            int count;
            try (InputStream input = getContentResolver().openInputStream(uri)) { count = input.read(header); }
            text = "Name: " + name + "\nType: " + getContentResolver().getType(uri) + "\nHeader: " + new String(header, 0, Math.max(0, count), StandardCharsets.US_ASCII);
        } catch (Exception error) { text = "Reader error: " + error.getClass().getSimpleName(); }
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(20); view.setPadding(24, 48, 24, 24);
        setContentView(view);
    }
}
