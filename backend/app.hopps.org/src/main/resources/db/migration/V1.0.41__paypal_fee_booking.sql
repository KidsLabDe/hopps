-- A PayPal account can book its fees on its own: every imported PayPal fee becomes a confirmed transaction on the fee
-- bommel, with the fee area and one value per category group. Without a fee bommel the fees stay open for matching.
ALTER TABLE BankAccount ADD COLUMN feeBommel_id bigint REFERENCES bommel ON DELETE SET NULL;
ALTER TABLE BankAccount ADD COLUMN feeArea varchar(30);

CREATE TABLE BankAccountFeeCategoryValue (
    bankAccount_id    bigint NOT NULL REFERENCES BankAccount ON DELETE CASCADE,
    category_group_id bigint NOT NULL REFERENCES category_group ON DELETE CASCADE,
    value             text   NOT NULL,
    PRIMARY KEY (bankAccount_id, category_group_id)
);
