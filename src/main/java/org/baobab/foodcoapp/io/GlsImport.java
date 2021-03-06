package org.baobab.foodcoapp.io;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.Log;

import org.baobab.foodcoapp.AccountActivity;
import org.baobab.foodcoapp.ImportActivity;

import java.io.IOException;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import au.com.bytecode.opencsv.CSVReader;

public class GlsImport implements ImportActivity.Importer {

    public static SimpleDateFormat date = new SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY);
    static {
        date.setTimeZone(TimeZone.getTimeZone("Europe/Berlin"));
    }
    public static String AUTHORITY = "org.baobab.foodcoapp";
    private SharedPreferences prefs;
    private final Context ctx;
    private String msg = "";
    public final Uri uri;
    private int count = 0;
    String lineMsges = "";
    private boolean intergrityCheckOk = true;

    public GlsImport(Context ctx) {
        this.ctx = ctx;
        ContentValues cv = new ContentValues();
        cv.put("start", System.currentTimeMillis());
        uri = ctx.getContentResolver().insert(Uri.parse(
                "content://" + AUTHORITY + "/sessions"), cv);
        try {
            prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        } catch (UnsupportedOperationException e) {
            // test env
        }
    }

    @Override
    public int read(CSVReader csv) throws IOException {
        List<String[]> lines = csv.readAll();
        Cursor before = ctx.getContentResolver().query(Uri.parse(
                "content://" + AUTHORITY + "/accounts"), null,
                "guid IS 'bank'", null, null);
        before.moveToFirst();
        float kontostand = before.getFloat(3);
        Log.i(AccountActivity.TAG, "Kontostand " + kontostand);
        float sum = 0;
        int exitingCount = 0;
        for (int i = lines.size()-1; i >= 0; i--) {
            String[] line = lines.get(i);
            if (prefs != null) { // ie no test
                String kNr = prefs.getString("Bank KontoNr", null);
                if (kNr == null) {
                    prefs.edit().putString("Bank KontoNr", line[0]).commit();
                } else if (!kNr.equals(line[0])) {
                    msg = "\nAndere Kontonummer?\n\nwar immer " + kNr + "\nist auf einmal " + line[0];
                    intergrityCheckOk = false;
                    return 0;
                }
            }
            Uri txn = readLine(line);
            if (txn == null) {
                System.out.println("WTF! null? " + line[1] + " " + line[3] );
            }
            Cursor t = ctx.getContentResolver().query(txn, null, null, null, null);
            t.moveToFirst();
            Cursor existing = ctx.getContentResolver().query(Uri.parse(
                    "content://" + AUTHORITY + "/transactions"), null,
                    "transactions.status IS 'final' AND transactions.start = ? AND transactions.comment IS ?",
                    new String[] { t.getString(2), t.getString(4)}, null);
            t.close();
            if (existing.getCount() > 0) {
                Log.i(AccountActivity.TAG, "Txn already exists! " + line[1] + " " + line[3]);
                ctx.getContentResolver().delete(txn, null, null);
                exitingCount++;
                existing.close();
            } else {
                try {
                    float amount = NumberFormat.getInstance(Locale.GERMAN).parse(line[19]).floatValue();
                    float newKontostand = NumberFormat.getInstance(Locale.GERMAN).parse(line[20]).floatValue();
                    if (Math.abs(kontostand + sum + amount - newKontostand) > 0.01) {
                        String m = "\nKontostand stimmt nicht mehr überein!\n" +
                                line[1] + " " + line[3] + "\n" +
                                "Wäre " + (kontostand + sum + amount) +
                                " aber sollte " + newKontostand + "\n\n";
                        Log.e(AccountActivity.TAG, m);
                        msg += m;
                        intergrityCheckOk = false;
                    } else {
                        sum += amount;
                        Log.i(AccountActivity.TAG, "Kontostand passt " + line[1] + " " + line[3] );
                    }
                } catch (ParseException e) {
                    e.printStackTrace();
                }
            }
        }
        if (exitingCount > 0) {
            msg += "\n\nFYI: " + exitingCount + " Transactionen existierten bereits.\n";
        }
        if (lines.size() != count) {
            msg += "Error! \nCould not read " + (lines.size() - count) + " lines!" + "\n\n" + msg;
            intergrityCheckOk = false;
        }
        return count - exitingCount;
    }

    public boolean isOk() {
        return intergrityCheckOk;
    }

    @Override
    public String getMsg() {
        return msg;
    }

    @Override
    public Uri getSession() {
        return uri;
    }

    static final Pattern vwz1 = Pattern.compile(
            "^(Einlage|einlage|Mitgliedsbeitrag|mitgliedsbeitrag|Beitrag|beitrag" +
                    "|Einzahlung|einzahlung|Guthaben|guthaben|Barkasse|barkasse)" +
                    "[-:,\\s]*([\\da-zA-Z]*)([-:,\\s]*|$)([\\da-zA-Z]*)([-:,\\s]*|$).*");

    static final Pattern vwz2Pattern = Pattern.compile("^([^-:\\s]*)[-:\\s]+(.*)([-:\\s]*|$)+.*");

    public Uri readLine(String[] line) {
        Uri transaction = null;
        try {
            lineMsges = "";
            long time = date.parse(line[1]).getTime();
            float amount = NumberFormat.getInstance(Locale.GERMAN).parse(line[19]).floatValue();
//            Log.d(PosActivity.TAG, "reading line: " + line[5] + line[6] + line[7] + line[8] + " (amount=" + amount + ")");
            if (amount > 0) {
                String vwz = line[5] + line[6] + line[7] + line[8];
                String comment = "Bankeingang:\n\n" + line[3] + "\nVWZ: " + vwz;
                Account account = findAccount(vwz);
                if (account != null && account.guid != null && account.err == null) {
                    transaction = storeTransaction(time, comment);
                    storeBankCash(transaction, amount);
                    if (vwz.toLowerCase().contains("einzahlung") ||
                                vwz.toLowerCase().contains("guthaben") ||
                                vwz.toLowerCase().contains("prepaid")) {
                        String title = "Bank " + account.name;
                        Iterator<Long> iter = findOpenTransactions("forderungen", "title IS '" + title + "'");
                        while (iter.hasNext()) {
                                Cursor txn = query("forderungen", "transactions._id =" + iter.next());
                            txn.moveToFirst();
                            float sum = txn.getFloat(8) * txn.getFloat(11);
                            if (amount + sum >= 0) { // quantity negative after groupBy from users perspective
                                lineMsges += "\nForderung beglichen: " + title + " -> " + String.format("%.2f", -sum);
                                storeTransactionItem(transaction, "forderungen", sum, title);
                                updateTxnTime(txn.getLong(0), time);
                                amount += sum;
                            }
                        }
                        if (amount > 0) { // rest guthaben
                            storeTransactionKorn(transaction, account.guid, -amount, "Korns");
                        }
                    } else if (vwz.toLowerCase().contains("mitgliedsbeitrag") ||
                                vwz.toLowerCase().contains("mitgliederbeitrag") ||
                                vwz.toLowerCase().contains("beitrag")) {
                        storeTransactionItem(transaction, "beiträge", - amount, account.name);
                    } else if (vwz.toLowerCase().contains("einlage")) {
                        storeTransactionItem(transaction, "einlagen", - amount, account.name);
                    } else if (vwz.toLowerCase().contains("spende")) {
                        storeTransactionItem(transaction, "spenden", - amount, "Spende");
                    } else { // found account but no keyword
                        storeTransactionItem(transaction, "verbindlichkeiten", - amount, account.name);
                    }
                } else if (vwz.toLowerCase().contains("barkasse")) {
                    transaction = storeTransaction(time, comment);
                    storeBankCash(transaction, amount);
                    Iterator<Long> iter = findOpenTransactions("forderungen", "title LIKE 'Bar%'");
                    while (iter.hasNext()) {
                        Cursor txn = query("forderungen", "transactions._id =" + iter.next());
                        txn.moveToNext();
                        float sum = txn.getFloat(8) * txn.getFloat(11);
                        if (amount + sum >= 0) { // quantity negative after groupBy from users perspective
                            lineMsges += "\nForderung beglichen: Bar " + txn.getString(3) + " -> " + String.format("%.2f", -sum);
                            storeTransactionItem(transaction, "forderungen",
                                    sum, "Bar " + txn.getString(3));
                            updateTxnTime(txn.getLong(0), time);
                            amount += sum;
                        }
                    }
                    if (amount > 0) { // rest barkasse (should never happen!)
                        lineMsges += "\n Komischer Rest von Barkasse Einzahlung -> " +String.format("%.2f", amount);
                        storeTransactionItem(transaction, "verbindlichkeiten", - amount, "Barkasse");
                    }
                } else if (vwz.toLowerCase().contains("spende")) {
                    transaction = storeTransaction(time, comment);
                    storeBankCash(transaction, amount);
                    storeTransactionItem(transaction, "spenden", - amount, "Spende");
                } else {
                    comment += "\nKein Mitglied gefunden";
                    transaction = storeTransaction(time, comment);
                    storeBankCash(transaction, amount);
                    if (vwz.toLowerCase().contains("einzahlung") ||
                            vwz.toLowerCase().contains("guthaben") ||
                            vwz.toLowerCase().contains("prepaid")) {
                        storeTransactionItem(transaction, "verbindlichkeiten", - amount, "Einzahlung");
                    } else if (vwz.toLowerCase().contains("mitgliedsbeitrag") ||
                            vwz.toLowerCase().contains("mitgliederbeitrag") ||
                            vwz.toLowerCase().contains("beitrag")) {
                        storeTransactionItem(transaction, "verbindlichkeiten", - amount, "Beitrag");
                    } else if (vwz.toLowerCase().contains("einlage")) {
                        storeTransactionItem(transaction, "verbindlichkeiten", - amount, "Einlage");
                    } else if (vwz.toLowerCase().contains("spende")) {
                        storeTransactionItem(transaction, "verbindlichkeiten", - amount, "Spende");
                    } else { // no account and no keyword
                        storeTransactionItem(transaction, "verbindlichkeiten", - amount, vwz);
                    }
                }
                count++;
                msg += lineMsges;
                return transaction;
            } else { // amount < 0
                String vwz1 = line[9] + line[10];
                String vwz2 = line[11] + line[12] + line[13] + line[14];
                String comment = "Bankausgang:\n\n" + (!line[3].equals("")? line[3]:"") +
                                    "\nVWZ: " + (!vwz1.equals("")? vwz1+"\n" : "") +
                                        (!vwz2.equals("")? vwz2+"\n" : "");
                if (line[3].equals("Auszahlung")) {
                    comment = comment + "\nVWZ " + line[5] + " " + line[6];
                    if (!settleOpenPayable("Auszahlung", amount, time, comment)) {
                        transaction = storeTransaction(time, comment);
                        storeBankCash(transaction, amount);
                        storeTransactionItem(transaction, "forderungen", -amount, "Auszahlung");
                    }
                } else if (line[4].contains("Kontof�hrung") || line[4].contains("Kontoführung")) {
                    transaction = storeTransaction(time, comment + "\nKontoführungsgebühren");
                    storeBankCash(transaction, amount);
                    storeBankCash(transaction, -amount, "kosten", "Kontogebühren");
                } else {
                    String text = line[9] + " " + line[10] + " " + vwz2;
                    if (text.toLowerCase().contains("auslage")) {
                        Account account = findAccount(vwz1);
                        if (account != null) {
                            transaction = storeTransaction(time, comment);
                            storeBankCash(transaction, amount);
                            storeTransactionItem(transaction, "einlagen", -amount, account.name);
                            amount = 0;
                        }
                    }
                    if (amount < 0) {
                        if ((transaction = findBookingInstruction(time, amount, comment, line[9])) != null) {
                        } else if ((transaction = findBookingInstruction(time, amount, comment, line[10])) != null) {
                        } else if ((transaction = findBookingInstruction(time, amount, comment, text)) != null) {
                        } else {
                            if (!settleOpenPayable(line[9], amount, time, comment)
                                    && !settleOpenPayable(vwz1, amount, time, comment)
                                    && !settleOpenPayable(vwz2, amount, time, comment)) {

                                transaction = storeTransaction(time, comment + "\nVWZ nicht erkannt");
                                storeBankCash(transaction, amount);
                                if (!vwz2.equals("")) {
                                    storeTransactionItem(transaction, "forderungen", -amount, vwz2);
                                } else if (!vwz1.equals("")) {
                                    storeTransactionItem(transaction, "forderungen", -amount, vwz1);
                                } else {
                                    storeTransactionItem(transaction, "forderungen", -amount, line[3]);
                                }
                            }
                        }
                    }
                }
            }
            count++;
            msg += lineMsges;
        } catch (ParseException e) {
            e.printStackTrace();
            Log.e(AccountActivity.TAG, "parse error " + e.getMessage());
            msg += "\nParse Error! " + e.getMessage();
            return null;
        }
        return transaction;
    }


    static final Pattern pattern =  Pattern.compile(".*([Kk]osten|[Ii]nventar)[-:,N\\s]+(.*)");
    private Uri findBookingInstruction(long time, float amount, String comment, String text) {
        Matcher m = pattern.matcher(text);
        if (m.matches()) {
            Uri transaction = storeTransaction(time, comment);
            storeBankCash(transaction, amount);
            storeTransactionItem(transaction, m.group(1).toLowerCase(), -amount, m.group(2));
            return transaction;
        }
        return null;
    }

    private boolean settleOpenPayable(String title, float amount, long time, String comment) {
        Iterator<Long> iter = findOpenTransactions("verbindlichkeiten", "title IS '" + title + "'");
        while (iter.hasNext()) {
            Cursor txn = query("verbindlichkeiten", "transactions._id =" + iter.next());
            txn.moveToFirst();
            float sum = txn.getFloat(8) * txn.getFloat(11);
            if (sum == amount) {
                Uri transaction = storeTransaction(time, comment);
                storeBankCash(transaction, amount);
                storeTransactionItem(transaction, "verbindlichkeiten", -amount, title);
                lineMsges += "\nVerbindlichkeit beglichen: " + title + " -> " + String.format("%.2f", -amount);
                updateTxnTime(txn.getLong(0), time);
                return true;
            }
        }
        return false;
    }

    private Iterator<Long> findOpenTransactions(String guid, String selection) {
        Cursor products = ctx.getContentResolver().query(uri.buildUpon()
                        .appendEncodedPath("accounts/" + guid + "/products").build(),
                            null, selection, null , null);
        TreeSet<Long> ids = new TreeSet<>();
        while (products.moveToNext()) {
            Cursor txns = query(guid, "title IS '" + products.getString(7) + "'" +
                " AND price = " + products.getFloat(5) + // select before groupBy
                " AND unit IS '" + products.getString(6) + "'" +
                (products.getFloat(4) > 0? " AND quantity > 0" : " AND quantity < 0"));
            txns.moveToLast();
            for (int i = 0; i < Math.abs(products.getFloat(4)); i++) {
                ids.add(txns.getLong(0));
                if (!txns.isFirst()) {
                    txns.moveToPrevious();
                }
            }
        }
        return ids.iterator();
    }

    private Cursor query(String guid, String selection) {
        return ctx.getContentResolver().query(Uri.parse(
                        "content://" + AUTHORITY + "/accounts/" + guid + "/transactions"),
                null, selection + " AND transactions.status IS 'final'", null , null);
    }

    private void storeBankCash(Uri transaction, float amount) {
        storeBankCash(transaction, amount, "bank", "Cash");

    }
    private void storeBankCash(Uri transaction, float amount, String guid, String title) {
        ContentValues b = new ContentValues();
        b.put("account_guid", guid);
        b.put("product_id", 1);
        b.put("title", title);
        b.put("quantity", amount);
        b.put("price", 1.0);
        ctx.getContentResolver().insert(transaction.buildUpon()
                .appendEncodedPath("products").build(), b);
    }

    private Uri storeTransaction(long time, String comment) {
        ContentValues t = new ContentValues();
        t.put("session_id", uri.getLastPathSegment());
        t.put("start", time);
        t.put("stop", time);
        t.put("status", "draft");
        t.put("comment", comment);
        return ctx.getContentResolver().insert(Uri.parse(
                "content://" + AUTHORITY + "/transactions"), t);
    }

    private void updateTxnTime(long txnId, long time) {
        ContentValues cv = new ContentValues();
        cv.put("start", time);
        ctx.getContentResolver().update(Uri.parse(
                "content://" + AUTHORITY + "/transactions/" + txnId), cv, null, null);
    }

    private void storeTransactionKorn(Uri transaction, String account, float amount, String title) {
        if (title == null || title.equals("")) title = "Unbekannt";
        ContentValues b = new ContentValues();
        b.put("account_guid", account);
        b.put("product_id", 2);
        b.put("title", title);
        b.put("quantity", amount);
        b.put("price", 1.0f);
        ctx.getContentResolver().insert(transaction.buildUpon()
                .appendEncodedPath("products").build(), b);
    }

    private void storeTransactionItem(Uri transaction, String account, float amount, String title) {
        if (title == null || title.equals("")) title = "Unbekannt";
        ContentValues b = new ContentValues();
        b.put("account_guid", account);
        b.put("product_id", 3);
        b.put("title", title);
        b.put("quantity", amount > 0? 1: -1);
        b.put("price", Math.abs(amount));
        ctx.getContentResolver().insert(transaction.buildUpon()
                .appendEncodedPath("products").build(), b);
    }

    static class Account {
        String guid;
        String name;
        String err;
    }

    static final Pattern nameWith = Pattern.compile("[a-zA-Z�]+[\\-\\s]{1}[a-zA-Z�]+");
    static final Pattern name = Pattern.compile("[a-zA-Z�]+");
    static final Pattern guid = Pattern.compile("\\d+");

    private Account findAccount(String vwz) {
        Account account = findAccountBy("guid", guid, vwz);
        if (account == null) {
            account = findAccountBy("name", name, vwz);
        }
        if (account == null) {
            account = findAccountBy("name", nameWith, vwz);
        }
        if (account == null) {
            if (vwz.contains(" ")) {
                account = findAccountBy("name", nameWith,
                        vwz.substring(vwz.indexOf(" ")));
            }
        }
        if (account == null) {
            if (vwz.contains("-")) {
                account = findAccountBy("name", nameWith,
                        vwz.substring(vwz.indexOf("-")));
            }
        }
        return account;
    }

    private Account findAccountBy(String column, Pattern pattern, String vwz) {
        Account account = null;
        Matcher g = pattern.matcher(vwz);
        int i = 0;
        while (g.find()) {
            i++;
            account = findAccountBy(column, g.group());
            if (account != null) break;
        }
        return account;
    }

    private Account findAccountBy(String column, String value) {
        Cursor accounts = ctx.getContentResolver().query(Uri.parse(
                        "content://" + AUTHORITY + "/accounts"), null,
                "UPPER(" + column + ") IS UPPER(?)", new String[] { value }, null);
        if (accounts.getCount() == 1) {
            accounts.moveToFirst();
            Account a = new Account();
            a.name = accounts.getString(1);
            a.guid = accounts.getString(2);
            return a;
        }
        return null;
    }
}
