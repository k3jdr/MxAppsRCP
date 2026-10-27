package com.k3jdr.mxjobsmgr.engine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.k3jdr.mx.core.http.Http;
import com.k3jdr.mxjobsmgr.model.ApiSubscription;
import com.k3jdr.mxjobsmgr.model.Jobspace;
import com.k3jdr.mxjobsmgr.model.Specs.AuthStyle;

/**
 * The secure accounts an API subscription can send the key of: MxAcctMGR ▸ Banking ▸ Secure Accounts, read through the
 * accounts service (Services/accounts, 127.0.0.1:8774, GET /secure-accounts) — or, while it isn't running, from the
 * secure-storage nodes that hold their service keys. MxJobsMGR only reads the list; MxAcctMGR edits it.
 */
public final class SecureAccounts {
    private SecureAccounts() {}

    public static final String BASE = "http://127.0.0.1:8774";

    public record Account(int id, String name, String status, String serviceApi) {
        public boolean isActive() { return status == null || status.equals("active"); }
    }

    /** The list, and why the accounts service couldn't be read (null when it could). */
    public record Listing(List<Account> accounts, String problem) {
        public Account account(Integer id) {
            if (id == null) return null;
            for (Account a : accounts) if (a.id == id) return a;
            return null;
        }

        public Account named(String name) {
            String n = name == null ? "" : name.trim().toLowerCase();
            if (n.isEmpty()) return null;
            for (Account a : accounts) if (a.name.toLowerCase().equals(n)) return a;
            return null;
        }
    }

    private static volatile Listing last;

    public static Listing last() { return last; }

    /** Reads the list (off the UI thread). */
    public static Listing load() {
        Listing listing;
        try {
            Http.Response r = Http.get(BASE + "/secure-accounts", Duration.ofSeconds(5), Map.of("Accept", "application/json"));
            JsonElement json = Values.parse(r.body());
            if (json != null && json.isJsonObject() && Values.isString(json.getAsJsonObject().get("error")))
                throw new IllegalStateException("Secure Accounts: " + json.getAsJsonObject().get("error").getAsString());
            if (json == null || !json.isJsonObject() || !json.getAsJsonObject().has("secureAccounts"))
                throw new IllegalStateException("The accounts service answered in an unexpected form.");
            List<Account> list = new ArrayList<>();
            for (JsonElement e : json.getAsJsonObject().getAsJsonArray("secureAccounts")) {
                JsonObject o = e.getAsJsonObject();
                list.add(new Account(o.get("id").getAsInt(), o.has("name") ? o.get("name").getAsString() : "#" + o.get("id").getAsInt(),
                        Values.isString(o.get("status")) ? o.get("status").getAsString() : "active",
                        Values.isString(o.get("serviceApi")) ? o.get("serviceApi").getAsString() : null));
            }
            listing = new Listing(list, null);
        } catch (Exception e) {
            List<Account> list = new ArrayList<>();
            SecureKeys.stored().forEach((id, name) -> list.add(new Account(id, name, "active", null)));
            list.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            String why = e instanceof IllegalStateException ? e.getMessage()
                    : "The accounts service (127.0.0.1:8774) isn't answering: start it from MxDataHUB ▸ Services or with python Services/mxservices.py start.";
            listing = new Listing(list, why);
        }
        last = listing;
        return listing;
    }

    /**
     * Links each subscription that needs a key but has no secure account yet to the account of the name it was given
     * (a sample's, or New in MxAcctMGR…'s), or else of its own name; and keeps linked ones' names as MxAcctMGR has them
     * now. Answers whether it changed any.
     */
    public static boolean link(Jobspace space, Listing listing) {
        if (listing == null) return false;
        boolean changed = false;
        for (ApiSubscription sub : space.subscriptions) {
            if (sub.secureAccountID == null && sub.auth != AuthStyle.NONE) {
                Account a = listing.named(sub.secureAccountName.isEmpty() ? sub.name : sub.secureAccountName);
                if (a != null) {
                    sub.secureAccountID = a.id;
                    sub.secureAccountName = a.name;
                    changed = true;
                }
            } else if (sub.secureAccountID != null && listing.problem() == null) {
                Account a = listing.account(sub.secureAccountID);
                if (a != null && !a.name.equals(sub.secureAccountName)) {
                    sub.secureAccountName = a.name;
                    changed = true;
                }
            }
        }
        return changed;
    }
}
