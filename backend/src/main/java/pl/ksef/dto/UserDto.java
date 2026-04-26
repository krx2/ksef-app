package pl.ksef.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import pl.ksef.entity.AppUser;

import java.util.UUID;

public class UserDto {

    @Data
    public static class LoginRequest {
        private String nip;
        @Pattern(regexp = "\\d{4,6}", message = "PIN musi składać się z 4–6 cyfr")
        private String pin;
    }

    @Data
    public static class SetPinRequest {
        @NotBlank
        @Pattern(regexp = "\\d{4,6}", message = "PIN musi składać się z 4–6 cyfr")
        private String pin;
    }

    @Data
    public static class CreateUserRequest {
        @NotBlank private String email;
        @NotBlank @Size(min = 10, max = 10) private String nip;
        @NotBlank private String companyName;
        private String ksefToken;
        private AppUser.InvoiceNumberPrefixMode invoicePrefixMode;
        @Pattern(regexp = "\\d{4,6}", message = "PIN musi składać się z 4–6 cyfr")
        private String pin;
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
    public static class ErrorResponse {
        private final String error;
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

        public static UserResponse from(AppUser u) {
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

    /** Minimalna odpowiedź dla endpointu /by-nip — tylko id i flaga PIN (bez danych wrażliwych). */
    @Data
    public static class PublicUserView {
        private UUID id;
        private boolean hasPin;

        public static PublicUserView from(AppUser u) {
            var v = new PublicUserView();
            v.setId(u.getId());
            v.setHasPin(u.getPinHash() != null);
            return v;
        }
    }
}
