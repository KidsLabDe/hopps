package app.hopps.bankimport.api.dto;

import app.hopps.bankimport.domain.BankAccountType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request payload for creating a new bank account. {@code name} and {@code iban} are required (see Q2). All other
 * fields are optional. {@code bommelId}, when null, defaults to the organization's root bommel server-side.
 */
public record BankAccountCreateRequest(
        @NotBlank String name,
        @Schema(description = "BANK (default) or PAYPAL; cannot be changed later") BankAccountType type,
        @Schema(description = "Required for BANK accounts") @Size(min = 15, max = 34) String iban,
        @Schema(description = "Required for PAYPAL accounts") @Email @Size(max = 320) String paypalEmail,
        @Size(max = 11) String bic,
        String bankName,
        String accountHolder,
        @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter ISO 4217 code") String currency,
        BigDecimal openingBalance,
        LocalDate openingBalanceDate,
        String description,
        String color,
        Long defaultSchemaId,
        Long bommelId) {
}
