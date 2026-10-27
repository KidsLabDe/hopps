package app.hopps.bankimport.domain;

/**
 * Kind of {@link BankAccount}. It decides how the account is identified and which import files it takes. Set when the
 * account is created and not changed afterwards.
 */
public enum BankAccountType {
    /** A bank account, identified by its IBAN. Takes CSV and MT940 statements. */
    BANK,
    /** A PayPal account, identified by its PayPal e-mail address. Takes PayPal activity exports. */
    PAYPAL
}
