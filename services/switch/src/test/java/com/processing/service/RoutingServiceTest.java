package com.processing.service;

import com.processing.config.SwitchProperties;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class RoutingServiceTest {

    private RoutingService routingService;

    @BeforeEach
    void setUp() {
        var properties = new SwitchProperties(
                "1.0.0", Map.of(
                        "400000", "ISS001", "400001", "ISS002", "400002", "ISS003",
                        "400003", "ISS004", "400004", "ISS005"),
                null, null, null, null, null, null);
        routingService = new RoutingService(properties);
    }

    @ParameterizedTest(name = "pan={0}, issuer={1}")
    @CsvSource({
            "4000003458730237, ISS001",
            "4000010000000000, ISS002",
            "4000020000000000, ISS003",
            "4000030000000000, ISS004",
            "4000040000000000, ISS005",
            "400000, ISS001"
    })
    void shouldFindIssuerWhenBinIsKnown(String pan, String expectedIssuer) {
        //Arrange
        String cardPan = pan;

        //Act
        String issuer = routingService.getIssuerIdByPan(cardPan);

        //Assert
        assertThat(issuer).isEqualTo(expectedIssuer);
    }

    @ParameterizedTest(name = "invalidPan={0}")
    @NullAndEmptySource
    @ValueSource(strings = {"4", "40000"})
    void shouldRejectWhenPanHasFewerThanSixCharacters(String pan) {
        //Arrange
        String invalidPan = pan;

        //Act
        Throwable failure = catchThrowable(() -> routingService.getIssuerIdByPan(invalidPan));

        //Assert
        assertThat(failure).isInstanceOf(UnknownBinException.class).hasMessage("Unknown BIN 'null-or-short'");
    }

    @ParameterizedTest(name = "unknownPan={0}")
    @ValueSource(strings = {"999999", "9999990000000000"})
    void shouldRejectWhenBinIsUnknown(String pan) {
        //Arrange
        String unknownPan = pan;

        //Act
        Throwable failure = catchThrowable(() -> routingService.getIssuerIdByPan(unknownPan));

        //Assert
        assertThat(failure).isInstanceOf(UnknownBinException.class).hasMessage("Unknown BIN '999999'");
    }
}
