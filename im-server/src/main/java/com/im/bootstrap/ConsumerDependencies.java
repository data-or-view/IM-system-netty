package com.im.bootstrap;

import com.im.core.delivery.DeliveryConsumer;
import com.im.core.delivery.PersistenceConsumer;
import com.im.core.reliability.BusinessMessageDlqCompensator;

record ConsumerDependencies(PersistenceConsumer persistenceConsumer,
                            DeliveryConsumer deliveryConsumer,
                            BusinessMessageDlqCompensator businessMessageDlqCompensator) {
}
