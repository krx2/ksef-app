package pl.ksef.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import pl.ksef.entity.AppUser;
import pl.ksef.repository.UserRepository;

import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    @GetMapping("/by-nip/{nip}")
    public ResponseEntity<UserResponse> getByNip(@PathVariable String nip) {
        return userRepository.findByNip(nip)
                .map(UserResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        if (req.getNip() == null || req.getNip().isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Pole NIP jest wymagane."));
        }
        AppUser user = userRepository.findByNip(req.getNip().trim())
                .orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("Nie znaleziono konta dla podanego NIP."));
        }
        boolean pinProvided = req.getPin() != null && !req.getPin().isBlank();
        if (user.getPinHash() == null) {
            // Konto bez PIN-u — zezwalamy na logowanie tylko gdy PIN nie został podany
            if (pinProvided) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new ErrorResponse("To konto nie ma ustawionego kodu PIN."));
            }
        } else {
            // Konto z PIN-em — wymagane podanie poprawnego PIN-u
            if (!pinProvided || !bcrypt.matches(req.getPin(), user.getPinHash())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new ErrorResponse("Nieprawidłowy kod PIN."));
            }
        }
        return ResponseEntity.ok(UserResponse.from(user));
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
        String pinHash = (req.getPin() != null && !req.getPin().isBlank())
                ? bcrypt.encode(req.getPin())
                : null;
        AppUser user = AppUser.builder()
                .email(req.getEmail())
                .nip(req.getNip())
                .companyName(req.getCompanyName())
                .ksefToken(req.getKsefToken())
                .invoicePrefixMode(prefixMode)
                .pinHash(pinHash)
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

    @PutMapping("/{id}/pin")
    public ResponseEntity<Void> setPin(
            @PathVariable UUID id,
            @Valid @RequestBody SetPinRequest req) {
        AppUser user = userRepository.findById(id)
                .orElseThrow(() -> new pl.ksef.exception.ResourceNotFoundException("User not found: " + id));
        user.setPinHash(bcrypt.encode(req.getPin()));
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
    public static class LoginRequest {
        private String nip;
        // PIN jest opcjonalny — null/pusty oznacza logowanie bez PIN-u (dla kont migracyjnych)
        @Pattern(regexp = "\\d{4,6}", message = "PIN musi składać się z 4–6 cyfr")
        private String pin;
    }

    @Data
    public static class SetPinRequest {
        @NotBlank @Pattern(regexp = "\\d{4,6}", message = "PIN musi składać się z 4–6 cyfr") private String pin;
    }

    @Data
    public static class ErrorResponse {
        private final String error;
    }

    @Data
    public static class CreateUserRequest {
        @NotBlank private String email;
        @NotBlank @Size(min = 10, max = 10) private String nip;
        @NotBlank private String companyName;
        private String ksefToken;
        private AppUser.InvoiceNumberPrefixMode invoicePrefixMode;
        @Pattern(regexp = "\\d{4,6}", message = "PIN musi składać się z 4–6 cyfr") private String pin;
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
        private boolean hasPin;
        private String invoicePrefixMode;

        static UserResponse from(AppUser u) {
            var r = new UserResponse();
            r.setId(u.getId());
            r.setEmail(u.getEmail());
            r.setNip(u.getNip());
            r.setCompanyName(u.getCompanyName());
            r.setHasKsefToken(u.getKsefToken() != null && !u.getKsefToken().isBlank());
            r.setHasPin(u.getPinHash() != null);
            r.setInvoicePrefixMode(u.getInvoicePrefixMode() != null
                    ? u.getInvoicePrefixMode().name() : "NONE");
            return r;
        }
    }
}
