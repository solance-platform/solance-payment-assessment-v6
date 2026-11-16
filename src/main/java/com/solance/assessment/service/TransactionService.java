package com.solance.assessment.service;

import com.solance.assessment.domain.Transaction;
import com.solance.assessment.domain.TransactionStatus;
import com.solance.assessment.exception.ErrorCode;
import com.solance.assessment.exception.PaymentException;
import com.solance.assessment.repository.TransactionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TransactionService {

  private static final Map<TransactionStatus, Set<TransactionStatus>> ALLOWED_TRANSITIONS =
      Map.of(
          TransactionStatus.PENDING_APPROVAL,
              Set.of(TransactionStatus.SUBMITTED, TransactionStatus.CANCELLED),
          TransactionStatus.SUBMITTED, Set.of(TransactionStatus.PROCESSING),
          TransactionStatus.PROCESSING,
              Set.of(TransactionStatus.COMPLETED, TransactionStatus.FAILED));

  private final TransactionRepository transactionRepository;

  public TransactionService(TransactionRepository transactionRepository) {
    this.transactionRepository = transactionRepository;
  }

  public Optional<Transaction> getTransaction(UUID transactionId) {
    return transactionRepository.findById(transactionId);
  }

  public List<Transaction> getTransactionsByClientId(UUID clientId) {
    return transactionRepository.findByClientId(clientId);
  }

  /**
   * Progress a transaction to a new status.
   *
   * <p>Updates come from the bank's webhook, which can redeliver the same update. An update to the
   * transaction's current status is therefore accepted as a no-op. Synchronized so the
   * check-then-update is atomic for the in-memory repository.
   *
   * @param transactionId the transaction to update
   * @param newStatus the target status
   * @return the updated transaction
   */
  public synchronized Transaction progressTransaction(
      UUID transactionId, TransactionStatus newStatus) {
    if (transactionId == null || newStatus == null) {
      throw new PaymentException(
          ErrorCode.INVALID_INPUT, "Transaction ID and target status are required");
    }
    Transaction transaction =
        transactionRepository
            .findById(transactionId)
            .orElseThrow(
                () ->
                    new PaymentException(
                        ErrorCode.TRANSACTION_NOT_FOUND, "Unknown transaction: " + transactionId));

    if (transaction.getStatus() == newStatus) {
      return transaction;
    }

    Set<TransactionStatus> allowed =
        ALLOWED_TRANSITIONS.getOrDefault(transaction.getStatus(), Set.of());
    if (!allowed.contains(newStatus)) {
      throw new PaymentException(
          ErrorCode.INVALID_STATUS_TRANSITION,
          "Cannot move transaction from " + transaction.getStatus() + " to " + newStatus);
    }

    transaction.setStatus(newStatus);
    transaction.setUpdatedAt(Instant.now());
    transactionRepository.save(transaction);
    return transaction;
  }
}
