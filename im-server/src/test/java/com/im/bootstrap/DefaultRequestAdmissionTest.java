package com.im.bootstrap;

import com.im.common.exception.InfrastructureException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultRequestAdmissionTest {

    @Test
    void rejectsRequestsBeforeOpenAndAfterClose() {
        DefaultRequestAdmission admission = new DefaultRequestAdmission();

        assertThrows(InfrastructureException.class, admission::enter);

        admission.open();
        admission.enter().close();

        admission.closeAndDrain(Duration.ZERO);
        assertThrows(InfrastructureException.class, admission::enter);
    }

    @Test
    void closeWaitsForEnteredRequestsToFinish() throws Exception {
        DefaultRequestAdmission admission = new DefaultRequestAdmission();
        admission.open();
        RequestScope scope = admission.enter();
        AtomicBoolean closeReturned = new AtomicBoolean(false);
        CountDownLatch closeStarted = new CountDownLatch(1);

        Thread closer = Thread.ofVirtual().start(() -> {
            closeStarted.countDown();
            admission.closeAndDrain(Duration.ofSeconds(1));
            closeReturned.set(true);
        });

        assertTrue(closeStarted.await(1, TimeUnit.SECONDS));
        Thread.sleep(50);
        assertFalse(closeReturned.get());

        scope.close();
        closer.join();
        assertTrue(closeReturned.get());
    }
}
