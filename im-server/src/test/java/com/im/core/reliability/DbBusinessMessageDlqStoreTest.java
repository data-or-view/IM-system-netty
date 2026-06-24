package com.im.core.reliability;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.im.core.db.entity.MessageSendFailureEntity;
import com.im.core.db.mapper.MessageSendFailureMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbBusinessMessageDlqStoreTest {

    @Test
    void claimOneOnlyUpdatesPendingRecordById() {
        RecordingMapper mapper = new RecordingMapper(1);

        boolean claimed = DbBusinessMessageDlqStore.claimOne(mapper.proxy(), 1001L, 123456L, 153456L);

        assertTrue(claimed);
        assertEquals(DbBusinessMessageDlqStore.STATUS_RETRYING, mapper.updatedEntity.getStatus());
        assertEquals(123456L, mapper.updatedEntity.getUpdatedAt());
        assertEquals(153456L, mapper.updatedEntity.getNextRetryAt());
        assertTrue(mapper.wrapper.getSqlSegment().contains("id"));
        assertTrue(mapper.wrapper.getSqlSegment().contains("status"));
        assertTrue(mapper.wrapper.getParamNameValuePairs().containsValue(1001L));
        assertTrue(mapper.wrapper.getParamNameValuePairs().containsValue(DbBusinessMessageDlqStore.STATUS_PENDING));
    }

    @Test
    void claimOneReturnsFalseWhenPendingConditionWasLostRace() {
        RecordingMapper mapper = new RecordingMapper(0);

        boolean claimed = DbBusinessMessageDlqStore.claimOne(mapper.proxy(), 1001L, 123456L, 153456L);

        assertTrue(!claimed);
    }

    @Test
    void dueClaimQueryIncludesExpiredRetryingAndExcludesTerminalStates() {
        DbBusinessMessageDlqStore.QuerySpec spec = DbBusinessMessageDlqStore.dueClaimQueryForTest(123456L, 10);

        String sql = spec.wrapper().getSqlSegment();
        assertTrue(sql.contains("status"));
        assertTrue(sql.contains("next_retry_at"));
        assertTrue(spec.wrapper().getParamNameValuePairs().containsValue(DbBusinessMessageDlqStore.STATUS_PENDING));
        assertTrue(spec.wrapper().getParamNameValuePairs().containsValue(DbBusinessMessageDlqStore.STATUS_RETRYING));
        assertTrue(!spec.wrapper().getParamNameValuePairs().containsValue(DbBusinessMessageDlqStore.STATUS_REPUBLISHED));
        assertTrue(!spec.wrapper().getParamNameValuePairs().containsValue(DbBusinessMessageDlqStore.STATUS_FAILED));
    }

    private static final class RecordingMapper {
        private final int updateResult;
        private MessageSendFailureEntity updatedEntity;
        private UpdateWrapper<MessageSendFailureEntity> wrapper;

        private RecordingMapper(int updateResult) {
            this.updateResult = updateResult;
        }

        private MessageSendFailureMapper proxy() {
            return (MessageSendFailureMapper) Proxy.newProxyInstance(
                    MessageSendFailureMapper.class.getClassLoader(),
                    new Class<?>[]{MessageSendFailureMapper.class},
                    (proxy, method, args) -> {
                        if ("update".equals(method.getName())) {
                            updatedEntity = (MessageSendFailureEntity) args[0];
                            wrapper = (UpdateWrapper<MessageSendFailureEntity>) args[1];
                            return updateResult;
                        }
                        if ("toString".equals(method.getName())) {
                            return "RecordingMapper";
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
