package com.k3jdr.mxdatasql;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Diagram ▸ Write DBML (DBMLWriter.swift): one catalog query for the chosen schemas, then DBML text — tables with
 * their columns, notes, indexes, checks as comments, every foreign key as a Ref, a TableGroup per schema.
 */
public final class DbmlWriter {
    private DbmlWriter() {}

    /** User schemas that have tables, public first. */
    public static final String SCHEMAS_SQL = """
            SELECT n.nspname::text FROM pg_namespace n
            WHERE n.nspname NOT LIKE 'pg\\_%' AND n.nspname <> 'information_schema'
              AND EXISTS (SELECT 1 FROM pg_class c WHERE c.relnamespace = n.oid AND c.relkind IN ('r', 'p'))
            ORDER BY n.nspname <> 'public', 1""";

    public static final String CATALOG_SQL = """
            SELECT json_build_object(
             'tables', (SELECT json_agg(json_build_object('schema', n.nspname, 'name', c.relname,
               'note', obj_description(c.oid, 'pg_class'),
               'columns', (SELECT json_agg(json_build_object(
                    'name', a.attname, 'type', format_type(a.atttypid, a.atttypmod), 'notnull', a.attnotnull,
                    'default', pg_get_expr(d.adbin, d.adrelid), 'identity', a.attidentity, 'note', col_description(c.oid, a.attnum)) ORDER BY a.attnum)
                  FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                  WHERE a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped),
               'constraints', (SELECT json_agg(json_build_object('name', k.conname, 'type', k.contype,
                    'cols', (SELECT json_agg(att.attname ORDER BY u.ord) FROM unnest(k.conkey) WITH ORDINALITY u(n, ord)
                               JOIN pg_attribute att ON att.attrelid = k.conrelid AND att.attnum = u.n),
                    'fschema', fn.nspname, 'ftable', fc.relname,
                    'fcols', (SELECT json_agg(att.attname ORDER BY u.ord) FROM unnest(k.confkey) WITH ORDINALITY u(n, ord)
                                JOIN pg_attribute att ON att.attrelid = k.confrelid AND att.attnum = u.n),
                    'ondelete', k.confdeltype, 'def', pg_get_constraintdef(k.oid)) ORDER BY k.conname)
                  FROM pg_constraint k
                  LEFT JOIN pg_class fc ON fc.oid = k.confrelid
                  LEFT JOIN pg_namespace fn ON fn.oid = fc.relnamespace
                  WHERE k.conrelid = c.oid),
               'indexes', (SELECT json_agg(json_build_object('name', i.relname, 'unique', x.indisunique, 'primary', x.indisprimary,
                    'method', am.amname,
                    'keys', (SELECT json_agg(pg_get_indexdef(x.indexrelid, k, true) ORDER BY k)
                               FROM generate_series(1, x.indnkeyatts) AS k),
                    'predicate', pg_get_expr(x.indpred, x.indrelid, true)) ORDER BY i.relname)
                  FROM pg_index x JOIN pg_class i ON i.oid = x.indexrelid JOIN pg_am am ON am.oid = i.relam
                  WHERE x.indrelid = c.oid)
              ) ORDER BY array_position(string_to_array($1, ','), n.nspname::text), c.relname)
              FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
              WHERE n.nspname = ANY (string_to_array($1, ',')) AND c.relkind IN ('r', 'p') AND NOT c.relispartition)
            )::text""";

    public record Result(String text, int tables, int columns, int refs) {}

    /** Reads the catalog JSON for {@code schemas}. */
    public static JsonArray read(PgService svc, List<String> schemas) throws DatabaseError {
        List<String[]> rows = svc.rows(CATALOG_SQL, String.join(",", schemas));
        if (rows.isEmpty() || rows.get(0)[0] == null) return new JsonArray();
        JsonElement tables = JsonParser.parseString(rows.get(0)[0]).getAsJsonObject().get("tables");
        return tables != null && tables.isJsonArray() ? tables.getAsJsonArray() : new JsonArray();
    }

    private static final Set<String> KEYWORDS = Set.of("note", "indexes", "ref", "table", "enum", "project", "tablegroup");

