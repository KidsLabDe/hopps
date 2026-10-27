package app.hopps.bankimport.service;

import app.hopps.audit.domain.AuditAction;
import app.hopps.bankimport.domain.BankAccount;
import app.hopps.bankimport.domain.BankTransaction;
import app.hopps.bankimport.domain.BankTransactionMatchType;
import app.hopps.category.service.CategoryGroupService;
import app.hopps.document.domain.TradeParty;
import app.hopps.document.service.TradePartyService;
import app.hopps.transaction.domain.Transaction;
import app.hopps.transaction.domain.TransactionDeletedEvent;
import app.hopps.transaction.domain.TransactionStatus;
import app.hopps.transaction.repository.TransactionRepository;
import app.hopps.transaction.audit.TransactionAuditor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Books the PayPal fees of an import on their own, for a PayPal account with a fee booking (see
 * {@link BankAccount#booksFees()}): each fee movement gets a transaction on the fee bommel with the fee area and
 * category values, linked to it in full and confirmed. A fee transaction that still lacks a mandatory category stays a
 * draft, as it could not be confirmed by hand either.
 */
@ApplicationScoped
public class PaypalFeeBookingService {

    private static final Logger LOG = LoggerFactory.getLogger(PaypalFeeBookingService.class);

    @Inject
    CategoryGroupService categoryGroupService;

    @Inject
    TransactionRepository transactionRepository;

    @Inject
    TransactionAuditor transactionAuditor;

    @Inject
    BankTransactionMatchService matchService;

    @Inject
    TradePartyService tradePartyService;

    @Inject
    Event<TransactionDeletedEvent> transactionDeletedEvent;

    @Inject
    EntityManager em;

    /** Books the given fee movements; returns how many transactions were confirmed. */
    public int book(BankAccount account, List<BankTransaction> fees, String username) {
        if (!account.booksFees() || fees.isEmpty()) {
            return 0;
        }
        // The values were valid when they were saved; a group or value removed since is left out, not an error.
        List<String> invalid = new ArrayList<>();
        Map<Long, String> categoryValues = categoryGroupService.acceptedValues(account.getFeeBommel(),
                account.getFeeCategoryValues(), invalid);
        if (!invalid.isEmpty()) {
            LOG.warn("PayPal fee booking of account {}: values no longer valid for groups {}", account.getId(),
                    invalid);
        }

        int confirmed = 0;
        for (BankTransaction fee : fees) {
            Transaction tx = new Transaction();
            tx.setOrganization(account.getOrganization());
            tx.setBommel(account.getFeeBommel());
            tx.setArea(account.getFeeArea());
            tx.setCreatedBy(username);
            tx.setStatus(TransactionStatus.DRAFT);
            tx.setName(fee.getPurpose());
            tx.setTotal(fee.getAmount());
            tx.setCurrencyCode(fee.getCurrency());
            tx.setTransactionTime(fee.getBookingDate().atStartOfDay(ZoneOffset.UTC).toInstant());
            TradeParty paypal = new TradeParty();
            paypal.setOrganization(account.getOrganization());
            paypal.setName(fee.getCounterpartyName());
            tx.setCounterparty(paypal);
            tx.replaceCategoryValues(categoryValues);
            transactionRepository.persist(tx);
            transactionAuditor.created(tx);

            matchService.addRuleMatch(fee, tx, username);

            if (categoryGroupService.missingRequiredGroups(tx).isEmpty()) {
                tx.setStatus(TransactionStatus.CONFIRMED);
                transactionAuditor.statusChanged(tx, TransactionStatus.DRAFT, AuditAction.CONFIRM);
                confirmed++;
            }
        }
        LOG.info("PayPal fee booking of account {}: booked={}, confirmed={}", account.getId(), fees.size(),
                confirmed);
        return confirmed;
    }

    /**
     * Deletes the transactions booked by {@link #book} for the movements of an import, before the import is rolled
     * back. Transactions linked by hand are left alone; they only lose their link.
     */
    public int deleteBookedForImport(Long importId) {
        List<Transaction> booked = em.createQuery(
                "SELECT DISTINCT m.transaction FROM BankTransactionMatch m "
                        + "WHERE m.bankTransaction.bankImport.id = :importId AND m.matchType = :type "
                        + "AND m.transaction.document IS NULL",
                Transaction.class)
                .setParameter("importId", importId)
                .setParameter("type", BankTransactionMatchType.AUTO_RULE)
                .getResultList();
        List<BankTransaction> unlinked = em.createQuery(
                "SELECT DISTINCT m.bankTransaction FROM BankTransactionMatch m "
                        + "WHERE m.bankTransaction.bankImport.id = :importId AND m.matchType = :type",
                BankTransaction.class)
                .setParameter("importId", importId)
                .setParameter("type", BankTransactionMatchType.AUTO_RULE)
                .getResultList();
        for (Transaction tx : booked) {
            transactionDeletedEvent.fire(new TransactionDeletedEvent(tx.getId()));
            TradeParty sender = tx.getSender();
            TradeParty recipient = tx.getRecipient();
            transactionRepository.delete(tx);
            tradePartyService.deleteIfUnreferenced(sender);
            tradePartyService.deleteIfUnreferenced(recipient);
        }
        // Unlinking loaded the movements and marked them dirty. Write that now and let go of them, because the rollback
        // bulk-deletes their rows and the import they point to; the commit would otherwise flush them again.
        em.flush();
        unlinked.forEach(em::detach);
        return booked.size();
    }
}
