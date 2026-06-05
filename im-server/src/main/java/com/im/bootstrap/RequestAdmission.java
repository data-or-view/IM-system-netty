package com.im.bootstrap;

import java.time.Duration;

public interface RequestAdmission {

    RequestScope enter();

    void open();

    void closeAndDrain(Duration timeout);

    boolean isOpen();
}
