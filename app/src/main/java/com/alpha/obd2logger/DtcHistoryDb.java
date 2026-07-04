package com.alpha.obd2logger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class DtcHistoryDb extends SQLiteOpenHelper {
    private static final String TAG = "DtcHistoryDb";
    private static final String DATABASE_NAME = "dtc_history.db";
    private static final int DATABASE_VERSION = 1;

    private static final String TABLE_SCANS = "dtc_scans";
    private static final String KEY_ID = "id";
    private static final String KEY_VIN = "vin";
    private static final String KEY_DATE = "scan_date";
    private static final String KEY_TYPE = "type"; // 'stored', 'pending', 'permanent'
    private static final String KEY_CODE = "code";
    private static final String KEY_DESC = "description";
    private static final String KEY_FREEZE_FRAME = "freeze_frame"; // JSON string

    public DtcHistoryDb(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTable = "CREATE TABLE " + TABLE_SCANS + " ("
                + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + KEY_VIN + " TEXT, "
                + KEY_DATE + " TEXT, "
                + KEY_TYPE + " TEXT, "
                + KEY_CODE + " TEXT, "
                + KEY_DESC + " TEXT, "
                + KEY_FREEZE_FRAME + " TEXT"
                + ");";
        db.execSQL(createTable);
        db.execSQL("CREATE INDEX idx_vin ON " + TABLE_SCANS + "(" + KEY_VIN + ");");
        db.execSQL("CREATE INDEX idx_code ON " + TABLE_SCANS + "(" + KEY_CODE + ");");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_SCANS);
        onCreate(db);
    }

    /**
     * Save a DTC scan snapshot to history.
     */
    public synchronized void saveScan(String vin, List<DtcCode> stored, List<DtcCode> pending, List<DtcCode> permanent, String ffJson) {
        if (vin == null || vin.isEmpty()) {
            vin = "UNKNOWN_VIN";
        }
        SQLiteDatabase db = getWritableDatabase();
        String dateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());

        db.beginTransaction();
        try {
            // Write stored
            for (DtcCode c : stored) {
                ContentValues values = new ContentValues();
                values.put(KEY_VIN, vin);
                values.put(KEY_DATE, dateStr);
                values.put(KEY_TYPE, "stored");
                values.put(KEY_CODE, c.getCode());
                values.put(KEY_DESC, c.getDescription());
                values.put(KEY_FREEZE_FRAME, ffJson);
                db.insert(TABLE_SCANS, null, values);
            }
            // Write pending
            for (DtcCode c : pending) {
                ContentValues values = new ContentValues();
                values.put(KEY_VIN, vin);
                values.put(KEY_DATE, dateStr);
                values.put(KEY_TYPE, "pending");
                values.put(KEY_CODE, c.getCode());
                values.put(KEY_DESC, c.getDescription());
                values.put(KEY_FREEZE_FRAME, ffJson);
                db.insert(TABLE_SCANS, null, values);
            }
            // Write permanent
            for (DtcCode c : permanent) {
                ContentValues values = new ContentValues();
                values.put(KEY_VIN, vin);
                values.put(KEY_DATE, dateStr);
                values.put(KEY_TYPE, "permanent");
                values.put(KEY_CODE, c.getCode());
                values.put(KEY_DESC, c.getDescription());
                values.put(KEY_FREEZE_FRAME, ffJson);
                db.insert(TABLE_SCANS, null, values);
            }
            // If empty, insert a placeholder record to indicate clean scan
            if (stored.isEmpty() && pending.isEmpty() && permanent.isEmpty()) {
                ContentValues values = new ContentValues();
                values.put(KEY_VIN, vin);
                values.put(KEY_DATE, dateStr);
                values.put(KEY_TYPE, "clean");
                values.put(KEY_CODE, "P0000");
                values.put(KEY_DESC, "No trouble codes found.");
                values.put(KEY_FREEZE_FRAME, (String) null);
                db.insert(TABLE_SCANS, null, values);
            }
            db.setTransactionSuccessful();
            Log.d(TAG, "Successfully saved DTC scan history entry");
        } catch (Exception e) {
            Log.e(TAG, "Failed to save DTC history scan", e);
        } finally {
            db.endTransaction();
        }
    }

    public static class DtcHistoryRecord {
        public final String vin;
        public final String date;
        public final String type;
        public final String code;
        public final String description;
        public final String freezeFrameJson;

        public DtcHistoryRecord(String vin, String date, String type, String code, String description, String ffJson) {
            this.vin = vin;
            this.date = date;
            this.type = type;
            this.code = code;
            this.description = description;
            this.freezeFrameJson = ffJson;
        }
    }

    /**
     * Retrieve diagnostic history for a specific VIN.
     */
    public synchronized List<DtcHistoryRecord> getHistory(String vin) {
        if (vin == null || vin.isEmpty()) {
            vin = "UNKNOWN_VIN";
        }
        List<DtcHistoryRecord> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_SCANS, null, KEY_VIN + "=?", new String[]{vin}, null, null, KEY_DATE + " DESC");

        if (cursor != null) {
            try {
                int colVin = cursor.getColumnIndex(KEY_VIN);
                int colDate = cursor.getColumnIndex(KEY_DATE);
                int colType = cursor.getColumnIndex(KEY_TYPE);
                int colCode = cursor.getColumnIndex(KEY_CODE);
                int colDesc = cursor.getColumnIndex(KEY_DESC);
                int colFf = cursor.getColumnIndex(KEY_FREEZE_FRAME);

                while (cursor.moveToNext()) {
                    list.add(new DtcHistoryRecord(
                            cursor.getString(colVin),
                            cursor.getString(colDate),
                            cursor.getString(colType),
                            cursor.getString(colCode),
                            cursor.getString(colDesc),
                            cursor.getString(colFf)
                    ));
                }
            } finally {
                cursor.close();
            }
        }
        return list;
    }

    /**
     * Clear diagnostic history for a specific VIN.
     */
    public synchronized void clearHistory(String vin) {
        if (vin == null || vin.isEmpty()) {
            vin = "UNKNOWN_VIN";
        }
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_SCANS, KEY_VIN + "=?", new String[]{vin});
    }
}
