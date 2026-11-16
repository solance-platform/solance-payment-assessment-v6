package com.solance.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solance.assessment.api.model.PaymentRequest;
import com.solance.assessment.api.model.PaymentResponse;
import com.solance.assessment.domain.PaymentInstrument;
import com.solance.assessment.domain.PaymentInstrumentStatus;
import com.solance.assessment.domain.PaymentInstrumentType;
import com.solance.assessment.domain.PaymentScheme;
import com.solance.assessment.domain.SupportedCurrency;
import com.solance.assessment.domain.Transaction;
import com.solance.assessment.exception.ErrorCode;
import com.solance.assessment.exception.PaymentException;
import com.solance.assessment.repository.PaymentInstrumentRepository;
import com.solance.assessment.repository.TransactionRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PaymentServiceTest {

  private static final UUID CLIENT_A =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID CLIENT_B =
      UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final UUID EUR_INSTRUMENT =
      UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID GBP_INSTRUMENT =
      UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID DISABLED_INSTRUMENT =
      UUID.fromString("33333333-3333-3333-3333-333333333333");

  private PaymentService paymentService;
  private TransactionRepository transactionRepository;

  @BeforeEach
  void setUp() {
    PaymentInstrumentRepository instrumentRepository = new PaymentInstrumentRepository();
    transactionRepository = new TransactionRepository();
    paymentService = new PaymentService(instrumentRepository, transactionRepository);
  }

  private PaymentRequest validEurRequest() {
    return PaymentRequest.builder()
        .paymentInstrumentId(EUR_INSTRUMENT)
        .recipientName("Jane Doe")
        .recipientIban("DE89370400440532013000")
        .amount(new BigDecimal("5000")) // 50.00 EUR — well within 1,000.00 balance
        .currency(SupportedCurrency.EUR)
        .reference("Invoice 123")
        .build();
  }

  private PaymentRequest validGbpRequest() {
    return PaymentRequest.builder()
        .paymentInstrumentId(GBP_INSTRUMENT)
        .recipientName("John Smith")
        .recipientIban("GB82WEST12345698765432")
        .amount(new BigDecimal("10000")) // 100.00 GBP — within 500.00 balance
        .currency(SupportedCurrency.GBP)
        .reference("Payment GBP-001")
        .build();
  }

  // ---------------------------------------------------------------------------
  // Successful payments
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Successful payments")
  class SuccessfulPayments {

    @Test
    @DisplayName("should submit EUR payment when amount is within balance")
    void shouldSubmitEurPaymentWithinBalance() {
      PaymentResponse response = paymentService.submitPayment(CLIENT_A, validEurRequest());

      assertThat(response).isNotNull();
      assertThat(response.getTransactionId()).isNotNull();
      assertThat(response.getStatus()).isEqualTo("PENDING_APPROVAL");
      assertThat(response.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("should submit GBP payment when amount is within balance")
    void shouldSubmitGbpPaymentWithinBalance() {
      PaymentResponse response = paymentService.submitPayment(CLIENT_A, validGbpRequest());

      assertThat(response).isNotNull();
      assertThat(response.getTransactionId()).isNotNull();
      assertThat(response.getStatus()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    @DisplayName("should create transaction with SEPA scheme for EUR payments")
    void shouldUseSepaForEurPayments() {
      PaymentResponse response = paymentService.submitPayment(CLIENT_A, validEurRequest());

      Transaction tx = transactionRepository.findById(response.getTransactionId()).orElseThrow();
      assertThat(tx.getPaymentScheme()).isEqualTo(PaymentScheme.SEPA);
    }

    @Test
    @DisplayName("should create transaction with SWIFT scheme for GBP payments")
    void shouldUseSwiftForGbpPayments() {
      PaymentResponse response = paymentService.submitPayment(CLIENT_A, validGbpRequest());

      Transaction tx = transactionRepository.findById(response.getTransactionId()).orElseThrow();
      assertThat(tx.getPaymentScheme()).isEqualTo(PaymentScheme.SWIFT);
    }

    @Test
    @DisplayName("should allow payment when amount exactly equals balance")
    void shouldAllowPaymentWhenAmountEqualsBalance() {
      PaymentRequest request = validEurRequest();
      request.setAmount(new BigDecimal("100000")); // exactly 1,000.00 = full balance

      PaymentResponse response = paymentService.submitPayment(CLIENT_A, request);
      assertThat(response.getTransactionId()).isNotNull();
    }

    @Test
    @DisplayName("should store transaction with all fields populated")
    void shouldStoreTransactionWithAllFields() {
      PaymentRequest request = validEurRequest();
      PaymentResponse response = paymentService.submitPayment(CLIENT_A, request);

      Transaction tx = transactionRepository.findById(response.getTransactionId()).orElseThrow();

      assertThat(tx.getClientId()).isEqualTo(CLIENT_A);
      assertThat(tx.getPaymentInstrumentId()).isEqualTo(EUR_INSTRUMENT);
      assertThat(tx.getAmount()).isEqualByComparingTo(request.getAmount());
      assertThat(tx.getCurrency()).isEqualTo(SupportedCurrency.EUR);
      assertThat(tx.getRecipientName()).isEqualTo("Jane Doe");
      assertThat(tx.getRecipientIban()).isEqualTo("DE89370400440532013000");
      assertThat(tx.getReference()).isEqualTo("Invoice 123");
    }
  }

  // ---------------------------------------------------------------------------
  // Validation failures
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Validation failures")
  class ValidationFailures {

    @Test
    @DisplayName("should reject payment when amount exceeds balance")
    void shouldRejectWhenAmountExceedsBalance() {
      PaymentRequest request = validEurRequest();
      request.setAmount(new BigDecimal("200000")); // 2,000.00 > 1,000.00 balance

      assertThatThrownBy(() -> paymentService.submitPayment(CLIENT_A, request))
          .isInstanceOf(PaymentException.class)
          .satisfies(
              ex ->
                  assertThat(((PaymentException) ex).getErrorCode())
                      .isEqualTo(ErrorCode.INSUFFICIENT_FUNDS));
    }

    @Test
    @DisplayName("should reject payment with zero amount")
    void shouldRejectZeroAmount() {
      PaymentRequest request = validEurRequest();
      request.setAmount(BigDecimal.ZERO);

      assertThatThrownBy(() -> paymentService.submitPayment(CLIENT_A, request))
          .isInstanceOf(PaymentException.class)
          .satisfies(
              ex ->
                  assertThat(((PaymentException) ex).getErrorCode())
                      .isEqualTo(ErrorCode.INVALID_AMOUNT));
    }

    @Test
    @DisplayName("should reject payment with negative amount")
    void shouldRejectNegativeAmount() {
      PaymentRequest request = validEurRequest();
      request.setAmount(new BigDecimal("-100"));

      assertThatThrownBy(() -> paymentService.submitPayment(CLIENT_A, request))
          .isInstanceOf(PaymentException.class)
          .satisfies(
              ex ->
                  assertThat(((PaymentException) ex).getErrorCode())
                      .isEqualTo(ErrorCode.INVALID_AMOUNT));
    }

    @Test
    @DisplayName("should reject payment with null amount")
    void shouldRejectNullAmount() {
      PaymentRequest request = validEurRequest();
      request.setAmount(null);

      assertThatThrownBy(() -> paymentService.submitPayment(CLIENT_A, request))
          .isInstanceOf(PaymentException.class)
          .satisfies(
              ex ->
                  assertThat(((PaymentException) ex).getErrorCode())
                      .isEqualTo(ErrorCode.INVALID_AMOUNT));
    }

    @Test
    @DisplayName("should reject payment for non-existent instrument")
    void shouldRejectNonExistentInstrument() {
      PaymentRequest request = validEurRequest();
      request.setPaymentInstrumentId(UUID.randomUUID());

      assertThatThrownBy(() -> paymentService.submitPayment(CLIENT_A, request))
          .isInstanceOf(PaymentException.class)
          .satisfies(
              ex ->
                  assertThat(((PaymentException) ex).getErrorCode())
                      .isEqualTo(ErrorCode.PAYMENT_INSTRUMENT_NOT_FOUND));
    }

    @Test
    @DisplayName("should reject payment when instrument belongs to another client")
    void shouldRejectWhenInstrumentNotOwned() {
      assertThatThrownBy(() -> paymentService.submitPayment(CLIENT_B, validEurRequest()))
          .isInstanceOf(PaymentException.class)
          .satisfies(
              ex ->
                  assertThat(((PaymentException) ex).getErrorCode())
                      .isEqualTo(ErrorCode.INSTRUMENT_NOT_OWNED));
    }

    @Test
    @DisplayName("should reject payment when instrument is disabled")
    void shouldRejectDisabledInstrument() {
      PaymentRequest request = validEurRequest();
      request.setPaymentInstrumentId(DISABLED_INSTRUMENT);

      assertThatThrownBy(() -> paymentService.submitPayment(CLIENT_A, request))
          .isInstanceOf(PaymentException.class)
          .satisfies(
              ex ->
                  assertThat(((PaymentException) ex).getErrorCode())
                      .isEqualTo(ErrorCode.INSTRUMENT_NOT_ACTIVE));
    }

    @Test
    @DisplayName("should reject payment with null currency")
    void shouldRejectNullCurrency() {
      PaymentRequest request = validEurRequest();
      request.setCurrency(null);

      assertThatThrownBy(() -> paymentService.submitPayment(CLIENT_A, request))
          .isInstanceOf(PaymentException.class)
          .satisfies(
              ex ->
                  assertThat(((PaymentException) ex).getErrorCode())
                      .isEqualTo(ErrorCode.INVALID_CURRENCY));
    }
  }

  // ---------------------------------------------------------------------------
  // README rules without a pre-existing test
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("README rules")
  class ReadmeRules {

    private static final UUID USD_INSTRUMENT =
        UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Test
    @DisplayName("should route USD payments via SWIFT (only EUR uses SEPA)")
    void shouldUseSwiftForUsdPayments() {
      PaymentInstrumentRepository instrumentRepository = new PaymentInstrumentRepository();
      instrumentRepository.save(
          PaymentInstrument.builder()
              .id(USD_INSTRUMENT)
              .clientId(CLIENT_A)
              .iban("DE89370400440532013000")
              .currency(SupportedCurrency.USD)
              .balance(new BigDecimal("100000"))
              .status(PaymentInstrumentStatus.ACTIVE)
              .type(PaymentInstrumentType.IBAN)
              .build());
      TransactionRepository txRepository = new TransactionRepository();
      PaymentService service = new PaymentService(instrumentRepository, txRepository);

      PaymentRequest request = validEurRequest();
      request.setPaymentInstrumentId(USD_INSTRUMENT);
      request.setCurrency(SupportedCurrency.USD);

      PaymentResponse response = service.submitPayment(CLIENT_A, request);

      Transaction tx = txRepository.findById(response.getTransactionId()).orElseThrow();
      assertThat(tx.getPaymentScheme()).isEqualTo(PaymentScheme.SWIFT);
    }

    @Test
    @DisplayName("should reject null payment instrument ID with INVALID_INPUT, not a 500")
    void shouldRejectNullInstrumentId() {
      PaymentRequest request = validEurRequest();
      request.setPaymentInstrumentId(null);

      assertThatThrownBy(() -> paymentService.submitPayment(CLIENT_A, request))
          .isInstanceOf(PaymentException.class)
          .satisfies(
              ex ->
                  assertThat(((PaymentException) ex).getErrorCode())
                      .isEqualTo(ErrorCode.INVALID_INPUT));
    }
  }
}
