package ch.blinkenlights.android.vanilla;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class CustomPlaybackDB extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "custom_playback.db";
    private static final int DATABASE_VERSION = 1;
    
    private static final String TABLE_POSITIONS = "custom_track_position";
    private static final String KEY_SONG_ID = "song_id";
    private static final String KEY_POSITION = "position";
    private static final String KEY_TIMESTAMP = "timestamp";

    private static CustomPlaybackDB sInstance;

    public static synchronized CustomPlaybackDB getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new CustomPlaybackDB(context.getApplicationContext());
        }
        return sInstance;
    }

    private CustomPlaybackDB(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String CREATE_TABLE = "CREATE TABLE " + TABLE_POSITIONS + " ("
                + KEY_SONG_ID + " INTEGER PRIMARY KEY, "
                + KEY_POSITION + " INTEGER, "
                + KEY_TIMESTAMP + " LONG" + ")";
        db.execSQL(CREATE_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    // Лёгкое сохранение (Android сам разберётся с буфером в памяти)
    public void savePosition(long songId, int position) {
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(KEY_SONG_ID, songId);
            values.put(KEY_POSITION, position);
            values.put(KEY_TIMESTAMP, System.currentTimeMillis());

            db.insertWithOnConflict(TABLE_POSITIONS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
        }
    }

    public int getPosition(long songId) {
        int position = 0;
        Cursor cursor = null;
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            cursor = db.query(TABLE_POSITIONS, 
                    new String[]{KEY_POSITION}, 
                    KEY_SONG_ID + "=?", 
                    new String[]{String.valueOf(songId)}, 
                    null, null, null);

            if (cursor != null && cursor.moveToFirst()) {
                position = cursor.getInt(cursor.getColumnIndexOrThrow(KEY_POSITION));
            }
        } catch (Exception e) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return position;
    }
}
