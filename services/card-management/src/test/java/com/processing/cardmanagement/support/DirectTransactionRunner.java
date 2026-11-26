package com.processing.cardmanagement.support;

import com.processing.cardmanagement.services.TransactionRunner;

import java.util.function.Function;
import java.util.function.Supplier;

/** Выполняет бизнес-операции в тестах без Spring и подключения к БД. */
public final class DirectTransactionRunner implements TransactionRunner {

    @Override
    public void run(Runnable operation) {
        operation.run();
    }

    @Override
    public <T> T runSupplier(Supplier<T> operation) {
        return operation.get();
    }

    @Override
    public <T> T runSupplierWithNestedQuery(Function<TransactionRunner, T> operation) {
        return operation.apply(this);
    }
}
