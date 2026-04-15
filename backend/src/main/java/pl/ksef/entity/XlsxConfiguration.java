package pl.ksef.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "xlsx_configurations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class XlsxConfiguration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String name;

    private String description;

    /**
     * Map of fieldName -> FieldMapping.
     * Example: {"sellerName": {"type": "VALUE", "value": "Firma XYZ sp. z o.o."},
     *            "buyerNip":   {"type": "CELL",  "cellRef": "B5", "sheetIndex": 0}}
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "field_mappings", columnDefinition = "jsonb")
    private Map<String, FieldMapping> fieldMappings;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @lombok.Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldMapping {
        private MappingType type;
        private String value;       // used when type=VALUE
        private String cellRef;     // e.g. "A1", "B5" — used when type=CELL
        private Integer sheetIndex; // 0-based, default 0

        /** Lista komórek do złączenia przecinkami — używana gdy type=MULTI_CELL. */
        private List<CellRef> cells;

        public enum MappingType {
            VALUE,
            CELL,
            MULTI_CELL
        }

        /** Referecja do pojedynczej komórki w trybie MULTI_CELL. */
        @lombok.Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class CellRef {
            private String cellRef;     // e.g. "A1", "C3"
            private Integer sheetIndex; // 0-based, default 0
        }
    }
}
