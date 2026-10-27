package com.k3jdr.mxfilemgr.cloud;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonObject;

/**
 * A saved SFTP server (SFTP.swift's SFTPAccount): a host or .ssh\config alias, optional user, port and starting
 * folder. Signing in uses the SSH keys and agent, as ssh does.
 */
public record SftpAccount(String id, String name, String host, String user, String port, String path) {
    /** user@host, or the host (alias) alone. */
    public String destination() { return user == null || user.isBlank() ? host : user + "@" + host; }

    /** sftp://user@host:port/path, with the path's special characters encoded. */
    public String link(String remotePath) {
        StringBuilder encoded = new StringBuilder();
        String p = remotePath == null ? "" : remotePath;
        for (String part : p.split("/", -1)) {
            if (encoded.length() > 0 || p.startsWith("/")) encoded.append('/');
            encoded.append(URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        String path = encoded.toString().replaceFirst("^//", "/");
        return "sftp://" + destination() + (port == null || port.isBlank() ? "" : ":" + port.trim())
                + (path.isEmpty() ? "" : (path.startsWith("/") ? "" : "/") + path);
    }

    JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("host", host);
        o.addProperty("user", user == null ? "" : user);
        o.addProperty("port", port == null ? "" : port);
        o.addProperty("path", path == null ? "" : path);
        return o;
    }

    static SftpAccount fromJson(JsonObject o) {
        return new SftpAccount(s(o, "id"), s(o, "name"), s(o, "host"), s(o, "user"), s(o, "port"), s(o, "path"));
    }

    private static String s(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
