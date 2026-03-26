package pl.ksef.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import pl.ksef.entity.XlsxConfiguration.FieldMapping;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public class XlsxConfigDto {

    @Data
    public static class SaveRequest {
        @NotBlank
        private String name;
        private String description;
        @NotNull
        private Map<String, FieldMapping> fieldMappings;
    }

    @Data
    public static class Response {
        private UUID id;
        private UUID userId;
        private String name;
        private String description;
        private Map<String, FieldMapping> fieldMappings;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }
}
