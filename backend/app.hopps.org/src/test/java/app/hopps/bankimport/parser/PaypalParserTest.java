package app.hopps.bankimport.parser;

import app.hopps.bankimport.parser.PaypalParser.ParsedPaypalTransaction;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaypalParserTest {

    private static final String SPARKASSE_HEADER = "\"Auftragskonto\";\"Buchungstag\";\"Valutadatum\";\"Buchungstext\";"
            + "\"Verwendungszweck\";\"Beguenstigter/Zahlungspflichtiger\";\"Kontonummer\";\"BLZ\";\"Betrag\";"
            + "\"Waehrung\";\"Info\"\n";

    private static final String ENGLISH_HEADER = "\"Date\",\"Time\",\"TimeZone\",\"Description\",\"Currency\","
            + "\"Gross\",\"Fee\",\"Net\",\"Balance\",\"Transaction ID\",\"From Email Address\",\"Name\",\"Bank Name\","
            + "\"Bank Account\",\"Shipping and Handling Amount\",\"Sales Tax\",\"Invoice ID\",\"Reference Txn ID\"\n";

    private static byte[] fixture() throws IOException {
        try (InputStream in = PaypalParserTest.class.getResourceAsStream("/bankimport/paypal-de-anon.csv")) {
            return in.readAllBytes();
        }
    }

    private static PaypalParser.Result parseFixture() throws IOException {
        return PaypalParser.parse(new String(fixture(), StandardCharsets.UTF_8));
    }

    private static ParsedPaypalTransaction byReference(List<ParsedPaypalTransaction> txs, String reference) {
        return txs.stream()
                .filter(t -> t.reference().equals(reference))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No transaction " + reference));
    }

    @Test
    void detectsGermanExportWithBom() throws IOException {
        assertTrue(PaypalParser.isPaypal(fixture()));
    }

    @Test
    void detectsEnglishExport() {
        assertTrue(PaypalParser.isPaypal(ENGLISH_HEADER.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void doesNotClaimBankCsv() {
        assertFalse(PaypalParser.isPaypal(SPARKASSE_HEADER.getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    void booksEachMovementOnceInTheBalanceCurrency() throws IOException {
        PaypalParser.Result result = parseFixture();

        assertEquals("EUR", result.currency());
        assertEquals(13, result.sourceRows());
        assertEquals(List.of(), result.errors());
        assertEquals(
                List.of("AAA001", "AAA001-FEE", "PAY002", "USD001", "USDR01", "TOPUP1", "RFD001", "RFD001-FEE",
                        "WDR001"),
                result.transactions().stream().map(ParsedPaypalTransaction::reference).toList());
        assertTrue(result.transactions().stream().allMatch(t -> t.currency().equals("EUR")));
        // 2 hold rows, 2 home-side conversions folded into their payments, 2 foreign-side conversions.
        assertEquals(6, result.skippedRows());
    }

    @Test
    void transactionsAddUpToTheBalance() throws IOException {
        List<ParsedPaypalTransaction> txs = parseFixture().transactions();

        BigDecimal balance = new BigDecimal("100.00");
        for (ParsedPaypalTransaction tx : txs) {
            balance = balance.add(tx.amount());
            assertEquals(0, balance.compareTo(tx.balanceAfter()), "balance after " + tx.reference());
        }
        assertEquals(0, balance.signum());
    }

    @Test
    void splitsGrossAndFee() throws IOException {
        List<ParsedPaypalTransaction> txs = parseFixture().transactions();

        ParsedPaypalTransaction donation = byReference(txs, "AAA001");
        assertEquals(new BigDecimal("50.00"), donation.amount());
        assertEquals("Erika Muster", donation.counterpartyName());
        assertEquals("Website-Zahlung · Rechnungsnr. INV-1 · spender@example.test", donation.purpose());
        assertEquals(LocalDate.of(2024, 1, 2), donation.bookingDate());

        ParsedPaypalTransaction fee = byReference(txs, "AAA001-FEE");
        assertEquals(new BigDecimal("-1.60"), fee.amount());
        assertEquals(PaypalParser.PAYPAL, fee.counterpartyName());
        assertEquals(PaypalParser.FEE_TYPE, fee.transactionType());

        // A refund gets the fee back: negative gross, positive fee.
        assertEquals(new BigDecimal("-50.00"), byReference(txs, "RFD001").amount());
        assertEquals(new BigDecimal("1.60"), byReference(txs, "RFD001-FEE").amount());
    }

    @Test
    void foreignPaymentIsBookedWithTheConvertedAmount() throws IOException {
        List<ParsedPaypalTransaction> txs = parseFixture().transactions();

        ParsedPaypalTransaction payment = byReference(txs, "USD001");
        assertEquals(new BigDecimal("-92.00"), payment.amount());
        assertEquals("US Vendor Inc", payment.counterpartyName());
        assertEquals("PayPal Express-Zahlung", payment.transactionType());
        assertTrue(payment.purpose().endsWith("Originalbetrag -100,00 USD"), payment.purpose());

        // The refund of part of that payment is converted back on its own.
        assertEquals(new BigDecimal("15.00"), byReference(txs, "USDR01").amount());
    }

    @Test
    void rowsWithoutNameTakeTheBankOrPaypalAsCounterparty() throws IOException {
        List<ParsedPaypalTransaction> txs = parseFixture().transactions();

        assertEquals("Beispielbank · XXXXXX1234", byReference(txs, "WDR001").counterpartyName());
        // A top-up refers to the payment it funded; it must not be mistaken for the reversal of a hold.
        assertEquals(PaypalParser.PAYPAL, byReference(txs, "TOPUP1").counterpartyName());
        assertEquals(new BigDecimal("20.00"), byReference(txs, "TOPUP1").amount());
    }

    @Test
    void foreignPaymentWithoutConversionIsReported() {
        String text = ENGLISH_HEADER
                + "\"02/01/2024\",\"10:00:00\",\"Europe/Berlin\",\"Website Payment\",\"EUR\",\"1,000.00\",\"0.00\","
                + "\"1,000.00\",\"1,000.00\",\"EN0001\",\"a@example.test\",\"A\",\"\",\"\",\"0.00\",\"0.00\",\"\",\"\"\n"
                + "\"03/01/2024\",\"10:00:00\",\"Europe/Berlin\",\"Website Payment\",\"USD\",\"-5.00\",\"0.00\","
                + "\"-5.00\",\"-5.00\",\"EN0002\",\"b@example.test\",\"B\",\"\",\"\",\"0.00\",\"0.00\",\"\",\"\"\n"
                + "\"04/01/2024\",\"10:00:00\",\"Europe/Berlin\",\"Website Payment\",\"EUR\",\"-10.00\",\"0.00\","
                + "\"-10.00\",\"990.00\",\"EN0003\",\"c@example.test\",\"C\",\"\",\"\",\"0.00\",\"0.00\",\"\",\"\"\n";

        PaypalParser.Result result = PaypalParser.parse(text);

        assertEquals(List.of("EN0001", "EN0003"),
                result.transactions().stream().map(ParsedPaypalTransaction::reference).toList());
        assertEquals(new BigDecimal("1000.00"), result.transactions().get(0).amount());
        assertEquals(LocalDate.of(2024, 1, 2), result.transactions().get(0).bookingDate());
        assertEquals(1, result.errors().size());
        assertEquals(3, result.errors().get(0).lineNumber());
    }
}
