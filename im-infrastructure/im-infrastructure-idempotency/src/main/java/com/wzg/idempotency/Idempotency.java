package com.wzg.idempotency;

import com.wzg.idempotency.config.IdempotencyConfig;
import com.wzg.idempotency.core.ExecutionContext;
import com.wzg.idempotency.core.IdempotencyHandler;
import com.wzg.idempotency.core.JsonConfig;
import com.wzg.idempotency.exception.IdempotencyConfigurationException;
import com.wzg.idempotency.persistence.BasePersistenceStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Idempotency provides both a configuration and a functional API for implementing idempotent workloads.
 * 
 * <p>This class is thread-safe. All operations delegate to the underlying persistence store
 * which handles concurrent access safely.</p>
 * 
 * <h2>Configuration</h2>
 * <p>Configure the persistence layer and idempotency settings before your handler executes (e.g. in constructor):</p>
 * <pre>{@code
 * Idempotency.config()
 *     .withPersistenceStore(persistenceStore)
 *     .withConfig(idempotencyConfig)
 *     .configure();
 * }</pre>
 * 
 * <h2>Functional API</h2>
 * <p>Make methods idempotent without AspectJ annotations. Generic return types are supported via Jackson TypeReference.</p>
 * 
 * <p><strong>Important:</strong> Always call {@link #registerExecutionContext(ExecutionContext)}
 * at the start of your handler to enable proper timeout handling.</p>
 * 
 * <p>Example usage with Function (single parameter):</p>
 * <pre>{@code
 * public Basket handleRequest(Product input) {
 *     Idempotency.registerExecutionContext(executionContext);
 *     return Idempotency.makeIdempotent(this::processProduct, input, Basket.class);
 * }
 * }</pre>
 * 
 * <p>Example usage with Supplier (multi-parameter methods):</p>
 * <pre>{@code
 * public String handleRequest(OrderRequest request) {
 *     Idempotency.registerExecutionContext(executionContext);
 *     return Idempotency.makeIdempotent(
 *         request.getOrderId(),
 *         () -> processPayment(request),
 *         String.class
 *     );
 * }
 * }</pre>
 */
public final class Idempotency {
    private static final String DEFAULT_FUNCTION_NAME = "function";

    private IdempotencyConfig config;
    
    private BasePersistenceStore persistenceStore;

    private Idempotency() {
    }

    public static Idempotency getInstance() {
        return Holder.instance;
    }

    /**
     * Register execution context for timeout handling.
     *
     * @param executionContext the execution context
     */
    public static void registerExecutionContext(ExecutionContext executionContext) {
        getInstance().getConfig().setExecutionContext(executionContext);
    }

    /**
     * Acts like a builder that can be used to configure Idempotency
     *
     * @return a new instance of Config
     */
    public static Config config() {
        return new Config();
    }

    public IdempotencyConfig getConfig() {
        return config;
    }

    private void setConfig(IdempotencyConfig config) {
        this.config = config;
    }

    public BasePersistenceStore getPersistenceStore() {
        if (persistenceStore == null) {
            throw new IllegalStateException("Persistence Store is null, did you call 'configure()'?");
        }
        return persistenceStore;
    }

    private void setPersistenceStore(BasePersistenceStore persistenceStore) {
        this.persistenceStore = persistenceStore;
    }

    private static final class Holder {
        private static final Idempotency instance = new Idempotency();
    }

    public static class Config {
        private IdempotencyConfig config;
        private BasePersistenceStore store;

        public void configure() {
            if (store == null) {
                throw new IllegalStateException(
                        "Persistence Layer is null, configure one with 'withPersistenceStore()'");
            }
            if (config == null) {
                config = IdempotencyConfig.builder().build();
            }
            Idempotency.getInstance().setConfig(config);
            Idempotency.getInstance().setPersistenceStore(store);
        }

        public Config withPersistenceStore(BasePersistenceStore persistenceStore) {
            this.store = persistenceStore;
            return this;
        }

        public Config withConfig(IdempotencyConfig config) {
            this.config = config;
            return this;
        }
    }

    // Functional API methods

    public static <T> T makeIdempotent(Object idempotencyKey, Supplier<T> function, Class<T> returnType) {
        return makeIdempotent(DEFAULT_FUNCTION_NAME, idempotencyKey, function, returnType);
    }

    public static <T> T makeIdempotent(String functionName, Object idempotencyKey, Supplier<T> function,
            Class<T> returnType) {
        return makeIdempotent(functionName, idempotencyKey, function, JsonConfig.toTypeReference(returnType));
    }

    public static <T, R> R makeIdempotent(Function<T, R> function, T arg, Class<R> returnType) {
        return makeIdempotent(DEFAULT_FUNCTION_NAME, arg, () -> function.apply(arg), returnType);
    }

    public static <T> T makeIdempotent(Object idempotencyKey, Supplier<T> function, TypeReference<T> typeRef) {
        return makeIdempotent(DEFAULT_FUNCTION_NAME, idempotencyKey, function, typeRef);
    }

    @SuppressWarnings("unchecked")
    public static <T> T makeIdempotent(String functionName, Object idempotencyKey, Supplier<T> function,
            TypeReference<T> typeRef) {
        try {
            JsonNode payload = JsonConfig.get().getObjectMapper().valueToTree(idempotencyKey);
            Idempotency instance = Idempotency.getInstance();
            ExecutionContext executionContext = instance.getConfig().getExecutionContext();
            BasePersistenceStore persistenceStore = instance.getPersistenceStore();
            IdempotencyConfig config = instance.getConfig();

            IdempotencyHandler handler = new IdempotencyHandler(
                    function::get,
                    typeRef,
                    functionName,
                    payload,
                    executionContext,
                    persistenceStore,
                    config);

            Object result = handler.handle();
            return (T) result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new IdempotencyConfigurationException("Idempotency operation failed: " + e.getMessage());
        }
    }

    public static <T, R> R makeIdempotent(Function<T, R> function, T arg, TypeReference<R> typeRef) {
        return makeIdempotent(DEFAULT_FUNCTION_NAME, arg, () -> function.apply(arg), typeRef);
    }
}
