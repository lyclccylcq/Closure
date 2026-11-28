package app.billing;

/** Deliberately excludes provider response bodies, credentials and request URLs. */
public final class BalanceException extends RuntimeException {
    public BalanceException(String message) { super(message); }
}
