package com.processing.gateway.validation;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class TransactionRequestValidatorTest {

    private final TransactionRequestValidator validator = new TransactionRequestValidator();

    @ParameterizedTest(name = "amount={0}")
    @ValueSource(strings = {"0.01", "1", "1000"})
    void shouldAcceptTransactionWhenAmountIsPositive(BigDecimal amount) {
        //Arrange
        AuthorizationRequest request = validRequestBuilder().amount(amount).build();

        //Act
        Throwable failure = catchThrowable(() -> validator.validate(request));

        //Assert
        assertThat(failure).isNull();
    }

    @Test
    void shouldAcceptRequestWhenIssuerAndTerminalTypeAreMissingBeforeRouting() {
        //Arrange
        AuthorizationRequest request = validRequestBuilder().issuerId(null).terminalType(null).build();

        //Act
        Throwable failure = catchThrowable(() -> validator.validate(request));

        //Assert
        assertThat(failure).isNull();
    }

    @Test
    void shouldRejectRequestWhenBodyIsMissing() {
        //Arrange
        AuthorizationRequest request = null;

        //Act
        Throwable failure = catchThrowable(() -> validator.validate(request));

        //Assert
        assertThat(failure).isInstanceOf(TransactionValidationException.class).hasMessage("Request body is required");
    }

    @ParameterizedTest(name = "{index}: missing {0}")
    @MethodSource("requestsWithMissingRequiredFields")
    void shouldRejectRequestWhenRequiredFieldIsMissingOrBlank(String field, AuthorizationRequest request) {
        //Arrange
        String expectedMessage = "Field '" + field + "' is required";

        //Act
        Throwable failure = catchThrowable(() -> validator.validate(request));

        //Assert
        assertThat(failure).isInstanceOf(TransactionValidationException.class).hasMessage(expectedMessage);
    }

    @Test
    void shouldRejectRequestWhenTransactionTimeIsMissing() {
        //Arrange
        AuthorizationRequest request = validRequestBuilder().transmissionDateTime(null).build();

        //Act
        Throwable failure = catchThrowable(() -> validator.validate(request));

        //Assert
        assertThat(failure).isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'transmissionDateTime' is required");
    }

    @ParameterizedTest(name = "mti={0}")
    @ValueSource(strings = {"0200", "0400", "9999"})
    void shouldRejectRequestWhenMtiIsUnsupported(String mti) {
        //Arrange
        AuthorizationRequest request = validRequestBuilder().mti(mti).build();

        //Act
        Throwable failure = catchThrowable(() -> validator.validate(request));

        //Assert
        assertThat(failure).isInstanceOf(TransactionValidationException.class).hasMessage("Field 'mti' must be '0100'");
    }

    @ParameterizedTest(name = "pan={0}")
    @ValueSource(strings = {"400000345873023", "40000034587302370", "400000345873023A"})
    void shouldRejectRequestWhenPanIsNotExactlySixteenDigits(String pan) {
        //Arrange
        AuthorizationRequest request = validRequestBuilder().pan(pan).build();

        //Act
        Throwable failure = catchThrowable(() -> validator.validate(request));

        //Assert
        assertThat(failure).isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'pan' must be exactly 16 digits");
    }

    @Test
    void shouldRejectRequestWhenAmountIsMissing() {
        //Arrange
        AuthorizationRequest request = validRequestBuilder().amount(null).build();

        //Act
        Throwable failure = catchThrowable(() -> validator.validate(request));

        //Assert
        assertThat(failure).isInstanceOf(TransactionValidationException.class).hasMessage("Field 'amount' is required");
    }

    @ParameterizedTest(name = "amount={0}")
    @ValueSource(strings = {"0", "0.00", "-0.01", "-1"})
    void shouldRejectRequestWhenAmountIsNotPositive(BigDecimal amount) {
        //Arrange
        AuthorizationRequest request = validRequestBuilder().amount(amount).build();

        //Act
        Throwable failure = catchThrowable(() -> validator.validate(request));

        //Assert
        assertThat(failure).isInstanceOf(TransactionValidationException.class).hasMessage("Field 'amount' must be > 0");
    }

    @ParameterizedTest(name = "currency={0}")
    @ValueSource(strings = {"64", "6430"})
    void shouldRejectRequestWhenCurrencyCodeLengthIsInvalid(String currencyCode) {
        //Arrange
        AuthorizationRequest request = validRequestBuilder().currencyCode(currencyCode).build();

        //Act
        Throwable failure = catchThrowable(() -> validator.validate(request));

        //Assert
        assertThat(failure).isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'currencyCode' must be exactly 3 characters");
    }

    @ParameterizedTest(name = "mcc={0}")
    @ValueSource(strings = {"123", "12345", "12A4"})
    void shouldRejectRequestWhenMccIsNotExactlyFourDigits(String mcc) {
        //Arrange
        AuthorizationRequest request = validRequestBuilder().mcc(mcc).build();

        //Act
        Throwable failure = catchThrowable(() -> validator.validate(request));

        //Assert
        assertThat(failure).isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'mcc' must be exactly 4 digits");
    }

    private static Stream<Arguments> requestsWithMissingRequiredFields() {
        return Stream.of(null, "", " ", "\t").flatMap(value -> Stream.of(
                Arguments.of("mti", validRequestBuilder().mti(value).build()),
                Arguments.of("stan", validRequestBuilder().stan(value).build()),
                Arguments.of("pan", validRequestBuilder().pan(value).build()),
                Arguments.of("processingCode", validRequestBuilder().processingCode(value).build()),
                Arguments.of("currencyCode", validRequestBuilder().currencyCode(value).build()),
                Arguments.of("terminalId", validRequestBuilder().terminalId(value).build()),
                Arguments.of("merchantId", validRequestBuilder().merchantId(value).build()),
                Arguments.of("mcc", validRequestBuilder().mcc(value).build()),
                Arguments.of("acquirerId", validRequestBuilder().acquirerId(value).build())));
    }

    private static AuthorizationRequest.AuthorizationRequestBuilder validRequestBuilder() {
        return AuthorizationRequest.builder()
                .mti("0100").stan("000001").pan("4000003458730237").processingCode("000000")
                .amount(new BigDecimal("1000")).currencyCode("643")
                .transmissionDateTime(Instant.parse("2026-01-31T23:59:59Z"))
                .terminalId("TERM0001").terminalType("POS").merchantId("MERCH0000000002")
                .mcc("5045").acquirerId("ACQ002");
    }
}
