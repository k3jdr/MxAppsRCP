package com.k3jdr.mxdatasql;

import java.sql.SQLException;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

import com.k3jdr.mx.core.ui.ErrorDialog;

/**
 * A database error with the server's message, detail, hint and SQLSTATE, and the error's 1-based character position
 * in the statement that was sent (PostgresService.swift DatabaseError).
 */
public final class DatabaseError extends Exception {
    private static final long serialVersionUID = 1L;
    private final String sqlState;
    private final Integer position;

    public DatabaseError(String message) {
        this(message, null, null);
    }

    public DatabaseError(String message, String sqlState, Integer position) {
        super(message);
        this.sqlState = sqlState;
        this.position = position;
    }

    public String sqlState() { return sqlState; }
    public Integer position() { return position; }

    /** The position relative to the user's SQL when it was sent after {@code count} characters of wrapper text. */
    public DatabaseError shifted(int count) {
        return new DatabaseError(getMessage(), sqlState, position == null ? null : position - count);
    }

    public DatabaseError withMessage(String message) {
        return new DatabaseError(message, sqlState, position);
    }

    /** Server details surfaced: message, detail, "Hint: …" and "(SQLSTATE …)". */
    public static DatabaseError from(Throwable t) {
        if (t instanceof DatabaseError d) return d;
        Throwable root = t;
        while (!(root instanceof SQLException) && root.getCause() != null && root.getCause() != root) root = root.getCause();
        if (root instanceof PSQLException pe && pe.getServerErrorMessage() != null) {
            ServerErrorMessage m = pe.getServerErrorMessage();
            StringBuilder text = new StringBuilder(m.getMessage() == null ? pe.getMessage() : m.getMessage());
            if (m.getDetail() != null) text.append('\n').append(m.getDetail());
            if (m.getHint() != null) text.append("\nHint: ").append(m.getHint());
            if (m.getSQLState() != null) text.append("\n(SQLSTATE ").append(m.getSQLState()).append(')');
            int pos = m.getPosition();
            return new DatabaseError(text.toString(), m.getSQLState(), pos > 0 ? pos : null);
        }
        if (root instanceof SQLException sql) {
            return new DatabaseError(sql.getMessage() == null ? sql.toString() : sql.getMessage(), sql.getSQLState(), null);
        }
        return new DatabaseError(ErrorDialog.describe(t));
    }

    public static String message(Throwable t) {
        return from(t).getMessage();
    }
}
