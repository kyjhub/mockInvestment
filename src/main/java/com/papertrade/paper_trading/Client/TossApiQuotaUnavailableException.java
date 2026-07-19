package com.papertrade.paper_trading.Client;

public class TossApiQuotaUnavailableException extends RuntimeException {

    private final String group;
    private final long retryAfterSeconds;

    public TossApiQuotaUnavailableException(String group, long retryAfterSeconds, String message) {
        super(message);
        this.group = group;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String group() {
        return group;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
