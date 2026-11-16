package com.solance.assessment.service;

import com.solance.assessment.api.model.FeeQuoteResponse;
import com.solance.assessment.domain.SupportedCurrency;
import com.solance.assessment.exception.ErrorCode;
import com.solance.assessment.exception.PaymentException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Calculates payment fees for domestic and cross-currency (FX) payments.
 *
 * <p>The fee rules are in the README. All amounts are whole minor units (e.g. cents). Fractional
 * results (the FX fee and the converted amount) are rounded HALF_UP to whole minor units.
 */
@Service
public class FeeCalculationService {

  static final long FLAT_FEE_MINOR_UNITS = 300L;
  static final double FX_FEE_PERCENTAGE = 0.35;
  static final int RATE_VALID_FOR_SECONDS = 20;

  private static final BigDecimal FX_FEE_FRACTION =
      BigDecimal.valueOf(FX_FEE_PERCENTAGE).movePointLeft(2);

  private final FxRateProvider fxRateProvider;

  public FeeCalculationService(FxRateProvider fxRateProvider) {
    this.fxRateProvider = fxRateProvider;
  }

  /**
   * Calculate the fee for a payment.
   *
   * @param amount payment amount in minor units
   * @param fromCurrency source currency code (e.g., "EUR")
   * @param toCurrency target currency code, null or blank for a domestic payment
   * @return the fee quote
   * @throws PaymentException for invalid input, with the matching {@link ErrorCode}
   */
  public FeeQuoteResponse calculateFee(Long amount, String fromCurrency, String toCurrency) {
    if (amount == null) {
      throw new PaymentException(ErrorCode.INVALID_AMOUNT, "Amount is required");
    }
    if (amount <= 0) {
      throw new PaymentException(ErrorCode.INVALID_AMOUNT, "Amount must be greater than zero");
    }
    SupportedCurrency from = parseCurrency(fromCurrency, "Source currency");
    SupportedCurrency to =
        toCurrency == null || toCurrency.isBlank()
            ? from
            : parseCurrency(toCurrency, "Target currency");

    try {
      return from == to ? domesticQuote(amount) : fxQuote(amount, from, to);
    } catch (ArithmeticException e) {
      throw new PaymentException(ErrorCode.INVALID_AMOUNT, "Amount is too large");
    }
  }

  private FeeQuoteResponse domesticQuote(long amount) {
    return FeeQuoteResponse.builder()
        .feeAmount(FLAT_FEE_MINOR_UNITS)
        .totalAmount(Math.addExact(amount, FLAT_FEE_MINOR_UNITS))
        .build();
  }

  private FeeQuoteResponse fxQuote(long amount, SupportedCurrency from, SupportedCurrency to) {
    double rate =
        fxRateProvider
            .getRate(from.name(), to.name())
            .orElseThrow(
                () ->
                    new PaymentException(
                        ErrorCode.INVALID_CURRENCY,
                        "No exchange rate available for " + from + " to " + to));

    BigDecimal source = BigDecimal.valueOf(amount);
    long fee = source.multiply(FX_FEE_FRACTION).setScale(0, RoundingMode.HALF_UP).longValueExact();
    long converted =
        source
            .multiply(BigDecimal.valueOf(rate))
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact();

    return FeeQuoteResponse.builder()
        .feeAmount(fee)
        .totalAmount(Math.addExact(amount, fee))
        .convertedAmount(converted)
        .exchangeRate(rate)
        .toCurrency(to.name())
        .feePercentage(FX_FEE_PERCENTAGE)
        .rateValidForSeconds(RATE_VALID_FOR_SECONDS)
        .rateRefreshedAt(Instant.now())
        .build();
  }

  private static SupportedCurrency parseCurrency(String code, String label) {
    if (code == null || code.isBlank()) {
      throw new PaymentException(ErrorCode.INVALID_CURRENCY, label + " is required");
    }
    try {
      return SupportedCurrency.valueOf(code.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new PaymentException(
          ErrorCode.INVALID_CURRENCY, label + " is not supported: " + code.trim());
    }
  }
}
