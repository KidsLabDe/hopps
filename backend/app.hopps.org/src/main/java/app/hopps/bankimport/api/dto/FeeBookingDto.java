package app.hopps.bankimport.api.dto;

import app.hopps.bankimport.domain.BankAccount;
import app.hopps.transaction.domain.TransactionArea;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * How a PayPal account books its fees: each imported PayPal fee becomes a confirmed transaction on {@code bommelId},
 * with {@code area} and one value per category group. A null {@code bommelId} turns automatic fee booking off.
 */
@Schema(description = "Automatic booking of PayPal fees (PayPal accounts only); bommelId null turns it off")
public record FeeBookingDto(
        @Schema(description = "Bommel the fees are booked on; null turns automatic fee booking off") Long bommelId,
        @Schema(description = "Area (Sphäre) of the booked fees, optional") TransactionArea area,
        @Schema(description = "Category group id → value for the booked fees") Map<Long, String> categoryValues) {

    /** The account's fee booking, or null when it books no fees. */
    public static FeeBookingDto from(BankAccount account) {
        if (!account.booksFees()) {
            return null;
        }
        return new FeeBookingDto(account.getFeeBommel().id, account.getFeeArea(),
                Map.copyOf(account.getFeeCategoryValues()));
    }
}
