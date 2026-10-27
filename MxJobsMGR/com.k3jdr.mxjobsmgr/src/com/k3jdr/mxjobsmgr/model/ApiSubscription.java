package com.k3jdr.mxjobsmgr.model;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.k3jdr.mxjobsmgr.model.Specs.AuthStyle;
import com.k3jdr.mxjobsmgr.model.Specs.IdeChoice;
import com.k3jdr.mxjobsmgr.model.Specs.KeyValue;

/**
 * A Layer 3 API subscription: base URL, how the key is sent, headers, and the MxAcctMGR secure account whose service
 * key its calls send. Keys are never written to the jobspace.
 */
public final class ApiSubscription implements J.Writable {
    public static final int LOCAL_SERVICE_PORT = 8765;

    public String id = J.newId();
    public String name = "New Subscription";
    public String provider = "";
    public String baseURL = "https://";
    public AuthStyle auth = AuthStyle.QUERY_PARAMETER;
    /** Query parameter or header name that carries the key ("apikey", "X-API-Key"). */
    public String keyName = "apikey";
    public int rateLimitPerMinute;
    public String plan = "";
    public String docsURL = "";
    /** Headers sent with every call (e.g. the User-Agent SEC EDGAR asks for). */
    public List<KeyValue> defaultHeaders = new ArrayList<>();
    public String notes = "";
    /** Source project of a local service (e.g. Services/portfolio-java), opened with "Open in IDE". */
    public String projectFolder = "";
    public IdeChoice ide = IdeChoice.VSCODE;
    /** The MxAcctMGR secure account whose service key the calls send; null until one is chosen. */
    public Integer secureAccountID;
    /** Its name as last seen; with no id, the account to link to once it exists. */
    public String secureAccountName = "";

    /** A Python (or any) service on this PC, e.g. Services/market-sample/mxjobs_service.py. */
    public static ApiSubscription localService(int port) {
        ApiSubscription sub = new ApiSubscription();
        sub.name = "Local Python service";
        sub.provider = "This PC";
        sub.baseURL = "http://127.0.0.1:" + port;
        sub.auth = AuthStyle.LOCAL_SERVICE;
        sub.keyName = "";
        sub.notes = "Start it with: python Services/mxservices.py start (or python Services/market-sample/mxjobs_service.py --port " + port + "). "
                + "Choose a secure account for it only if the service is started with --token (its service key is the token).";
        return sub;
    }

    public static ApiSubscription read(JsonObject o) {
        ApiSubscription s = new ApiSubscription();
        s.id = J.uuid(o, "id", s.id);
        s.name = J.str(o, "name", s.name);
        s.provider = J.str(o, "provider", "");
        s.baseURL = J.str(o, "baseURL", s.baseURL);
        s.auth = J.enumOf(o, "auth", s.auth);
        s.keyName = J.str(o, "keyName", s.keyName);
        s.rateLimitPerMinute = J.integer(o, "rateLimitPerMinute", 0);
        s.plan = J.str(o, "plan", "");
        s.docsURL = J.str(o, "docsURL", "");
        s.defaultHeaders = J.list(o, "defaultHeaders", KeyValue::read);
        s.notes = J.str(o, "notes", "");
        s.projectFolder = J.str(o, "projectFolder", "");
        s.ide = J.enumOf(o, "ide", IdeChoice.VSCODE);
        s.secureAccountID = J.optInt(o, "secureAccountID");
        s.secureAccountName = J.str(o, "secureAccountName", "");
        return s;
    }

    @Override
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("provider", provider);
        o.addProperty("baseURL", baseURL);
        o.addProperty("auth", auth.raw());
        o.addProperty("keyName", keyName);
        o.addProperty("rateLimitPerMinute", rateLimitPerMinute);
        o.addProperty("plan", plan);
        o.addProperty("docsURL", docsURL);
        o.add("defaultHeaders", J.array(defaultHeaders));
        o.addProperty("notes", notes);
        o.addProperty("projectFolder", projectFolder);
        o.addProperty("ide", ide.raw());
        if (secureAccountID != null) o.addProperty("secureAccountID", secureAccountID);
        o.addProperty("secureAccountName", secureAccountName);
        return o;
    }
}
