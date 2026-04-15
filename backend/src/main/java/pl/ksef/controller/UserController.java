package pl.ksef.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pl.ksef.entity.AppUser;
import pl.ksef.repository.UserRepository;

import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    @GetMapping("/by-nip/{nip}")
    public ResponseEntity<UserResponse> getByNip(@PathVariable String nip) {
        return userRepository.findByNip(nip)
                .map(UserResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> get(@PathVariable UUID id) {
        // SECURITY: Brak autoryzacji — przed wdrożeniem produkcyjnym dodać weryfikację JWT.
        return userRepository.findById(id)
                .map(UserResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest req) {
        if (userRepository.existsByEmail(req.getEmail())) {
            throw new IllegalArgumentException("Użytkownik z adresem e-mail '" + req.getEmail() + "' już istnieje");
        }
        // TODO: Token KSeF jest przechowywany w bazie jako czysty tekst (plaintext).
        //       W środowisku produkcyjnym należy zaszyfrować go przed zapisem
        //       (np. JPA AttributeConverter z AES-256 lub integracja z HashiCorp Vault / AWS KMS).
        AppUser.InvoiceNumberPrefixMode prefixMode = req.getInvoicePrefixMode() != null
                ? req.getInvoicePrefixMode()
                : AppUser.InvoiceNumberPrefixMode.NONE;
        AppUser user = AppUser.builder()
                .email(req.getEmail())
                .nip(req.getNip())
                .companyName(req.getCompanyName())
                .ksefToken(req.getKsefToken())
                .invoicePrefixMode(prefixMode)
                .build();
        return ResponseEntity.ok(UserResponse.from(userRepository.save(user)));
    }

    @PutMapping("/{id}/invoice-prefix-mode")
    public ResponseEntity<Void> updatePrefixMode(
            @PathVariable UUID id,
            @RequestBody UpdatePrefixRequest req) {
        AppUser user = userRepository.findById(id)
                .orElseThrow(() -> new pl.ksef.exception.ResourceNotFoundException("User not found: " + id));
        user.setInvoicePrefixMode(req.getInvoicePrefixMode());
        userRepository.save(user);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/ksef-token")
    public ResponseEntity<Void> updateToken(
            @PathVariable UUID id,
            @RequestBody UpdateTokenRequest req) {
        // SECURITY: Brak autoryzacji — przed wdrożeniem produkcyjnym dodać weryfikację JWT.
        AppUser user = userRepository.findById(id)
                .orElseThrow(() -> new pl.ksef.exception.ResourceNotFoundException("User not found: " + id));
        user.setKsefToken(req.getKsefToken());
        userRepository.save(user);
        return ResponseEntity.noContent().build();
    }

    // ---- inner DTOs ----

    @Data
    public static class CreateUserRequest {
        @NotBlank private String email;
        @NotBlank @Size(min = 10, max = 10) private String nip;
        @NotBlank private String companyName;
        private String ksefToken;
        private AppUser.InvoiceNumberPrefixMode invoicePrefixMode;
    }

    @Data
    public static class UpdateTokenRequest {
        private String ksefToken;
    }

    @Data
    public static class UpdatePrefixRequest {
        private AppUser.InvoiceNumberPrefixMode invoicePrefixMode;
    }

    @Data
    public static class UserResponse {
        private UUID id;
        private String email;
        private String nip;
        private String companyName;
        private boolean hasKsefToken;
        private String invoicePrefixMode;

        static UserResponse from(AppUser u) {
            var r = new UserResponse();
            r.setId(u.getId());
            r.setEmail(u.getEmail());
            r.setNip(u.getNip());
            r.setCompanyName(u.getCompanyName());
            r.setHasKsefToken(u.getKsefToken() != null && !u.getKsefToken().isBlank());
            r.setInvoicePrefixMode(u.getInvoicePrefixMode() != null
                    ? u.getInvoicePrefixMode().name() : "NONE");
            return r;
        }
    }
}