    static String ident(String name) {
        return name.matches("^[A-Za-z_][A-Za-z0-9_]*$") && !KEYWORDS.contains(name.toLowerCase())
                ? name : "\"" + name.replace("\"", "\\\"") + "\"";
    }

    static String tableName(String schema, String name) {
        return schema.equals("public") ? ident(name) : ident(schema) + "." + ident(name);
    }

    static String quote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'";
    }

    static String dbmlType(String t) {
        Matcher m = Pattern.compile("^character varying\\((\\d+)\\)$").matcher(t);
        if (m.find()) return "varchar(" + m.group(1) + ")";
        m = Pattern.compile("^character\\((\\d+)\\)$").matcher(t);
        if (m.find()) return "char(" + m.group(1) + ")";
        return switch (t) {
            case "character varying" -> "varchar";
            case "timestamp with time zone" -> "timestamptz";
            case "timestamp without time zone" -> "timestamp";
            case "double precision" -> "float8";
            default -> t.contains(" ") ? "\"" + t + "\"" : t;
        };
    }

    static String dbmlDefault(String expr) {
        String v = expr.replaceAll("::[\\w\\s]+(\\[\\])?$", "");
        if (v.matches("^(-?\\d+(\\.\\d+)?|true|false)$") || v.matches("^'[^']*'$")) return "default: " + v;
        return "default: `" + v + "`";
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        JsonElement e = o.get(key);
        if (e != null && e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) out.add(x.getAsString());
        return out;
    }

    private static List<JsonObject> objects(JsonObject o, String key) {
        List<JsonObject> out = new ArrayList<>();
        JsonElement e = o.get(key);
        if (e != null && e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) out.add(x.getAsJsonObject());
        return out;
    }

    public static Result generate(JsonArray tables, String source, List<String> schemas) {
        Map<String, String> onDelete = Map.of("c", "cascade", "n", "set null", "d", "set default", "r", "restrict");
        List<String> lines = new ArrayList<>();
        String[] parts = source.split("/");
        lines.add("// " + source + " (schema" + (schemas.size() == 1 ? "" : "s") + " " + String.join(", ", schemas) + "), "
                + "written by MxDataSQL ▸ Diagram ▸ Write DBML on " + LocalDate.now());
        lines.add("");
        lines.add("Project " + ident(parts.length == 0 ? "database" : parts[parts.length - 1]) + " {");
        lines.add("  database_type: 'PostgreSQL'");
        lines.add("}");
        lines.add("");
        List<String> refs = new ArrayList<>();
        Map<String, List<String>> groups = new LinkedHashMap<>();
        int columnCount = 0;
        for (JsonElement te : tables) {
            JsonObject t = te.getAsJsonObject();
            String schema = str(t, "schema"), name = str(t, "name");
            String me = tableName(schema, name);
            groups.computeIfAbsent(schema, k -> new ArrayList<>()).add(me);
            List<JsonObject> cons = objects(t, "constraints");
            List<String> pk = new ArrayList<>();
            for (JsonObject c : cons) if ("p".equals(str(c, "type"))) { pk = strings(c, "cols"); break; }
            Set<String> unique1 = new HashSet<>();
            for (JsonObject c : cons) {
                List<String> cols = strings(c, "cols");
                if ("u".equals(str(c, "type")) && cols.size() == 1) unique1.add(cols.get(0));
            }
            lines.add("Table " + me + " {");
            if (str(t, "note") != null) lines.add("  Note: " + quote(str(t, "note")));
            for (JsonObject c : objects(t, "columns")) {
                columnCount++;
                String cname = str(c, "name");
                List<String> s = new ArrayList<>();
                if (pk.size() == 1 && cname.equals(pk.get(0))) s.add("pk");
                String def = str(c, "default") == null ? "" : str(c, "default");
                String identity = str(c, "identity");
                if (def.startsWith("nextval(") || "a".equals(identity) || "d".equals(identity)) s.add("increment");
                else if (!def.isEmpty()) s.add(dbmlDefault(def));
                boolean notnull = c.get("notnull") != null && !c.get("notnull").isJsonNull() && c.get("notnull").getAsBoolean();
                if (notnull && !s.contains("pk")) s.add("not null");
                if (unique1.contains(cname) && !s.contains("pk")) s.add("unique");
                if (str(c, "note") != null) s.add("note: " + quote(str(c, "note")));
                lines.add("  " + ident(cname) + " " + dbmlType(str(c, "type")) + (s.isEmpty() ? "" : " [" + String.join(", ", s) + "]"));
            }
            List<String> idx = new ArrayList<>();
            if (pk.size() > 1) idx.add("    (" + join(pk) + ") [pk]");
            for (JsonObject c : cons) {
                List<String> cols = strings(c, "cols");
                if ("u".equals(str(c, "type")) && cols.size() > 1) idx.add("    (" + join(cols) + ") [unique]");
            }
            Set<String> constraintNames = new HashSet<>();
            for (JsonObject c : cons) constraintNames.add(str(c, "name"));
            for (JsonObject i : objects(t, "indexes")) {
                boolean primary = i.get("primary").getAsBoolean();
                if (primary || constraintNames.contains(str(i, "name"))) continue;
                List<String> keys = strings(i, "keys");
                if (keys.isEmpty()) continue;
                List<String> spec = new ArrayList<>();
                for (String k : keys) spec.add(k.matches("^[a-z_][a-z0-9_]*$") || k.startsWith("\"") ? k : "`" + k + "`");
                List<String> settings = new ArrayList<>(List.of("name: '" + str(i, "name") + "'"));
                if (i.get("unique").getAsBoolean()) settings.add("unique");
                String method = str(i, "method");
                if ("hash".equals(method)) settings.add("type: hash");
                List<String> extra = new ArrayList<>();
                if (method != null && !method.equals("btree") && !method.equals("hash")) extra.add(method + " index");
                if (str(i, "predicate") != null) extra.add("partial: WHERE " + str(i, "predicate"));
                if (!extra.isEmpty()) settings.add("note: " + quote(String.join("; ", extra)));
                idx.add("    " + (spec.size() > 1 ? "(" + String.join(", ", spec) + ")" : spec.get(0)) + " [" + String.join(", ", settings) + "]");
            }
            if (!idx.isEmpty()) {
                lines.add("");
                lines.add("  indexes {");
                lines.addAll(idx);
                lines.add("  }");
            }
            for (JsonObject c : cons) if ("c".equals(str(c, "type"))) lines.add("  // check: " + str(c, "def"));
            for (JsonObject c : cons) if ("x".equals(str(c, "type"))) lines.add("  // exclude: " + str(c, "def"));
            lines.add("}");
            lines.add("");
            for (JsonObject c : cons) {
                if (!"f".equals(str(c, "type"))) continue;
                List<String> cols = strings(c, "cols"), fcols = strings(c, "fcols");
                String ft = str(c, "ftable");
                if (cols.isEmpty() || fcols.isEmpty() || ft == null) continue;
                String target = tableName(str(c, "fschema") == null ? "public" : str(c, "fschema"), ft);
                String lhs = cols.size() == 1 ? me + "." + ident(cols.get(0)) : me + ".(" + join(cols) + ")";
                String rhs = fcols.size() == 1 ? target + "." + ident(fcols.get(0)) : target + ".(" + join(fcols) + ")";
                String od = str(c, "ondelete") == null ? null : onDelete.get(str(c, "ondelete"));
                refs.add("Ref: " + lhs + " > " + rhs + (od == null ? "" : " [delete: " + od + "]"));
            }
        }
        if (!refs.isEmpty()) {
            refs.sort(null);
            lines.add("// Relationships (foreign keys)");
            lines.addAll(refs);
            lines.add("");
        }
        if (groups.size() > 1) {
            groups.forEach((schema, members) -> {
                lines.add("TableGroup " + schema.replaceAll("[^A-Za-z0-9_]", "_") + "_schema {");
                for (String m : members) lines.add("  " + m);
                lines.add("}");
                lines.add("");
            });
        }
        return new Result(String.join("\n", lines), tables.size(), columnCount, refs.size());
    }

    private static String join(List<String> names) {
        List<String> out = new ArrayList<>();
        for (String n : names) out.add(ident(n));
        return String.join(", ", out);
    }
}
