package com.k3jdr.mxacctmgr.port;

import java.net.http.HttpTimeoutException;

import com.k3jdr.mx.core.services.ServiceCatalog;

/** A failed request to a Layer 3 service (ServiceClient.Failure); getMessage() is the text to show. */
public final class ServiceException extends Exception {
    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** Refused or lost connection. */
        NOT_RUNNING,
        /** The service answered with an error, or not in time. */
        REJECTED,
        /** The account changed in the security master since it was opened (HTTP 409): the save was refused. */
        CONFLICT,
        /** The calling thread was interrupted (its screen went away): show nothing. */
        CANCELLED
    }

    public final Kind kind;

    private ServiceException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public boolean isCancelled() { return kind == Kind.CANCELLED; }

    public boolean isConflict() { return kind == Kind.CONFLICT; }

    public static ServiceException notRunning(String service) {
        return new ServiceException(Kind.NOT_RUNNING, notRunningMessage(service), null);
    }

    public static ServiceException rejected(String message) { return new ServiceException(Kind.REJECTED, message, null); }

    public static ServiceException conflict(String message) { return new ServiceException(Kind.CONFLICT, message, null); }

    public static ServiceException cancelled() { return new ServiceException(Kind.CANCELLED, "Cancelled.", null); }

    /** "<service> isn't running. Start the Layer 3 services with … (see Layer 3 Services)." */
    public static String notRunningMessage(String service) {
        return service + " isn't running. Start the Layer 3 services with " + ServiceCatalog.startCommand()
                + " (see Layer 3 Services).";
    }

    /**
     * What a failed request means: an interrupt is a cancellation, which no alert shows; a timeout says the service is
     * slow; only a refused or lost connection says it isn't running.
     */
    public static ServiceException from(Throwable error, String service) {
        if (error instanceof ServiceException e) return e;
        if (error instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return new ServiceException(Kind.CANCELLED, "Cancelled.", error);
        }
        if (error instanceof HttpTimeoutException) {
            return new ServiceException(Kind.REJECTED, service
                    + " is running but didn't answer in time — it may still be computing; try again.", error);
        }
        return new ServiceException(Kind.NOT_RUNNING, notRunningMessage(service), error);
    }
}
