package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationRequestNormalizerTest {

    @ParameterizedTest(name = "terminal={0}")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void shouldPreserveTerminalIdWhenItIsMissingOrBlank(String terminalId) {
        //Arrange
        String original = terminalId;

        //Act
        String normalized = AuthorizationRequestNormalizer.normalizeTerminalId(original);

        //Assert
        assertThat(normalized).isEqualTo(original);
    }

    @ParameterizedTest(name = "terminal={0} -> {1}")
    @CsvSource({
            "T, T0000000",
            "TERM001, TERM0010",
            "TERM0001, TERM0001",
            "TERM00012, TERM0001"
    })
    void shouldNormalizeTerminalIdToEightCharactersWhenValueIsPresent(String terminalId, String expected) {
        //Arrange
        String original = terminalId;

        //Act
        String normalized = AuthorizationRequestNormalizer.normalizeTerminalId(original);

        //Assert
        assertThat(normalized).isEqualTo(expected);
    }

    @ParameterizedTest(name = "merchant={0}")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void shouldPreserveMerchantIdWhenItIsMissingOrBlank(String merchantId) {
        //Arrange
        String original = merchantId;

        //Act
        String normalized = AuthorizationRequestNormalizer.normalizeMerchantId(original);

        //Assert
        assertThat(normalized).isEqualTo(original);
    }

    @ParameterizedTest(name = "merchant={0} -> {1}")
    @CsvSource({
            "SHOP, SHOP00000000000",
            "MERCH000000001, MERCH0000000010",
            "MERCH0000000001, MERCH0000000001",
            "MERCH00000000001, MERCH0000000001",
            "MERCH10000000001, MERCH1000000000",
            "OTHER00000000001, OTHER0000000000",
            "MERCH000000000012, MERCH0000000000"
    })
    void shouldNormalizeMerchantIdToFifteenCharactersWhenValueIsPresent(String merchantId, String expected) {
        //Arrange
        String original = merchantId;

        //Act
        String normalized = AuthorizationRequestNormalizer.normalizeMerchantId(original);

        //Assert
        assertThat(normalized).isEqualTo(expected);
    }

    @Test
    void shouldPreserveTransactionFieldsWhenNormalizingRequest() {
        //Arrange
        AuthorizationRequest original = requestWithIdentifiers("TERM1", "MERCH00000000001");
        AuthorizationRequest expected = requestWithIdentifiers("TERM1000", "MERCH0000000001");

        //Act
        AuthorizationRequest normalized = AuthorizationRequestNormalizer.normalize(original);

        //Assert
        assertThat(normalized).isEqualTo(expected).isNotSameAs(original);
        assertThat(original.terminalId()).isEqualTo("TERM1");
        assertThat(original.merchantId()).isEqualTo("MERCH00000000001");
    }

    private AuthorizationRequest requestWithIdentifiers(String terminalId, String merchantId) {
        return new AuthorizationRequest(
                "0100", "000001", "4000003458730237", "000000", new BigDecimal("1000"), "643",
                Instant.parse("2026-01-31T23:59:59Z"), terminalId, "POS", merchantId, "5045", "ACQ002", "ISS001");
    }
}
