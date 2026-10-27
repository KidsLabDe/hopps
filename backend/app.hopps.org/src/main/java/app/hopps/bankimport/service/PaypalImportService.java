package app.hopps.bankimport.service;

import app.hopps.bankimport.domain.BankAccount;
import app.hopps.bankimport.domain.BankImport;
import app.hopps.bankimport.domain.BankImportStatus;
import app.hopps.bankimport.domain.BankTransaction;
import app.hopps.bankimport.parser.PaypalParser;
import app.hopps.bankimport.parser.PaypalParser.ParsedPaypalTransaction;
import app.hopps.bankimport.parser.PaypalParser.RowError;
import app.hopps.bankimport.repository.BankTransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Orchestrates a single PayPal import job: load file → decode → {@link PaypalParser} → persist
 * {@link BankTransaction}s. Mirrors {@link Mt940ImportService}; like MT940 it needs no schema.
 * <p>
 * Rows the parser drops on purpose (holds, the foreign side of conversions) count neither as imported nor as errors, so
 * {@code totalRows} can be larger than the sum of the other counters.
 */
@ApplicationScoped
public class PaypalImportService {

    private static final Logger LOG = LoggerFactory.getLogger(PaypalImportService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_REPORTED_ERRORS = 500;
    private static final int MAX_ROWS = 5000;

    @Inject
    BankTransactionRepository transactionRepository;

    @Inject
    DedupeHashService dedupeHashService;

    @Inject
    ImportFileStorageService fileStorage;

    @Inject
    BankImportService bankImportService;

    @Inject
    PaypalFeeBookingService feeBookingService;

    @Transactional
    public void runImport(Long importId) {
        BankImport job = BankImport.findById(importId);
        if (job == null) {
            LOG.warn("BankImport {} not found, skipping", importId);
            return;
        }
        BankAccount account = job.getBankAccount();

        ArrayNode errors = JSON.createArrayNode();
        int importedRows = 0;
        int duplicateRows = 0;

        try {
            byte[] bytes = fileStorage.readImportFile(job.getS3FileKey());
            PaypalParser.Result result = PaypalParser.parse(Mt940ImportService.decodeWithFallback(bytes));
            int totalRows = result.sourceRows();
            if (totalRows > MAX_ROWS) {
                fail(job, "Datei enthält " + totalRows + " Zeilen — Maximum sind " + MAX_ROWS);
                return;
            }
            for (RowError error : result.errors()) {
                appendError(errors, error.lineNumber(), error.rawLine(), error.message());
            }
            int errorRows = result.errors().size();

            List<ParsedPaypalTransaction> transactions = result.transactions();
            int checkpointInterval = Math.max(1, transactions.size() / 20);

            String[] hashes = new String[transactions.size()];
            Set<String> dedupeHashes = new HashSet<>();
            for (int i = 0; i < transactions.size(); i++) {
                ParsedPaypalTransaction tx = transactions.get(i);
                // The PayPal transaction code is unique per transaction, so it stands in for the end-to-end reference.
                hashes[i] = dedupeHashService.computeHash(tx.bookingDate(), tx.amount(), null, tx.reference(),
                        tx.purpose());
                dedupeHashes.add(hashes[i]);
            }
            Set<String> existing = transactionRepository.findExistingDedupeHashes(account.getId(), dedupeHashes);

            Set<String> seenInThisImport = new HashSet<>();
            List<BankTransaction> fees = new ArrayList<>();
            for (int i = 0; i < transactions.size(); i++) {
                ParsedPaypalTransaction pt = transactions.get(i);
                String hash = hashes[i];
                if (existing.contains(hash) || !seenInThisImport.add(hash)) {
                    duplicateRows++;
                    continue;
                }

                BankTransaction tx = new BankTransaction();
                tx.setOrganization(account.getOrganization());
                tx.setBankAccount(account);
                tx.setBankImport(job);
                tx.setBookingDate(pt.bookingDate());
                tx.setValueDate(pt.bookingDate());
                tx.setAmount(pt.amount());
                tx.setCurrency(pt.currency() != null ? pt.currency() : account.getCurrency());
                tx.setPurpose(pt.purpose());
                tx.setCounterpartyName(pt.counterpartyName());
                tx.setTransactionType(pt.transactionType());
                tx.setBankReference(pt.reference());
                tx.setBalanceAfter(pt.balanceAfter());
                tx.setRawRow(pt.rawLine());
                tx.setDedupeHash(hash);
                tx.persist();
                importedRows++;
                if (PaypalParser.FEE_TYPE.equals(pt.transactionType())) {
                    fees.add(tx);
                }

                if ((importedRows + duplicateRows) % checkpointInterval == 0) {
                    bankImportService.reportProgress(importId,
                            percent(importedRows + duplicateRows, transactions.size()),
                            importedRows, duplicateRows, errorRows);
                }
            }

            feeBookingService.book(account, fees, job.getImportedBy());

            job.setTotalRows(totalRows);
            job.setImportedRows(importedRows);
            job.setDuplicateRows(duplicateRows);
            job.setErrorRows(errorRows);
            job.setProgress(100);
            job.setFinishedAt(Instant.now());
            if (errors.size() > 0) {
                ObjectNode report = JSON.createObjectNode();
                report.set("errors", errors);
                job.setErrorReport(report.toString());
            }
            job.setStatus(errorRows > 0 ? BankImportStatus.PARTIAL : BankImportStatus.COMPLETED);
            LOG.info("PayPal Import {} done: rows={}, skipped={}, imported={}, duplicate={}, error={}",
                    importId, totalRows, result.skippedRows(), importedRows, duplicateRows, errorRows);

        } catch (RuntimeException fatal) {
            LOG.error("PayPal Import {} failed", importId, fatal);
            fail(job, fatal.getMessage());
        }
    }

    private void fail(BankImport job, String reason) {
        job.setStatus(BankImportStatus.FAILED);
        job.setFailureReason(reason);
        job.setFinishedAt(Instant.now());
    }

    private static int percent(int done, int total) {
        return total == 0 ? 100 : Math.min(99, (int) ((done * 100L) / total));
    }

    private void appendError(ArrayNode errors, int rowNumber, String rawRow, String message) {
        if (errors.size() >= MAX_REPORTED_ERRORS) {
            return;
        }
        ObjectNode entry = JSON.createObjectNode();
        entry.put("rowNumber", rowNumber);
        entry.put("rawRow", rawRow != null ? rawRow : "");
        entry.put("message", message);
        errors.add(entry);
    }
}
