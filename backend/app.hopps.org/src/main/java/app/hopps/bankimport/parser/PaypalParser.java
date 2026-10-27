package app.hopps.bankimport.parser;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parses the PayPal activity export ("Aktivitäten herunterladen", comma-separated, one row per balance movement) into
 * bookable transactions. Column positions are resolved from the header, so the German and the English export both work.
 * <p>
 * A PayPal export does not map one row to one booking, which is why it gets its own parser instead of a CSV schema:
 * <ul>
 * <li><b>Holds</b> (open authorization and its reversal) cancel each other out and are dropped as a pair.</li>
 * <li><b>Foreign currency:</b> a payment in USD is followed by two conversion rows (USD in, EUR out). The payment is
 * booked once, with the EUR amount of the conversion and the payment's counterparty; the original amount goes into the
 * purpose.</li>
 * <li><b>Fees:</b> a row with a fee becomes two transactions, the gross amount and the fee, so income and fee expense
 * can be booked separately (gross principle). Together they add up to the net movement of the balance.</li>
 * </ul>
 */
public final class PaypalParser {

    /** Shown as counterparty of the fee transactions and of rows without a name (top-ups, withdrawals). */
    public static final String PAYPAL = "PayPal";
    static final String FEE_TYPE = "PayPal-Gebühr";
    static final String FEE_REFERENCE_SUFFIX = "-FEE";

    private static final Pattern GERMAN_DATE = Pattern.compile("\\d{1,2}\\.\\d{1,2}\\.\\d{4}");
    private static final Pattern SLASH_DATE = Pattern.compile("\\d{1,2}/\\d{1,2}/\\d{4}");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    enum Column {
        DATE("Datum", "Date"),
        TIME("Uhrzeit", "Time"),
        DESCRIPTION("Beschreibung", "Description", "Type"),
        CURRENCY("Währung", "Currency"),
        GROSS("Brutto", "Gross"),
        FEE("Entgelt", "Gebühr", "Fee"),
        NET("Netto", "Net"),
        BALANCE("Guthaben", "Balance"),
        CODE("Transaktionscode", "Transaction ID"),
        EMAIL("Absender E-Mail-Adresse", "From Email Address"),
        NAME("Name"),
        BANK_NAME("Name der Bank", "Bank Name"),
        BANK_ACCOUNT("Bankkonto", "Bank Account"),
        INVOICE("Rechnungsnummer", "Invoice Number", "Invoice ID"),
        RELATED_CODE("Zugehöriger Transaktionscode", "Reference Txn ID");

        private final Set<String> headers;

        Column(String... headers) {
            this.headers = Arrays.stream(headers)
                    .map(PaypalParser::normalizeHeader)
                    .collect(Collectors.toSet());
        }

        /** Columns a file must have to be read as a PayPal export. */
        static final Set<Column> REQUIRED = Set.of(DATE, DESCRIPTION, CURRENCY, GROSS, FEE, NET, BALANCE, CODE,
                RELATED_CODE);
    }

    /** One bookable transaction. {@code reference} is the PayPal transaction code, unique per transaction. */
    public record ParsedPaypalTransaction(
            LocalDate bookingDate,
            BigDecimal amount,
            String currency,
            String transactionType,
            String purpose,
            String counterpartyName,
            String reference,
            BigDecimal balanceAfter,
            String rawLine) {
    }

    /** A source row that could not be booked, with its 1-based line number in the file. */
    public record RowError(int lineNumber, String rawLine, String message) {
    }

    /**
     * @param currency
     *            the currency the balance is kept in (the account currency the file is meant for)
     * @param sourceRows
     *            the number of data rows in the file
     * @param skippedRows
     *            rows that are not booked on purpose: holds and the foreign-currency side of conversions
     */
    public record Result(
            List<ParsedPaypalTransaction> transactions,
            String currency,
            int sourceRows,
            int skippedRows,
            List<RowError> errors) {
    }

