package pl.ksef.service.ksef;

public class KsefException extends RuntimeException {
    public KsefException(String message) { super(message); }
    public KsefException(String message, Throwable cause) { super(message, cause); }
}
