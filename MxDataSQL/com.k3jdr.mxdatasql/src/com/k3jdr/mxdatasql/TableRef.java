package com.k3jdr.mxdatasql;

/** A table or view by schema and name. */
public record TableRef(String schema, String name) {
    /** "schema"."name", quoted for SQL. */
    public String qualifiedSQL() { return Sql.ident(schema) + "." + Sql.ident(name); }

    /** schema.name, as shown. */
    public String displayName() { return schema + "." + name; }
}