    private record Row(
            int lineNumber,
            String rawLine,
            LocalDate date,
            String time,
            String description,
            String currency,
            BigDecimal gross,
            BigDecimal fee,
            BigDecimal net,
            BigDecimal balance,
            String code,
            String email,
            String name,
            String bankName,
            String bankAccount,
            String invoice,
            String relatedCode) {

        boolean hasName() {
            return !name.isEmpty();
        }

        boolean hasFee() {
            return fee.signum() != 0;
        }

        /** Rows PayPal books on its own: holds and conversions carry neither a name nor a fee. */
        boolean isSystemRow() {
            return !hasName() && !hasFee();
        }

        String timestamp() {
            return date + " " + time;
        }
    }

    private PaypalParser() {
    }

    /** True when the first line of the file is the header of a PayPal activity export. */
    public static boolean isPaypal(byte[] content) {
        int limit = Math.min(content.length, 2048);
        String head = new String(content, 0, limit, StandardCharsets.UTF_8);
        int newline = head.indexOf('\n');
        String firstLine = stripBom(newline >= 0 ? head.substring(0, newline) : head);
        try {
            return columnIndexes(parseRecords(firstLine).get(0)).keySet().containsAll(Column.REQUIRED);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static Result parse(String text) {
        List<List<String>> records = parseRecords(stripBom(text));
        if (records.isEmpty()) {
            throw new IllegalArgumentException("PayPal export is empty");
        }
        Map<Column, Integer> columns = columnIndexes(records.get(0));
        if (!columns.keySet().containsAll(Column.REQUIRED)) {
            throw new IllegalArgumentException("Not a PayPal export, missing columns: "
                    + Column.REQUIRED.stream().filter(c -> !columns.containsKey(c)).toList());
        }

        List<Row> rows = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> record = records.get(i);
            String raw = String.join(",", record);
            try {
                rows.add(toRow(i + 1, raw, record, columns));
            } catch (RuntimeException e) {
                errors.add(new RowError(i + 1, raw, e.getMessage()));
            }
        }
        return resolve(rows, errors, records.size() - 1);
    }

    private static Result resolve(List<Row> rows, List<RowError> errors, int sourceRows) {
        String home = homeCurrency(rows);
        List<Row> homeRows = rows.stream().filter(r -> r.currency().equals(home)).toList();
        if (homeRows.size() > 1 && homeRows.get(0).date().isAfter(homeRows.get(homeRows.size() - 1).date())) {
            // Newest first: work through it in booking order, so balances and holds are read in sequence.
            rows = new ArrayList<>(rows);
            Collections.reverse(rows);
        }
        Set<Row> skipped = new HashSet<>();

        // Holds: a nameless row reversing another nameless row it refers to, by the opposite amount.
        Map<String, Row> byCode = new HashMap<>();
        for (Row row : rows) {
            byCode.put(row.code(), row);
        }
        for (Row release : rows) {
            Row hold = byCode.get(release.relatedCode());
            if (hold != null && hold != release && release.isSystemRow() && hold.isSystemRow()
                    && hold.currency().equals(release.currency())
                    && hold.net().negate().compareTo(release.net()) == 0
                    && !skipped.contains(hold)) {
                skipped.add(hold);
                skipped.add(release);
            }
        }

        // Currency conversions: per related code and timestamp, the system rows on the home and on the foreign side.
        Map<String, List<Row>> conversions = new HashMap<>();
        for (Row row : rows) {
            if (!skipped.contains(row) && row.isSystemRow() && !row.relatedCode().isEmpty()) {
                conversions.computeIfAbsent(row.relatedCode() + "|" + row.timestamp(), k -> new ArrayList<>())
                        .add(row);
            }
        }

        // Foreign-currency payments, each paired with the home-currency conversion row that paid for it. Exports list
        // the foreign rows after all home rows, so the pairing comes first and the payment is booked where its
        // conversion sits in the home-currency sequence.
        Map<Row, Row> paymentByConversion = new HashMap<>();
        int foreignSystemRows = 0;
        for (Row row : rows) {
            if (row.currency().equals(home) || skipped.contains(row)) {
                continue;
            }
            if (row.isSystemRow()) {
                foreignSystemRows++;
                continue;
            }
            Row homeSide = findConversion(row, conversions, home, paymentByConversion.keySet());
            if (homeSide == null) {
                errors.add(new RowError(row.lineNumber(), row.rawLine(),
                        "Buchung in " + row.currency() + " ohne Umrechnung in " + home));
            } else {
                paymentByConversion.put(homeSide, row);
            }
        }

        List<ParsedPaypalTransaction> transactions = new ArrayList<>();
        // PayPal's balance column includes holds that are still open. Holds are not booked, so take them back out to
        // keep the balance in line with the transactions that are.
        BigDecimal openHolds = BigDecimal.ZERO;
        for (Row row : rows) {
            if (!row.currency().equals(home)) {
                continue;
            }
            if (skipped.contains(row)) {
                openHolds = openHolds.add(row.net());
                continue;
            }
            BigDecimal balance = row.balance().subtract(openHolds);
            Row payment = paymentByConversion.get(row);
            if (payment == null) {
                addHomeRow(row, balance, transactions);
                continue;
            }
            transactions.add(new ParsedPaypalTransaction(
                    payment.date(),
                    row.net(),
                    home,
                    payment.description(),
                    join(purpose(payment), "Originalbetrag " + german(payment.net()) + " " + payment.currency()
                            + (payment.hasFee() ? " (Gebühr " + german(payment.fee()) + " " + payment.currency() + ")"
                                    : "")),
                    counterparty(payment),
                    payment.code(),
                    balance,
                    payment.rawLine() + "\n" + row.rawLine()));
        }

        // Holds and the foreign side of conversions are dropped; the home side of a conversion is folded into its
        // payment.
        return new Result(transactions, home, sourceRows,
                skipped.size() + foreignSystemRows + paymentByConversion.size(), errors);
    }

    private static void addHomeRow(Row row, BigDecimal balance, List<ParsedPaypalTransaction> transactions) {
        if (!row.hasFee()) {
            transactions.add(new ParsedPaypalTransaction(row.date(), row.net(), row.currency(), row.description(),
                    purpose(row), counterparty(row), row.code(), balance, row.rawLine()));
            return;
        }
        // Gross first, then the fee: the balance after the gross amount is the final balance minus the fee.
        transactions.add(new ParsedPaypalTransaction(row.date(), row.gross(), row.currency(), row.description(),
                purpose(row), counterparty(row), row.code(), balance.subtract(row.fee()), row.rawLine()));
        transactions.add(new ParsedPaypalTransaction(row.date(), row.fee(), row.currency(), FEE_TYPE,
                FEE_TYPE + " zu Transaktion " + row.code(), PAYPAL, row.code() + FEE_REFERENCE_SUFFIX, balance,
                row.rawLine()));
    }

    /**
     * The home-currency conversion row booked together with a foreign-currency payment: same timestamp, related to the
     * payment (or to the payment it refunds), next to a foreign conversion row that offsets the payment exactly.
     */
    private static Row findConversion(Row payment, Map<String, List<Row>> conversions, String home,
            Set<Row> usedConversions) {
        for (String key : List.of(payment.code(), payment.relatedCode())) {
            if (key.isEmpty()) {
                continue;
            }
            List<Row> group = conversions.get(key + "|" + payment.timestamp());
            if (group == null) {
                continue;
            }
            boolean offsets = group.stream()
                    .anyMatch(r -> r.currency().equals(payment.currency())
                            && r.net().negate().compareTo(payment.net()) == 0);
            if (!offsets) {
                continue;
            }
            for (Row candidate : group) {
                if (candidate.currency().equals(home) && !usedConversions.contains(candidate)
                        && candidate.net().signum() == payment.net().signum()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** The currency most rows are in. Foreign-currency rows only appear around the occasional conversion. */
    private static String homeCurrency(List<Row> rows) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Row row : rows) {
            counts.merge(row.currency(), 1, Integer::sum);
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private static String purpose(Row row) {
        return join(row.description(),
                row.invoice().isEmpty() ? "" : "Rechnungsnr. " + row.invoice(),
                row.email());
    }

    private static String german(BigDecimal amount) {
        return amount.toPlainString().replace('.', ',');
    }

    private static String counterparty(Row row) {
        if (row.hasName()) {
            return row.name();
        }
        String bank = join(row.bankName(), row.bankAccount());
        return bank.isEmpty() ? PAYPAL : bank;
    }

    private static String join(String... parts) {
        return Arrays.stream(parts)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(" · "));
    }

    private static Row toRow(int lineNumber, String raw, List<String> record, Map<Column, Integer> columns) {
        return new Row(
                lineNumber,
                raw,
                parseDate(value(record, columns, Column.DATE)),
                value(record, columns, Column.TIME),
                value(record, columns, Column.DESCRIPTION),
                value(record, columns, Column.CURRENCY).toUpperCase(Locale.ROOT),
                parseAmount(value(record, columns, Column.GROSS)),
                parseAmountOrZero(value(record, columns, Column.FEE)),
                parseAmount(value(record, columns, Column.NET)),
                parseAmount(value(record, columns, Column.BALANCE)),
                value(record, columns, Column.CODE),
                value(record, columns, Column.EMAIL),
                value(record, columns, Column.NAME),
                value(record, columns, Column.BANK_NAME),
                value(record, columns, Column.BANK_ACCOUNT),
                value(record, columns, Column.INVOICE),
                value(record, columns, Column.RELATED_CODE));
    }

    private static String value(List<String> record, Map<Column, Integer> columns, Column column) {
        Integer index = columns.get(column);
        if (index == null || index >= record.size()) {
            return "";
        }
        return record.get(index).trim();
    }

    static LocalDate parseDate(String value) {
        if (GERMAN_DATE.matcher(value).matches()) {
            return DateAmountParser.parseDate(value, "d.M.yyyy");
        }
        if (SLASH_DATE.matcher(value).matches()) {
            // PayPal exports from European accounts write day first.
            return DateAmountParser.parseDate(value, "d/M/yyyy");
        }
        if (ISO_DATE.matcher(value).matches()) {
            return LocalDate.parse(value);
        }
        throw new IllegalArgumentException("Unbekanntes Datumsformat: '" + value + "'");
    }

    /** Reads "1.234,56" as well as "1,234.56": the separator that comes last is the decimal one. */
    static BigDecimal parseAmount(String value) {
        int comma = value.lastIndexOf(',');
        int dot = value.lastIndexOf('.');
        if (comma > dot) {
            return DateAmountParser.parseAmount(value, ',', '.');
        }
        return DateAmountParser.parseAmount(value, '.', ',');
    }

    private static BigDecimal parseAmountOrZero(String value) {
        return value.isEmpty() ? BigDecimal.ZERO : parseAmount(value);
    }

    private static Map<Column, Integer> columnIndexes(List<String> header) {
        Map<Column, Integer> indexes = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String name = normalizeHeader(header.get(i));
            for (Column column : Column.values()) {
                if (column.headers.contains(name) && !indexes.containsKey(column)) {
                    indexes.put(column, i);
                }
            }
        }
        return indexes;
    }

    private static String normalizeHeader(String header) {
        return stripBom(header).trim().toLowerCase(Locale.ROOT);
    }

    private static String stripBom(String text) {
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    private static List<List<String>> parseRecords(String text) {
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setIgnoreEmptyLines(true)
                .build()
                .parse(new StringReader(text))) {
            List<List<String>> records = new ArrayList<>();
            for (CSVRecord record : parser) {
                records.add(record.toList());
            }
            return records;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
