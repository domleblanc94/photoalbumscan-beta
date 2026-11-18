package local.photoscan;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/** The state executor owns this store. Each committed page is its own row. */
final class BatchStore implements AutoCloseable {
    final SQLiteDatabase db;

    BatchStore(File file) {
        db = SQLiteDatabase.openOrCreateDatabase(file, null);
        db.execSQL("PRAGMA synchronous=FULL");
        db.execSQL("CREATE TABLE IF NOT EXISTS settings (id INTEGER PRIMARY KEY, payload TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS pages (id TEXT PRIMARY KEY, position INTEGER NOT NULL, payload TEXT NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS page_order ON pages(position)");
        db.execSQL("CREATE TABLE IF NOT EXISTS receipts (id TEXT PRIMARY KEY, payload TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS imports (id TEXT PRIMARY KEY, position INTEGER NOT NULL, payload TEXT NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS import_order ON imports(position)");
    }

    JSONObject settings() throws JSONException {
        try (Cursor row = db.rawQuery("SELECT payload FROM settings WHERE id=1", null)) {
            if (!row.moveToFirst()) return null;
            return new JSONObject(row.getString(0));
        }
    }

    JSONObject read() throws JSONException {
        JSONObject data = settings();
        return data == null ? null : data
                .put("sources", readRows("pages", "position"))
                .put("receipts", readRows("receipts", "rowid"))
                .put("imports", readRows("imports", "position"));
    }

    int pageCount() {
        try (Cursor row = db.rawQuery("SELECT COUNT(*) FROM pages", null)) {
            return row.moveToFirst() ? row.getInt(0) : 0;
        }
    }

    boolean hasPendingImport(String uri) throws JSONException {
        try (Cursor rows = db.rawQuery("SELECT payload FROM imports", null)) {
            while (rows.moveToNext()) {
                if (uri.equals(new JSONObject(rows.getString(0)).getString("uri"))) return true;
            }
        }
        return false;
    }

    private JSONArray readRows(String table, String order) throws JSONException {
        JSONArray result = new JSONArray();
        try (Cursor rows = db.rawQuery("SELECT payload FROM " + table + " ORDER BY " + order, null)) {
            while (rows.moveToNext()) result.put(new JSONObject(rows.getString(0)));
        }
        return result;
    }

    void settings(JSONObject settings) {
        ContentValues values = new ContentValues();
        values.put("id", 1);
        values.put("payload", settings.toString());
        db.replaceOrThrow("settings", null, values);
    }

    void put(String table, String id, Integer position, JSONObject payload) {
        ContentValues values = new ContentValues();
        values.put("id", id);
        if (position != null) values.put("position", position);
        values.put("payload", payload.toString());
        // Update in place so recovery does not reorder receipts with an existing key.
        if (db.update(table, values, "id=?", new String[]{id}) == 0) {
            db.insertOrThrow(table, null, values);
        }
    }

    void move(String table, String id, int position) {
        ContentValues values = new ContentValues();
        values.put("position", position);
        if (db.update(table, values, "id=?", new String[]{id}) != 1) {
            throw new android.database.sqlite.SQLiteException("Could not update batch order.");
        }
    }

    void remove(String table, String id) {
        db.delete(table, "id=?", new String[]{id});
    }

    @Override public void close() { db.close(); }
}
