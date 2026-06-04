package com.im.core.persistence;

import com.im.common.exception.DatabasePersistenceException;
import com.im.common.retry.RetryConfig;
import com.im.common.retry.RetryExecutor;
import com.im.core.db.MyBatisPlusFactory;
import com.im.core.friend.DbFriendManager;
import com.im.core.group.DbGroupManager;
import com.im.core.sync.DbIncrementalSync;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class DbManagerPersistenceExceptionTest {

    private final RetryExecutor directRetry = new RetryExecutor() {
        @Override
        public <T> T execute(RetryConfig config, java.util.concurrent.Callable<T> callable) {
            try {
                return callable.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    };

    @AfterEach
    void tearDown() {
        MyBatisPlusFactory.shutdown();
    }

    @Test
    void friendReadsWrapDatabaseFailures() {
        DbFriendManager manager = new DbFriendManager(directRetry, new DbIncrementalSync(directRetry));

        assertThrows(DatabasePersistenceException.class, () -> manager.getFriendList("alice"));
    }

    @Test
    void groupReadsWrapDatabaseFailures() {
        DbGroupManager manager = new DbGroupManager(directRetry, new DbIncrementalSync(directRetry));

        assertThrows(DatabasePersistenceException.class, () -> manager.getMemberList("group-1"));
    }

    @Test
    void groupSearchDoesNotHideDatabaseFailuresAsEmptyResult() {
        DbGroupManager manager = new DbGroupManager(directRetry, new DbIncrementalSync(directRetry));

        assertThrows(DatabasePersistenceException.class, () -> manager.searchGroups("dev", 20));
    }
}
