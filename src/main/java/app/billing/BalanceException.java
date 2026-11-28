package app.billing;

/** Deliberately excludes provider response bodies, credentials and request URLs. */
public final class BalanceException extends RuntimeException {
    public enum Action { NONE, CONFIGURE_OPENAI_ADMIN, ACCESS_OPENAI_ADMIN, REVIEW_OPENAI_ADMIN }
    private final Action action;
    public BalanceException(String message) { this(message, Action.NONE); }
    public BalanceException(String message, Action action) { super(message); this.action = action; }
    public Action action() { return action; }
}
