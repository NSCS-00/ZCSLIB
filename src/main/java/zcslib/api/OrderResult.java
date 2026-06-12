package zcslib.api;

/**
 * Return value for all {@code kernel.order()} calls.
 *
 * <p>Never null. Check {@link #ok} before accessing {@link #data}.
 */
public class OrderResult {
    private final boolean ok;
    private final String error;
    private final Object data;

    private OrderResult(boolean ok, String error, Object data) {
        this.ok = ok;
        this.error = error;
        this.data = data;
    }

    public static OrderResult success(Object data) {
        return new OrderResult(true, null, data);
    }

    public static OrderResult success() {
        return new OrderResult(true, null, null);
    }

    public static OrderResult fail(String error) {
        return new OrderResult(false, error, null);
    }

    public boolean isOk()      { return ok; }
    public String getError()   { return error; }
    @SuppressWarnings("unchecked")
    public <T> T getData()     { return (T) data; }

    @Override
    public String toString() {
        return ok ? "OrderResult{ok, data=" + data + "}"
                  : "OrderResult{fail, error=" + error + "}";
    }
}
