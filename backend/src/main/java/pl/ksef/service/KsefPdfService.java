package pl.ksef.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pl.ksef.entity.AppUser;
import pl.ksef.entity.Invoice;
import pl.ksef.repository.InvoiceRepository;
import pl.ksef.repository.UserRepository;
import pl.ksef.service.ksef.KsefApiClient;
import pl.ksef.service.ksef.KsefTokenManager;
import pl.ksef.exception.ResourceNotFoundException;

import java.util.UUID;

/**
 * Serwis generowania oficjalnych PDF-ów faktur z KSeF.
 *
 * Przepływ:
 * 1. Pobierz XML FA(3) faktury z KSeF API (GET /invoices/ksef/{ksefNumber})
 * 2. Przekaż XML do generatora PDF (ksef-pdf-generator)
 * 3. Zwróć bajty PDF
 *
 * TODO(F6/F7): Zintegrować bibliotekę ksef-pdf-generator z https://github.com/CIRFMF/ksef-pdf-generator
 *   Kroki integracji:
 *   a) Sprawdzić czy biblioteka jest dostępna w Maven Central / GitHub Packages
 *      (szukaj: pl.gov.mf.ksef:ksef-pdf-generator lub org.cirfmf:ksef-pdf-generator)
 *   b) Jeśli niedostępna jako artefakt — sklonować repo i zbudować lokalnie:
 *      git clone https://github.com/CIRFMF/ksef-pdf-generator
 *      mvn install  → instaluje do lokalnego ~/.m2
 *      Następnie dodać do pom.xml i odkomentować import poniżej.
 *   c) Zastąpić placeholder w metodzie generatePdfBytes() właściwym wywołaniem API biblioteki.
 *   d) Zbadać API biblioteki — prawdopodobnie: KsefPdfGenerator.generate(String xmlContent) : byte[]
 *      lub podobne. Sprawdzić README i testy w repozytorium.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class KsefPdfService {

    private final KsefApiClient ksefApiClient;
    private final KsefTokenManager ksefTokenManager;
    private final InvoiceRepository invoiceRepository;
    private final UserRepository userRepository;

    /**
     * Generuje oficjalny PDF faktury z KSeF dla wskazanej faktury.
     *
     * @param invoiceId UUID faktury w lokalnej bazie
     * @param userId    UUID właściciela (weryfikacja autoryzacji)
     * @return bajty pliku PDF
     * @throws ResourceNotFoundException jeśli faktura nie istnieje lub nie należy do userId
     * @throws IllegalStateException     jeśli faktura nie ma jeszcze numeru KSeF (nie wysłana)
     */
    public byte[] generatePdfForInvoice(UUID invoiceId, UUID userId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .filter(inv -> inv.getUserId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + invoiceId));

        if (invoice.getKsefNumber() == null || invoice.getKsefNumber().isBlank()) {
            throw new IllegalStateException(
                    "Faktura " + invoiceId + " nie ma numeru KSeF — PDF dostępny dopiero po wysłaniu do KSeF");
        }

        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        String accessToken = ksefTokenManager.getValidAccessToken(user);
        String xml = ksefApiClient.getInvoiceContent(accessToken, invoice.getKsefNumber());

        return generatePdfBytes(xml, invoice.getKsefNumber());
    }

    /**
     * Generuje PDF z XML FA(3) faktury.
     * Wywoływana zarówno przy pobieraniu PDF przez użytkownika (F7),
     * jak i przy dołączaniu PDF do maila (F6).
     *
     * @param xmlContent treść XML FA(3) faktury jako String (UTF-8)
     * @param ksefNumber numer KSeF — używany tylko w logach
     * @return bajty PDF
     *
     * TODO(F6/F7): Zastąpić poniższy placeholder właściwym wywołaniem ksef-pdf-generator.
     *   Przykład (API do weryfikacji po zapoznaniu się z biblioteką):
     *
     *   import pl.gov.mf.ksef.pdf.KsefPdfGenerator;  // lub właściwy package z biblioteki
     *
     *   return KsefPdfGenerator.generate(xmlContent);
     *
     *   Jeśli biblioteka wymaga dodatkowej konfiguracji (np. szablony XSLT, zasoby),
     *   sprawdź jej dokumentację / testy integracyjne.
     */
    public byte[] generatePdfBytes(String xmlContent, String ksefNumber) {
        // TODO(F6/F7): PLACEHOLDER — zastąpić właściwym wywołaniem ksef-pdf-generator
        log.warn("KsefPdfService.generatePdfBytes() — PLACEHOLDER, ksef-pdf-generator nie jest jeszcze zintegrowany. ksefNumber={}", ksefNumber);
        throw new UnsupportedOperationException(
                "PDF generator nie jest jeszcze zintegrowany. " +
                "Zainstaluj ksef-pdf-generator z https://github.com/CIRFMF/ksef-pdf-generator " +
                "i uzupełnij implementację w KsefPdfService.generatePdfBytes()");
    }
}
