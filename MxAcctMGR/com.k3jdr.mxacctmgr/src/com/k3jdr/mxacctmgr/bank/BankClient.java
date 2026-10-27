package com.k3jdr.mxacctmgr.bank;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.k3jdr.mxacctmgr.port.AccountsClient;
import com.k3jdr.mxacctmgr.port.ServiceException;

/**
 * Banking through the accounts service (BankViews.swift's BankClient, SecureClient, ExpenseClient): the checking
 * accounts and their shared register, the target accounts (payees) and expense accounts, the secure accounts and the
 * expense ledgers. Synchronous: call off the UI thread. Every change says who made it ({@link #by()}).
 */
public final class BankClient {
    private BankClient() {}

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    /** Who an entry was made by, as the register shows it: the Windows user and the PC. */
    public static String by() {
        String user = System.getProperty("user.name", "someone");
        String host = System.getenv("COMPUTERNAME");
        if (host == null) {
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                host = "PC";
            }
        }
        return user + " (" + host + ")";
    }

    static JsonObject call(String method, String path, JsonObject body) throws ServiceException {
        return call(method, path, body, TIMEOUT);
    }

    static JsonObject call(String method, String path, JsonObject body, Duration timeout) throws ServiceException {
        JsonElement e = AccountsClient.request(method, path, body, timeout);
        return J.obj(e);
    }

    private static JsonObject withBy(JsonObject body) {
        JsonObject b = body == null ? new JsonObject() : body.deepCopy();
        if (!b.has("by")) b.addProperty("by", by());
        return b;
    }

    // Checking accounts and the register

    public static List<JsonObject> accounts() throws ServiceException {
        return J.list(call("GET", "/bank/accounts", null), "accounts");
    }

    public static List<JsonObject> saveAccount(String code, JsonObject body) throws ServiceException {
        return J.list(call("PUT", "/bank/accounts/" + code, body), "accounts");
    }

    public static JsonObject register(String code) throws ServiceException {
        return call("GET", "/bank/accounts/" + code + "/register", null);
    }

    public static JsonObject add(String code, JsonObject body) throws ServiceException {
        return call("POST", "/bank/accounts/" + code + "/transactions", withBy(body));
    }

    /** Marks several entries cleared or not cleared at once: all or none (refused when one changed since it was read). */
    public static JsonObject setStatus(String code, List<JsonObject> entries, String status) throws ServiceException {
        JsonArray list = new JsonArray();
        for (JsonObject e : entries) {
            JsonObject x = new JsonObject();
            x.addProperty("id", J.i(e, "id"));
            x.addProperty("version", J.i(e, "version"));
            list.add(x);
        }
        JsonObject body = new JsonObject();
        body.add("entries", list);
        body.addProperty("status", status);
        return call("POST", "/bank/accounts/" + code + "/status", withBy(body));
    }

    /** Moves an entry one line up or down in its register (the only way its line changes). */
    public static JsonObject move(int id, boolean down) throws ServiceException {
        JsonObject body = new JsonObject();
        body.addProperty("direction", down ? "down" : "up");
        return call("POST", "/bank/transactions/" + id + "/move", withBy(body));
    }

    public static JsonObject update(int id, JsonObject body) throws ServiceException {
        return call("PUT", "/bank/transactions/" + id, withBy(body));
    }

    public static JsonObject accept(int line) throws ServiceException {
        return call("POST", "/bank/lines/" + line + "/accept", withBy(null));
    }

    public static JsonObject match(int line, int txn) throws ServiceException {
        JsonObject body = new JsonObject();
        body.addProperty("txnId", txn);
        return call("POST", "/bank/lines/" + line + "/match", withBy(body));
    }

    public static JsonObject importDownloads(String code) throws ServiceException {
        return call("POST", "/bank/accounts/" + code + "/import", new JsonObject(), Duration.ofMinutes(5));
    }

    // Target accounts (payees) and expense accounts

    /** The expense accounts and the target accounts: {id, year, expenseAccounts, payees}. */
    public static JsonObject book() throws ServiceException {
        return call("GET", "/expense-accounts", null);
    }

    public static JsonObject savePayee(int id, JsonObject body) throws ServiceException {
        return call("PUT", "/payees/" + id, withBy(body));
    }

    public static JsonObject savePayeeNote(int id, String note) throws ServiceException {
        JsonObject body = new JsonObject();
        body.addProperty("note", note);
        return call("PUT", "/payees/" + id + "/note", withBy(body));
    }

    public static JsonObject saveExpenseAccount(int id, JsonObject body) throws ServiceException {
        return call("PUT", "/expense-accounts/" + id, body);
    }

    /** The names card entries carry without a target account (Add from Card Registers…). */
    public static List<JsonObject> cardTargets() throws ServiceException {
        return J.list(call("GET", "/cards/targets", null), "targets");
    }

    public static JsonObject addCardTargets(List<String> names) throws ServiceException {
        JsonObject body = new JsonObject();
        body.add("names", J.array(names));
        return call("POST", "/cards/targets", withBy(body));
    }

    // Secure accounts

    /** {secureAccounts: [...], id?}. */
    public static JsonObject secureAccounts() throws ServiceException {
        return call("GET", "/secure-accounts", null);
    }

    public static JsonObject saveSecure(int id, JsonObject body) throws ServiceException {
        return call("PUT", "/secure-accounts/" + id, withBy(body));
    }

    // Expense ledgers

    public static JsonObject ledgers() throws ServiceException {
        return call("GET", "/expense-ledgers", null);
    }

    public static JsonObject ledger(String code) throws ServiceException {
        return call("GET", "/expense-ledgers/" + code, null);
    }

    public static JsonObject saveLedgerEntry(int id, JsonObject body) throws ServiceException {
        return call("PUT", "/expense-entries/" + id, withBy(body));
    }

    public static JsonObject addLedgerEntry(String code, JsonObject body) throws ServiceException {
        return call("POST", "/expense-ledgers/" + code + "/entries", withBy(body));
    }

    public static JsonObject deleteLedgerEntries(String code, List<JsonObject> entries) throws ServiceException {
        JsonArray list = new JsonArray();
        for (JsonObject e : entries) {
            JsonObject x = new JsonObject();
            x.addProperty("id", J.i(e, "id"));
            x.addProperty("version", J.i(e, "version"));
            list.add(x);
        }
        JsonObject body = new JsonObject();
        body.add("entries", list);
        return call("POST", "/expense-ledgers/" + code + "/entries/delete", withBy(body));
    }
}
