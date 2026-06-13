package com.wzg.idempotency.core;

import java.util.OptionalInt;

/**
 * Execution context abstraction for idempotency operations.
 * Implementations can expose runtime timeout information and a stable service name.
 */
public interface ExecutionContext {
    
    /**
     * Get the remaining execution time in milliseconds.
     * Used for timeout handling in idempotency operations.
     * 
     * @return OptionalInt containing remaining time, or empty if not available
     */
    OptionalInt getRemainingTimeInMillis();
    
    /**
     * Get the function/service name.
     * Used for namespacing idempotency keys.
     * 
     * @return function/service name
     */
    String getFunctionName();
}
