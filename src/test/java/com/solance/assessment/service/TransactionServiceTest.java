package com.solance.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solance.assessment.domain.PaymentScheme;
import com.solance.assessment.domain.SupportedCurrency;
import com.solance.assessment.domain.Transaction;
import com.solance.assessment.domain.TransactionStatus;
import com.solance.assessment.exception.ErrorCode;
import com.solance.assessment.exception.PaymentException;
import com.solance.assessment.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class TransactionServiceTest {

  private TransactionRepository transactionRepository;
  private TransactionService transactionService;

  @BeforeEach
  void setUp() {
    transactionRepository = new TransactionRepository();
    transactionService = new TransactionService(transactionRepository);
  }

  private Transaction savedTransaction(TransactionStatus status) {
    Transaction transaction =
        Transaction.builder()
            .id(UUID.randomUUID())
            .clientId(UUID.randomUUID())
            .paymentInstrumentId(UUID.randomUUID())
            .amount(new BigDecimal("5000"))
            .currency(SupportedCurrency.EUR)
            .status(status)
            .paymentScheme(PaymentScheme.SEPA)
            .recipientName("Jane Doe")
            .recipientIban("DE89370400440532013000")
            .reference("Invoice 123")
            .createdAt(Instant.now())
            .build();
    transactionRepository.save(transaction);
    return transaction;
  }

  @Test
  @DisplayName("should submit a transaction that is pending approval")
  void shouldSubmitPendingTransaction() {
    Transaction transaction = savedTransaction(TransactionStatus.PENDING_APPROVAL);

    Transaction result =
        transactionService.progressTransaction(transaction.getId(), TransactionStatus.SUBMITTED);

    assertThat(result.getStatus()).isEqualTo(TransactionStatus.SUBMITTED);
  }

  @Test
  @DisplayName("should cancel a transaction that is pending approval")
  void shouldCancelPendingTransaction() {
    Transaction transaction = savedTransaction(TransactionStatus.PENDING_APPROVAL);

    Transaction result =
        transactionService.progressTransaction(transaction.getId(), TransactionStatus.CANCELLED);

    assertThat(result.getStatus()).isEqualTo(TransactionStatus.CANCELLED);
  }

  @Test
  @DisplayName("should start processing a submitted transaction")
  void shouldStartProcessingSubmittedTransaction() {
    Transaction transaction = savedTransaction(TransactionStatus.SUBMITTED);

    Transaction result =
        transactionService.progressTransaction(transaction.getId(), TransactionStatus.PROCESSING);

    assertThat(result.getStatus()).isEqualTo(TransactionStatus.PROCESSING);
  }

  @Test
  @DisplayName("should complete a processing transaction")
  void shouldCompleteProcessingTransaction() {
    Transaction transaction = savedTransaction(TransactionStatus.PROCESSING);

    Transaction result =
        transactionService.progressTransaction(transaction.getId(), TransactionStatus.COMPLETED);

    assertThat(result.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
  }

  @Test
  @DisplayName("should fail a processing transaction")
  void shouldFailProcessingTransaction() {
    Transaction transaction = savedTransaction(TransactionStatus.PROCESSING);

    Transaction result =
        transactionService.progressTransaction(transaction.getId(), TransactionStatus.FAILED);

    assertThat(result.getStatus()).isEqualTo(TransactionStatus.FAILED);
  }

  @Test
  @DisplayName("should not cancel a transaction that was already submitted")
  void shouldNotCancelSubmittedTransaction() {
    Transaction transaction = savedTransaction(TransactionStatus.SUBMITTED);

    assertThatThrownBy(
            () ->
                transactionService.progressTransaction(
                    transaction.getId(), TransactionStatus.CANCELLED))
        .isInstanceOf(PaymentException.class)
        .satisfies(
            ex ->
                assertThat(((PaymentException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));
    assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.SUBMITTED);
  }

  @Test
  @DisplayName("should reject moving a completed transaction back to submitted")
  void shouldRejectCompletedToSubmitted() {
    Transaction transaction = savedTransaction(TransactionStatus.COMPLETED);

    assertThatThrownBy(
            () ->
                transactionService.progressTransaction(
                    transaction.getId(), TransactionStatus.SUBMITTED))
        .isInstanceOf(PaymentException.class)
        .satisfies(
            ex ->
                assertThat(((PaymentException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));
  }

  @Test
  @DisplayName("should reject an unknown transaction")
  void shouldRejectUnknownTransaction() {
    assertThatThrownBy(
            () ->
                transactionService.progressTransaction(
                    UUID.randomUUID(), TransactionStatus.SUBMITTED))
        .isInstanceOf(PaymentException.class)
        .satisfies(
            ex ->
                assertThat(((PaymentException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.TRANSACTION_NOT_FOUND));
  }

  // ---------------------------------------------------------------------------
  // Full transition matrix, duplicates, updatedAt and null input
  // ---------------------------------------------------------------------------

  /** The transitions allowed by the README, kept independent of the service's own table. */
  private static final Map<TransactionStatus, Set<TransactionStatus>> SPEC_TRANSITIONS =
      Map.of(
          TransactionStatus.PENDING_APPROVAL,
              Set.of(TransactionStatus.SUBMITTED, TransactionStatus.CANCELLED),
          TransactionStatus.SUBMITTED, Set.of(TransactionStatus.PROCESSING),
          TransactionStatus.PROCESSING,
              Set.of(TransactionStatus.COMPLETED, TransactionStatus.FAILED));

  static Stream<Arguments> everyStatusChange() {
    return Arrays.stream(TransactionStatus.values())
        .flatMap(
            from ->
                Arrays.stream(TransactionStatus.values())
                    .filter(to -> to != from)
                    .map(to -> Arguments.of(from, to)));
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @MethodSource("everyStatusChange")
  @DisplayName("should allow exactly the README transitions and reject every other one")
  void shouldEnforceTransitionMatrix(TransactionStatus from, TransactionStatus to) {
    Transaction transaction = savedTransaction(from);
    boolean allowed = SPEC_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);

    if (allowed) {
      Instant before = Instant.now();
      Transaction result = transactionService.progressTransaction(transaction.getId(), to);

      assertThat(result.getStatus()).isEqualTo(to);
      assertThat(result.getUpdatedAt()).isBetween(before, Instant.now());
    } else {
      assertThatThrownBy(() -> transactionService.progressTransaction(transaction.getId(), to))
          .isInstanceOf(PaymentException.class)
          .satisfies(
              ex ->
                  assertThat(((PaymentException) ex).getErrorCode())
                      .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));
      assertThat(transaction.getStatus()).isEqualTo(from);
      assertThat(transaction.getUpdatedAt()).isNull();
    }
  }

  @ParameterizedTest(name = "{0} -> {0}")
  @EnumSource(TransactionStatus.class)
  @DisplayName("should accept a duplicate delivery of the current status as a no-op")
  void shouldTreatDuplicateDeliveryAsNoOp(TransactionStatus status) {
    Transaction transaction = savedTransaction(status);

    Transaction result = transactionService.progressTransaction(transaction.getId(), status);

    assertThat(result.getStatus()).isEqualTo(status);
    assertThat(result.getUpdatedAt()).isNull();
  }

  @Test
  @DisplayName("should reject a null transaction ID with INVALID_INPUT")
  void shouldRejectNullTransactionId() {
    assertThatThrownBy(
            () -> transactionService.progressTransaction(null, TransactionStatus.SUBMITTED))
        .isInstanceOf(PaymentException.class)
        .satisfies(
            ex ->
                assertThat(((PaymentException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT));
  }

  @Test
  @DisplayName("should reject a null target status with INVALID_INPUT")
  void shouldRejectNullTargetStatus() {
    Transaction transaction = savedTransaction(TransactionStatus.PENDING_APPROVAL);

    assertThatThrownBy(() -> transactionService.progressTransaction(transaction.getId(), null))
        .isInstanceOf(PaymentException.class)
        .satisfies(
            ex ->
                assertThat(((PaymentException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT));
    assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.PENDING_APPROVAL);
  }
}
