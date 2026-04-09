package name.caiyao.fakegps.data;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

public class AppInfoProvider extends ContentProvider {
    private static final String TAG = "AppInfoProvider";
    public static final String AUTHRITY = "name.caiyao.fakegps.data.AppInfoProvider";
    public static final Uri APP_CONTENT_URI = Uri.parse("content://" + AUTHRITY + "/app");
    public static final int APP_URI_CODE = 0;
    private static final UriMatcher sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    /** Room database file name — must match AppDatabase.kt */
    private static final String ROOM_DB_NAME = "fakegps.db";
    private static final String TABLE_TEMP = "temp";

    static {
        sUriMatcher.addURI(AUTHRITY, "app", APP_URI_CODE);
    }

    private SQLiteDatabase mSQLiteDatabase;

    @Override
    public boolean onCreate() {
        // Lazy init — Room DB may not exist yet on first launch
        return true;
    }

    /**
     * Opens Room's internal database so hooks read the same data Compose UI writes.
     * Uses WAL mode for safe concurrent reads alongside Room's own connection.
     */
    private synchronized SQLiteDatabase getDatabase() {
        if (mSQLiteDatabase != null && mSQLiteDatabase.isOpen()) {
            return mSQLiteDatabase;
        }
        File dbFile = getContext().getDatabasePath(ROOM_DB_NAME);
        if (!dbFile.exists()) {
            Log.d(TAG, "Room database not yet created");
            return null;
        }
        try {
            mSQLiteDatabase = SQLiteDatabase.openDatabase(
                    dbFile.getPath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open Room database", e);
            return null;
        }
        return mSQLiteDatabase;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        SQLiteDatabase db = getDatabase();
        if (db == null) return null;
        String table = getTableName(uri);
        if (table == null)
            throw new IllegalArgumentException("Unsupported URI:" + uri);
        return db.query(table, projection, selection, selectionArgs, null, null, sortOrder, null);
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, ContentValues values) {
        // Legacy path — Compose UI writes via Room directly
        return uri;
    }

    @Override
    public int delete(@NonNull Uri uri, String selection, String[] selectionArgs) {
        // Legacy path — Compose UI deletes via Room directly
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        // Legacy path — Compose UI updates via Room directly
        return 0;
    }

    private String getTableName(Uri uri) {
        String tableName = null;
        switch (sUriMatcher.match(uri)) {
            case APP_URI_CODE:
                tableName = TABLE_TEMP;
                break;
        }
        return tableName;
    }
}
