package com.solance.assessment.service;

import com.solance.assessment.api.model.PaymentRequest;
import com.solance.assessment.api.model.PaymentResponse;
import com.solance.assessment.domain.PaymentInstrument;
import com.solance.assessment.domain.PaymentInstrumentStatus;
import com.solance.assessment.domain.PaymentInstrumentType;
import com.solance.assessment.domain.PaymentScheme;
import com.solance.assessment.domain.SupportedCurrency;
import com.solance.assessment.domain.Transaction;
import com.solance.assessment.domain.TransactionStatus;
import com.solance.assessment.exception.ErrorCode;
import com.solance.assessment.exception.PaymentException;
import com.solance.assessment.repository.PaymentInstrumentRepository;
import com.solance.assessment.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

  private final PaymentInstrumentRepository instrumentRepository;
  private final TransactionRepository transactionRepository;

  public PaymentService(
      PaymentInstrumentRepository instrumentRepository,
      TransactionRepository transactionRepository) {
    this.instrumentRepository = instrumentRepository;
    this.transactionRepository = transactionRepository;
  }

  public PaymentResponse submitPayment(UUID clientId, PaymentRequest request) {
    if (request.getPaymentInstrumentId() == null) {
      throw new PaymentException(ErrorCode.INVALID_INPUT, "Payment instrument ID is required");
    }

    PaymentInstrument instrument =
        instrumentRepository
            .findById(request.getPaymentInstrumentId())
            .orElseThrow(
                () ->
                    new PaymentException(
                        ErrorCode.PAYMENT_INSTRUMENT_NOT_FOUND,
                        "Payment instrument not found: " + request.getPaymentInstrumentId()));

    validateOwnership(instrument, clientId);
    validateAmount(request.getAmount(), instrument.getBalance());

    Transaction transaction =
        Transaction.builder()
            .id(UUID.randomUUID())
            .clientId(clientId)
            .paymentInstrumentId(instrument.getId())
            .amount(request.getAmount())
            .currency(request.getCurrency())
            .status(TransactionStatus.PENDING_APPROVAL)
            .paymentScheme(resolvePaymentScheme(request.getCurrency()))
            .recipientName(request.getRecipientName())
            .recipientIban(request.getRecipientIban())
            .reference(request.getReference())
            .createdAt(Instant.now())
            .build();

    transactionRepository.save(transaction);

    return PaymentResponse.builder()
        .transactionId(transaction.getId())
        .status(transaction.getStatus().name())
        .createdAt(transaction.getCreatedAt())
        .build();
  }

  private void validateOwnership(PaymentInstrument instrument, UUID clientId) {
    if (!instrument.getClientId().equals(clientId)) {
      throw new PaymentException(
          ErrorCode.INSTRUMENT_NOT_OWNED, "Payment instrument does not belong to client");
    }
    if (instrument.getStatus() != PaymentInstrumentStatus.ACTIVE) {
      throw new PaymentException(
          ErrorCode.INSTRUMENT_NOT_ACTIVE,
          "Payment instrument is not active, current status: " + instrument.getStatus());
    }
    if (instrument.getType() != PaymentInstrumentType.IBAN) {
      throw new PaymentException(
          ErrorCode.INVALID_INPUT, "Only IBAN payment instruments are supported for payments");
    }
  }

  private void validateAmount(BigDecimal amount, BigDecimal balance) {
    if (amount == null || amount.signum() <= 0) {
      throw new PaymentException(ErrorCode.INVALID_AMOUNT, "Amount must be greater than zero");
    }
    if (amount.compareTo(balance) > 0) {
      throw new PaymentException(
          ErrorCode.INSUFFICIENT_FUNDS,
          "Amount " + amount + " exceeds available balance " + balance);
    }
  }

  private PaymentScheme resolvePaymentScheme(SupportedCurrency currency) {
    if (currency == null) {
      throw new PaymentException(ErrorCode.INVALID_CURRENCY, "Currency is required");
    }
    return currency == SupportedCurrency.EUR ? PaymentScheme.SEPA : PaymentScheme.SWIFT;
  }
}
