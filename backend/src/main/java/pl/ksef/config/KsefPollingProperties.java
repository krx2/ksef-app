package pl.ksef.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ksef.polling")
public class KsefPollingProperties {

    /** Maksymalna liczba prób pollingu numeru KSeF po wysłaniu faktury. */
    private int maxInvoicePollAttempts = 15;

    /** Przerwa między kolejnymi próbami pollingu (ms). */
    private long invoicePollIntervalMs = 2_000L;

    /** Rozmiar strony przy pobieraniu faktur z KSeF. */
    private int fetchPageSize = 100;

    /** Okno czasowe pobierania faktur — zakres wstecz w godzinach (overlap przy cyklu co 2h). */
    private int fetchWindowHours = 3;

    public int getMaxInvoicePollAttempts() { return maxInvoicePollAttempts; }
    public void setMaxInvoicePollAttempts(int v) { this.maxInvoicePollAttempts = v; }

    public long getInvoicePollIntervalMs() { return invoicePollIntervalMs; }
    public void setInvoicePollIntervalMs(long v) { this.invoicePollIntervalMs = v; }

    public int getFetchPageSize() { return fetchPageSize; }
    public void setFetchPageSize(int v) { this.fetchPageSize = v; }

    public int getFetchWindowHours() { return fetchWindowHours; }
    public void setFetchWindowHours(int v) { this.fetchWindowHours = v; }
}
