-- PayPal accounts have no IBAN. An account now has a type: bank accounts keep the IBAN as identifier, PayPal accounts
-- are identified by their PayPal e-mail address. Existing accounts are bank accounts.
ALTER TABLE BankAccount ADD COLUMN accountType varchar(20) NOT NULL DEFAULT 'BANK';
ALTER TABLE BankAccount ADD COLUMN paypalEmail varchar(320);
ALTER TABLE BankAccount ALTER COLUMN iban DROP NOT NULL;

ALTER TABLE BankAccount
    ADD CONSTRAINT bankaccount_accounttype_check CHECK (accountType IN ('BANK', 'PAYPAL'));

ALTER TABLE BankAccount
    ADD CONSTRAINT bankaccount_identifier_check CHECK (
        (accountType = 'BANK' AND iban IS NOT NULL)
            OR (accountType = 'PAYPAL' AND paypalEmail IS NOT NULL));
