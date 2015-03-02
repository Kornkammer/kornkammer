package org.baobab.pos;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.net.Uri;
import android.util.Log;

public class StorageProvider extends ContentProvider {

    private static final String TAG = "POS";

    private class DatabaseHelper extends SQLiteOpenHelper {

        static final String TAG = "Provider";

        public DatabaseHelper(Context context) {
            super(context, "pos.db", null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE products (" +
                    "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "title TEXT, " +
                    "price FLOAT, " +
                    "path TEXT" +
                    ");");
            db.execSQL("CREATE TABLE transactions (" +
                    "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "session_id INTEGER, " +
                    "account_id INTEGER, " +
                    "timestamp INTEGER, " +
                    "status TEXT" +
                    ");");
            db.execSQL("CREATE TABLE transaction_products (" +
                    "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "transaction_id INTEGER, " +
                    "product_id INTEGER, " +
                    "quantity INTEGER, " +
                    "price FLOAT, " +
                    "title TEXT, " +
                    "path TEXT " +
                    ");");
            db.execSQL("CREATE UNIQUE INDEX idx"
                    + " ON transaction_products (" +
                    "transaction_id, product_id);");
            db.execSQL("CREATE TABLE accounts (" +
                    "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "guid TEXT, " +
                    "name TEXT, " +
                    "status TEXT, " +
                    "contact TEXT, " +
                    "pin TEXT, " +
                    "qr TEXT" +
                    ");");
            db.execSQL("CREATE TABLE sessions (" +
                    "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "account_id INTEGER, " +
                    "start INTEGER, " +
                    "stop INTEGER" +
                    ");");
            db.execSQL("INSERT INTO products (title, price) VALUES ('Tee', 1.5);");
            db.execSQL("INSERT INTO products (title, price) VALUES ('Bier', 3.5);");
            db.execSQL("INSERT INTO products (title, price) VALUES ('Keks', 0.5);");
            db.execSQL("INSERT INTO products (title) VALUES ('');");
            db.execSQL("INSERT INTO products (title) VALUES ('');");
            db.execSQL("INSERT INTO products (title) VALUES ('');");
            db.execSQL("INSERT INTO products (title) VALUES ('');");
            db.execSQL("INSERT INTO products (title) VALUES ('');");
            db.execSQL("INSERT INTO products (title) VALUES ('');");
            db.execSQL("INSERT INTO products (title) VALUES ('');");
            db.execSQL("INSERT INTO products (title) VALUES ('');");
            db.execSQL("INSERT INTO products (title) VALUES ('');");
            db.execSQL("INSERT INTO products (title) VALUES ('');");
            db.execSQL("INSERT INTO products (title) VALUES ('');");
            db.execSQL("INSERT INTO products (title) VALUES ('');");
            db.execSQL("INSERT INTO products (title) VALUES ('');");
            Log.d(TAG, "created DB");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        }
    }

    static final UriMatcher router = new UriMatcher(0);
    private static final int ACCOUNT = 0;
    private static final int ACCOUNTS = 1;
    private static final int SESSIONS = 2;
    private static final int PRODUCTS = 3;
    private static final int PRODUCT = 4;
    private static final int LEGITIMATE = 5;
    private static final int TRANSACTION = 6;
    private static final int TRANSACTIONS = 7;
    private static final int TRANSACTION_PRODUCT = 8;

    private static final short SUM = 8;

    static {
        router.addURI("org.baobab.pos", "accounts/#", ACCOUNT);
        router.addURI("org.baobab.pos", "accounts", ACCOUNTS);
        router.addURI("org.baobab.pos", "products", PRODUCTS);
        router.addURI("org.baobab.pos", "sessions", SESSIONS);
        router.addURI("org.baobab.pos", "products/#", PRODUCT);
        router.addURI("org.baobab.pos", "legitimate", LEGITIMATE);
        router.addURI("org.baobab.pos", "transactions", TRANSACTIONS);
        router.addURI("org.baobab.pos", "transactions/#", TRANSACTION);
        router.addURI("org.baobab.pos", "transactions/#/products/#", TRANSACTION_PRODUCT);
        router.addURI("org.baobab.pos", "transactions/#/sum", SUM);
    }

    private DatabaseHelper db;
    private SQLiteStatement insert;

