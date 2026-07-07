package com.im.api;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OperationContractTest {

    @Test
    void everyOperationHasExplicitContractClassification() {
        Set<Operation> contracted = OperationContract.all().stream()
                .map(OperationContract::operation)
                .collect(Collectors.toSet());

        assertEquals(EnumSet.allOf(Operation.class), contracted);
    }

    @Test
    void httpContractsRoundTripThroughOperationLookup() {
        OperationContract.all().stream()
                .filter(contract -> contract.transport() == TransportType.HTTP_ONLY || contract.transport() == TransportType.BOTH)
                .forEach(contract -> {
                    Operation operation = contract.operation();

                    assertNotNull(operation.httpMethod(), operation + " must declare http method");
                    assertNotNull(operation.httpPath(), operation + " must declare http path");
                    assertEquals(operation, Operation.fromHttp(operation.httpMethod(), operation.httpPath()));
                });
    }

    @Test
    void websocketContractsRoundTripThroughOperationLookup() {
        OperationContract.all().stream()
                .filter(contract -> contract.transport() == TransportType.WS_ONLY || contract.transport() == TransportType.BOTH)
                .forEach(contract -> assertEquals(contract.operation(), Operation.fromOpName(contract.operation().opName())));
    }

    @Test
    void contractCategoriesAreNotBlank() {
        OperationContract.all().forEach(contract -> {
            assertFalse(contract.category().isBlank(), contract.operation() + " category must be useful");
            assertFalse(contract.requestShape().isBlank(), contract.operation() + " request shape must be useful");
            assertFalse(contract.responseShape().isBlank(), contract.operation() + " response shape must be useful");
        });
    }

    @Test
    void contractRegistryDoesNotContainDuplicateOperations() {
        long distinct = OperationContract.all().stream().map(OperationContract::operation).distinct().count();

        assertEquals(OperationContract.all().size(), distinct);
        assertEquals(Arrays.stream(Operation.values()).count(), distinct);
    }
}
