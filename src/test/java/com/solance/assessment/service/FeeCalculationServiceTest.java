package com.solance.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.solance.assessment.api.model.FeeQuoteResponse;
import com.solance.assessment.exception.ErrorCode;
import com.solance.assessment.exception.PaymentException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for FeeCalculationService.
 *
 * <p>The FxRateProvider is mocked so the tests do not depend on the static rate table.
 */
@ExtendWith(MockitoExtension.class)
class FeeCalculationServiceTest {

  @Mock private FxRateProvider fxRateProvider;

  private FeeCalculationService service;

  @BeforeEach
  void setUp() {
    service = new FeeCalculationService(fxRateProvider);
  }

  private static void assertPaymentException(Runnable call, ErrorCode expected) {
    assertThatThrownBy(call::run)
        .isInstanceOf(PaymentException.class)
        .satisfies(ex -> assertThat(((PaymentException) ex).getErrorCode()).isEqualTo(expected));
  }

  // ---------------------------------------------------------------------------
  // Domestic
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Domestic payments")
  class Domestic {

    @ParameterizedTest(name = "toCurrency = [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("should charge the flat fee when there is no target currency")
    void shouldChargeFlatFeeWithoutTargetCurrency(String toCurrency) {
      FeeQuoteResponse quote = service.calculateFee(10_000L, "EUR", toCurrency);

      assertThat(quote.getFeeAmount()).isEqualTo(300);
      assertThat(quote.getTotalAmount()).isEqualTo(10_300);
      verify(fxRateProvider, never()).getRate(anyString(), anyString());
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"EUR, EUR", "eur, EUR", "' gbp ', GBP"})
    @DisplayName("should charge the flat fee when source and target currency are the same")
    void shouldChargeFlatFeeForSameCurrency(String from, String to) {
      FeeQuoteResponse quote = service.calculateFee(10_000L, from, to);

      assertThat(quote.getFeeAmount()).isEqualTo(300);
      assertThat(quote.getTotalAmount()).isEqualTo(10_300);
      verify(fxRateProvider, never()).getRate(anyString(), anyString());
    }

    @Test
    @DisplayName("should leave all FX fields empty on a domestic quote")
    void shouldLeaveFxFieldsEmpty() {
      FeeQuoteResponse quote = service.calculateFee(10_000L, "EUR", null);

      assertThat(quote.getConvertedAmount()).isNull();
      assertThat(quote.getExchangeRate()).isNull();
      assertThat(quote.getToCurrency()).isNull();
      assertThat(quote.getFeePercentage()).isNull();
      assertThat(quote.getRateValidForSeconds()).isNull();
      assertThat(quote.getRateRefreshedAt()).isNull();
    }

    @Test
    @DisplayName("should charge the flat fee even when it exceeds the amount")
    void shouldChargeFlatFeeOnSmallAmount() {
      FeeQuoteResponse quote = service.calculateFee(1L, "EUR", null);

      assertThat(quote.getFeeAmount()).isEqualTo(300);
      assertThat(quote.getTotalAmount()).isEqualTo(301);
    }
  }

  // ---------------------------------------------------------------------------
  // FX
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("FX payments")
  class Fx {

    @Test
    @DisplayName("should return every FX field for a cross-currency quote")
    void shouldReturnFullFxQuote() {
      when(fxRateProvider.getRate("EUR", "GBP")).thenReturn(Optional.of(0.856));

      Instant before = Instant.now();
      FeeQuoteResponse quote = service.calculateFee(10_000L, "EUR", "GBP");
      Instant after = Instant.now();

      assertThat(quote.getFeeAmount()).isEqualTo(35); // 0.35% of 10,000
      assertThat(quote.getTotalAmount()).isEqualTo(10_035);
      assertThat(quote.getConvertedAmount()).isEqualTo(8_560);
      assertThat(quote.getExchangeRate()).isEqualTo(0.856);
      assertThat(quote.getToCurrency()).isEqualTo("GBP");
      assertThat(quote.getFeePercentage()).isEqualTo(0.35);
      assertThat(quote.getRateValidForSeconds()).isEqualTo(20);
      assertThat(quote.getRateRefreshedAt()).isBetween(before, after);
    }

    @Test
    @DisplayName("should normalise currency codes before looking up the rate")
    void shouldNormaliseCurrencyCodes() {
      when(fxRateProvider.getRate("EUR", "USD")).thenReturn(Optional.of(1.085));

      FeeQuoteResponse quote = service.calculateFee(10_000L, " eur", "usd ");

      assertThat(quote.getToCurrency()).isEqualTo("USD");
      assertThat(quote.getConvertedAmount()).isEqualTo(10_850);
    }

