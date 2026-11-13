package com.ondemandmonitoring.devicecheck.service;

import com.ondemandmonitoring.devicecheck.service.impl.PersistedPreDeviceCheckService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.TransactionDefinition;
import static org.junit.jupiter.api.Assertions.*;

class PreflightTransactionTest {
    @Test
    void selectedDroneStartOpensWritableTransactionForPessimisticLock() throws Exception {
        var method = IPersistedPreDeviceCheckService.class.getMethod("start", String.class, String.class);
        var transaction = new AnnotationTransactionAttributeSource()
                .getTransactionAttribute(method, PersistedPreDeviceCheckService.class);
        assertNotNull(transaction);
        assertFalse(transaction.isReadOnly());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRED, transaction.getPropagationBehavior());
    }
}
