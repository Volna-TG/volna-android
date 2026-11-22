package app.volna;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

public final class UpdateProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private File resolve(Uri uri) {
        if (!("/" + Updates.APK_NAME).equals(uri.getPath())) throw new IllegalArgumentException();
        return Updates.apk(getContext());
    }

    @Override public String getType(Uri uri) {
        resolve(uri);
        return "application/vnd.android.package-archive";
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        File file = resolve(uri);
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = Updates.APK_NAME;
            if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
        }
        MatrixCursor cursor = new MatrixCursor(columns);
        if (file.isFile()) cursor.addRow(values);
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