    @ParameterizedTest(name = "0.35% of {0} = {1}")
    @CsvSource({
      "1000, 4", // 3.5    -> 4 (half rounds up)
      "1428, 5", // 4.998  -> 5
      "1142, 4", // 3.997  -> 4
      "1000000, 3500", // exact
      "142, 0", // 0.497  -> 0
      "100, 0" // 0.35   -> 0
    })
    @DisplayName("should round the FX fee HALF_UP to whole minor units")
    void shouldRoundFeeHalfUp(long amount, long expectedFee) {
      when(fxRateProvider.getRate("EUR", "GBP")).thenReturn(Optional.of(0.856));

      FeeQuoteResponse quote = service.calculateFee(amount, "EUR", "GBP");

      assertThat(quote.getFeeAmount()).isEqualTo(expectedFee);
      assertThat(quote.getTotalAmount()).isEqualTo(amount + expectedFee);
    }

    @ParameterizedTest(name = "{0} x {1} = {2}")
    @CsvSource({
      "1001, 0.856, 857", // 856.856 -> 857
      "1000, 0.8565, 857", // 856.5   -> 857 (half rounds up)
      "1000, 0.8564, 856" // 856.4   -> 856
    })
    @DisplayName("should round the converted amount HALF_UP to whole minor units")
    void shouldRoundConvertedAmountHalfUp(long amount, double rate, long expected) {
      when(fxRateProvider.getRate("EUR", "GBP")).thenReturn(Optional.of(rate));

      FeeQuoteResponse quote = service.calculateFee(amount, "EUR", "GBP");

      assertThat(quote.getConvertedAmount()).isEqualTo(expected);
    }

    @Test
    @DisplayName("should reject with INVALID_CURRENCY when the provider has no rate")
    void shouldRejectWhenNoRate() {
      when(fxRateProvider.getRate("EUR", "GBP")).thenReturn(Optional.empty());

      assertPaymentException(
          () -> service.calculateFee(10_000L, "EUR", "GBP"), ErrorCode.INVALID_CURRENCY);
    }
  }

  // ---------------------------------------------------------------------------
  // Validation
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Validation failures")
  class Validation {

    @Test
    @DisplayName("should reject a missing amount with INVALID_AMOUNT and 'Amount is required'")
    void shouldRejectMissingAmount() {
      assertPaymentException(() -> service.calculateFee(null, "EUR", null), ErrorCode.INVALID_AMOUNT);
      assertThatThrownBy(() -> service.calculateFee(null, "EUR", null))
          .hasMessage("Amount is required");
    }

    @ParameterizedTest(name = "amount = {0}")
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    @DisplayName("should reject a zero or negative amount with INVALID_AMOUNT")
    void shouldRejectNonPositiveAmount(long amount) {
      assertPaymentException(
          () -> service.calculateFee(amount, "EUR", null), ErrorCode.INVALID_AMOUNT);
    }

    @ParameterizedTest(name = "fromCurrency = [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "JPY", "EURO"})
    @DisplayName("should reject a missing or unsupported source currency with INVALID_CURRENCY")
    void shouldRejectInvalidSourceCurrency(String fromCurrency) {
      assertPaymentException(
          () -> service.calculateFee(10_000L, fromCurrency, "GBP"), ErrorCode.INVALID_CURRENCY);
    }

    @Test
    @DisplayName("should reject an unsupported target currency without calling the provider")
    void shouldRejectUnsupportedTargetCurrency() {
      assertPaymentException(
          () -> service.calculateFee(10_000L, "EUR", "JPY"), ErrorCode.INVALID_CURRENCY);
      verify(fxRateProvider, never()).getRate(anyString(), anyString());
    }

    @Test
    @DisplayName("should reject a domestic amount whose total would overflow")
    void shouldRejectDomesticOverflow() {
      assertPaymentException(
          () -> service.calculateFee(Long.MAX_VALUE, "EUR", null), ErrorCode.INVALID_AMOUNT);
    }

    @Test
    @DisplayName("should reject an FX amount whose converted amount would overflow")
    void shouldRejectFxOverflow() {
      when(fxRateProvider.getRate("GBP", "USD")).thenReturn(Optional.of(1.267));

      assertPaymentException(
          () -> service.calculateFee(Long.MAX_VALUE - 1_000, "GBP", "USD"),
          ErrorCode.INVALID_AMOUNT);
    }
  }
}
