package pl.ksef.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import pl.ksef.dto.UserDto;
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
    public ResponseEntity<UserDto.PublicUserView> getByNip(@PathVariable String nip) {
        return userRepository.findByNip(nip)
                .map(UserDto.PublicUserView::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody UserDto.LoginRequest req) {
        if (req.getNip() == null || req.getNip().isBlank()) {
            return ResponseEntity.badRequest().body(new UserDto.ErrorResponse("Pole NIP jest wymagane."));
        }
        AppUser user = userRepository.findByNip(req.getNip().trim()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new UserDto.ErrorResponse("Nie znaleziono konta dla podanego NIP."));
        }
        boolean pinProvided = req.getPin() != null && !req.getPin().isBlank();
        if (user.getPinHash() == null) {
            if (pinProvided) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new UserDto.ErrorResponse("To konto nie ma ustawionego kodu PIN."));
            }
        } else {
            if (!pinProvided || !bcrypt.matches(req.getPin(), user.getPinHash())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new UserDto.ErrorResponse("Nieprawidłowy kod PIN."));
            }
        }
        return ResponseEntity.ok(UserDto.UserResponse.from(user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserDto.UserResponse> get(@PathVariable UUID id) {
        // SECURITY: Brak autoryzacji — przed wdrożeniem produkcyjnym dodać weryfikację JWT.
        return userRepository.findById(id)
                .map(UserDto.UserResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<UserDto.UserResponse> create(@Valid @RequestBody UserDto.CreateUserRequest req) {
        if (userRepository.existsByEmail(req.getEmail())) {
            throw new IllegalArgumentException("Użytkownik z adresem e-mail '" + req.getEmail() + "' już istnieje");
        }
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
        return ResponseEntity.ok(UserDto.UserResponse.from(userRepository.save(user)));
    }

    @PutMapping("/{id}/invoice-prefix-mode")
    public ResponseEntity<Void> updatePrefixMode(
            @PathVariable UUID id,
            @RequestBody UserDto.UpdatePrefixRequest req) {
        AppUser user = userRepository.findById(id)
                .orElseThrow(() -> new pl.ksef.exception.ResourceNotFoundException("User not found: " + id));
        user.setInvoicePrefixMode(req.getInvoicePrefixMode());
        userRepository.save(user);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/pin")
    public ResponseEntity<Void> setPin(
            @PathVariable UUID id,
            @Valid @RequestBody UserDto.SetPinRequest req) {
        AppUser user = userRepository.findById(id)
                .orElseThrow(() -> new pl.ksef.exception.ResourceNotFoundException("User not found: " + id));
        user.setPinHash(bcrypt.encode(req.getPin()));
        userRepository.save(user);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/ksef-token")
    public ResponseEntity<Void> updateToken(
            @PathVariable UUID id,
            @RequestBody UserDto.UpdateTokenRequest req) {
        // SECURITY: Brak autoryzacji — przed wdrożeniem produkcyjnym dodać weryfikację JWT.
        AppUser user = userRepository.findById(id)
                .orElseThrow(() -> new pl.ksef.exception.ResourceNotFoundException("User not found: " + id));
        user.setKsefToken(req.getKsefToken());
        userRepository.save(user);
        return ResponseEntity.noContent().build();
    }
}