    @Override
    public boolean onCreate() {
        db = new DatabaseHelper(getContext());
        return false;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        Cursor result = null;
        switch (router.match(uri)) {
            case PRODUCTS:
                result = db.getReadableDatabase().query("products", projection, selection, selectionArgs, null, null, null);
                break;
            case PRODUCT:
                result = db.getReadableDatabase().query("products", projection,
                        "_id = ?", new String[] {uri.getLastPathSegment()},
                        null, null, null);
                break;
            case TRANSACTION:
                result = db.getReadableDatabase().rawQuery(
                        "SELECT * FROM transaction_products" +
                        " WHERE transaction_id = ?",
                        new String[] { uri.getLastPathSegment() });
                break;
            case SUM:
                result = db.getReadableDatabase().rawQuery(
                        "SELECT quantity, price, sum(quantity * price)" +
                        " FROM transaction_products JOIN products" +
                        " ON transaction_products.product_id = products._id" +
                        " WHERE transaction_id = ?",
                        new String[] { uri.getPathSegments().get(1) });
                break;
            case ACCOUNTS:
                result = db.getReadableDatabase().rawQuery(
                        "SELECT accounts._id AS _id, name, max(accounts._id), " +
                                "sum(transaction_products.quantity * transaction_products.price)" +
                        " FROM accounts" +
                        " LEFT OUTER JOIN transactions ON transactions.account_id = accounts._id" +
                        " LEFT OUTER JOIN transaction_products ON transaction_products.transaction_id = transactions._id" +
                        " LEFT OUTER JOIN products ON transaction_products.product_id = products._id" +
                        " WHERE pin IS NOT NULL" +
                        " GROUP BY guid" +
                        " ORDER BY name",
                        null);
                break;
            case ACCOUNT:
                result = db.getReadableDatabase().rawQuery(
                        "SELECT accounts._id AS _id, guid, name, contact, pin, qr, max(accounts._id), " +
                                "sum(transaction_products.quantity * transaction_products.price) " +
                        " FROM accounts" +
                        " LEFT OUTER JOIN transactions ON transactions.account_id = accounts._id" +
                        " LEFT OUTER JOIN transaction_products ON transaction_products.transaction_id = transactions._id" +
                        " LEFT OUTER JOIN products ON transaction_products.product_id = products._id" +
                        " WHERE accounts._id = ?" +
                        " GROUP BY guid",
                        new String[] { uri.getLastPathSegment() });
                break;
            case LEGITIMATE:
                result = db.getReadableDatabase().rawQuery(
                        "SELECT _id, status, max(accounts._id), guid FROM accounts " +
                                "WHERE pin IS ? OR qr IS ? GROUP BY guid",
                        new String[] { uri.getQueryParameter("pin"),
                                uri.getQueryParameter("pin")});
                break;
            case TRANSACTIONS:
                result = db.getReadableDatabase().rawQuery(
                        "SELECT transactions._id AS _id, session.name, transactions.timestamp, accounts.name, " +
                                "GROUP_CONCAT(quantity || ' x ' || title || ' a ' || price || ' = ' || (quantity * price), ',  \n'), " +
                                "sum(transaction_products.quantity * transaction_products.price)" +
                        " FROM transactions" +
                        " JOIN accounts ON transactions.account_id = accounts._id" +
                        " JOIN sessions ON transactions.session_id = sessions._id" +
                        " JOIN accounts AS session ON sessions.account_id = session._id" +
                        " JOIN transaction_products ON transaction_products.transaction_id = transactions._id" +
                        " WHERE transactions.account_id IS NOT NULL" +
                        " GROUP BY transactions._id",
                        null);
                break;
        }
        result.setNotificationUri(getContext().getContentResolver(), uri);
        return result;
    }

    @Override
    public String getType(Uri uri) {
        switch (router.match(uri)) {
            case PRODUCT:
                return "org.baobab.pos/products";
            case ACCOUNT:
                return "org.baobab.pos/accounts";
        }
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        switch (router.match(uri)) {
            case TRANSACTION_PRODUCT:
                Cursor product = query(Uri.parse(
                        "content://org.baobab.pos/products/" + uri.getLastPathSegment()),
                        null, null, null, null);
                product.moveToFirst();
                float price = product.getFloat(2) * -1;
                db.getWritableDatabase().execSQL(
                        "INSERT OR REPLACE INTO transaction_products" +
                                " (transaction_id, product_id, title, price, path, quantity)" +
                                " VALUES (?, ?, ?, ?, ?, " +
                                "COALESCE(" +
                                "(SELECT quantity FROM transaction_products" +
                                " WHERE transaction_id = ? AND product_id = ?)," +
                                "0) + 1);", new String[]{
                                uri.getPathSegments().get(1),
                                product.getString(0),
                                product.getString(1),
                                String.valueOf(price),
                                product.getString(3),
                                uri.getPathSegments().get(1),
                                uri.getLastPathSegment() }
                );
                getContext().getContentResolver().notifyChange(uri, null);
                break;
            case ACCOUNTS:
                uri = ContentUris.withAppendedId(uri,
                    db.getWritableDatabase().insert("accounts", null, values));
                getContext().getContentResolver().notifyChange(uri, null);
                break;
            case TRANSACTIONS:
                uri = ContentUris.withAppendedId(uri,
                    db.getWritableDatabase().insert("transactions", null, values));
                break;
            case SESSIONS:
                uri = ContentUris.withAppendedId(uri,
                    db.getWritableDatabase().insert("sessions", null, values));
                break;
        }
        return uri;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        switch (router.match(uri)) {
            case TRANSACTION_PRODUCT:
                Cursor c = db.getReadableDatabase().query("transaction_products", null,
                        "transaction_id =? AND product_id = ?",
                        new String[]{
                                uri.getPathSegments().get(1),
                                uri.getLastPathSegment()},
                        null, null, null);
                if (c.getCount() == 0) return -1;
                c.moveToFirst();
                if (c.getInt(3) > 1) {
                    ContentValues dec = new ContentValues();
                    dec.put("quantity", c.getInt(3) - 1);
                    db.getWritableDatabase().update("transaction_products", dec,
                            "transaction_id = ? AND product_id = ?",
                            new String[]{
                                    uri.getPathSegments().get(1),
                                    uri.getLastPathSegment() });
                } else {
                    db.getWritableDatabase().delete("transaction_products",
                            "transaction_id = ? AND product_id = ?",
                            new String[]{
                                    uri.getPathSegments().get(1),
                                    uri.getLastPathSegment()}
                    );
                }
                getContext().getContentResolver().notifyChange(uri, null);

                break;
        }
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        switch (router.match(uri)) {
            case ACCOUNTS:
                db.getWritableDatabase().update("accounts", values, selection, selectionArgs);
                break;
            case PRODUCT:
                db.getWritableDatabase().update("products", values, "_id = " + uri.getLastPathSegment(), null);
                break;
            case TRANSACTION:
                db.getWritableDatabase().update("transactions", values, "_id =  + " + uri.getLastPathSegment(), null);
                break;
        }
        getContext().getContentResolver().notifyChange(uri, null);
        return 0;
    }
}
