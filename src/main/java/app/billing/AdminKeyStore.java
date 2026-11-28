package app.billing;

/** OS credential storage, separate from account calibration and ordinary chat settings. */
interface AdminKeyStore extends AutoCloseable {
    String load();
    void save(String key);
    void clear();
    boolean persistent();
    @Override default void close() { }

    static AdminKeyStore systemDefault() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")
                ? new MacOsAdminKeyStore() : memory();
    }
    static AdminKeyStore memory() {
        return new AdminKeyStore() {
            private String value;
            public synchronized String load() { return value; }
            public synchronized void save(String key) { value = key; }
            public synchronized void clear() { value = null; }
            public synchronized void close() { value = null; }
            public boolean persistent() { return false; }
        };
    }
}
