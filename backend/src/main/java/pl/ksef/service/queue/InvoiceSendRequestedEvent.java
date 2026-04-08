package pl.ksef.service.queue;

import java.util.UUID;

/**
 * Event publikowany przez InvoiceService po zapisie faktury do bazy danych.
 * Obsługiwany przez InvoiceQueuePublisher dopiero po zatwierdzeniu transakcji (AFTER_COMMIT),
 * co eliminuje race condition między commitem DB a odebraniem wiadomości przez konsumera.
 */
public record InvoiceSendRequestedEvent(UUID invoiceId, UUID userId) {}
