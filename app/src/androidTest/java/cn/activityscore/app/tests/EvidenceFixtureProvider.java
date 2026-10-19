package cn.activityscore.app.tests;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;

/** Test APK only; Java so the standalone provider process needs no target-APK Kotlin runtime. */
public class EvidenceFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "image/png"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if ("unopenable".equals(uri.getLastPathSegment())) throw new FileNotFoundException("fixture cannot open URI");
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            new Thread(() -> {
                ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
                try {
                    Bitmap bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(Color.BLUE);
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer); bitmap.recycle();
                    byte[] bytes = buffer.toByteArray();
                    switch (uri.getLastPathSegment()) {
                        case "fake": output.write("advertised image MIME, actual plain text".getBytes(java.nio.charset.StandardCharsets.UTF_8)); break;
                        case "interrupted": output.write(bytes, 0, 32); output.flush(); pipe[1].closeWithError("fixture URI interrupted mid-copy"); break;
                        case "blocked": output.write(bytes, 0, 32); output.flush(); Thread.sleep(15000); output.write(bytes, 32, bytes.length - 32); break;
                        default: output.write(bytes);
                    }
                } catch (Exception error) { try { pipe[1].closeWithError(error.toString()); } catch (Exception ignored) {} }
                finally { try { output.close(); } catch (Exception ignored) {} }
            }).start();
            return pipe[0];
        } catch (java.io.IOException error) { throw new FileNotFoundException(error.toString()); }
    }
}
