package app.hopps.bankimport.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request payload for updating a bank account. All fields are optional; only non-null fields are applied (PATCH
 * semantics).
 */
public record BankAccountUpdateRequest(
        String name,
        @Schema(description = "Only for BANK accounts") @Size(min = 15, max = 34) String iban,
        @Schema(description = "Only for PAYPAL accounts") @Email @Size(max = 320) String paypalEmail,
        @Size(max = 11) String bic,
        String bankName,
        String accountHolder,
        @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter ISO 4217 code") String currency,
        BigDecimal openingBalance,
        LocalDate openingBalanceDate,
        String description,
        String color,
        Long defaultSchemaId,
        @Schema(description = "PayPal accounts only: replaces the fee booking; omit to keep it, bommelId null turns it off") FeeBookingDto feeBooking) {
}
