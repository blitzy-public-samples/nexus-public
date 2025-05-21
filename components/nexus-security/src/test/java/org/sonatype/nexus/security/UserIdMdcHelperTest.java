/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.security;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;

import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.security.UserIdHelper.UNKNOWN;
import static org.sonatype.nexus.security.UserIdMdcHelper.KEY;

/**
 * Tests for {@link UserIdMdcHelper}.
 */
@ExtendWith(MockitoExtension.class)
class UserIdMdcHelperTest
  extends TestSupport
{
  private void reset() {
    MDC.remove(KEY);
    ThreadContext.unbindSubject();
    ThreadContext.unbindSecurityManager();
  }

  @BeforeEach
  void setUp() {
    reset();
  }

  @AfterEach
  void tearDown() {
    reset();
  }

  private Subject subject(final Object principal) {
    Subject subject = mock(Subject.class);
    when(subject.getPrincipal()).thenReturn(principal);
    return subject;
  }

  @Test
  void isSet() {
    MDC.put(KEY, "test");
    assertThat(UserIdMdcHelper.isSet(), is(true));
  }

  @Test
  void isSet_withNull() {
    String value = MDC.get(KEY);
    assertThat(value, nullValue());
    assertThat(UserIdMdcHelper.isSet(), is(false));
  }

  @Test
  void isSet_withBlank() {
    MDC.put(KEY, "");
    assertThat(UserIdMdcHelper.isSet(), is(false));
  }

  @Test
  void isSet_withUnknown() {
    MDC.put(KEY, UNKNOWN);
    assertThat(UserIdMdcHelper.isSet(), is(false));
  }

  @Test
  void set_withNull() {
    assertThrows(NullPointerException.class, () -> UserIdMdcHelper.set(null));
  }

  @Test
  void set_withSubject() {
    UserIdMdcHelper.set(subject("test"));

    assertThat(UserIdMdcHelper.isSet(), is(true));
    assertThat(MDC.get(KEY), is("test"));
  }

  @Test
  void set_notSet() {
    ThreadContext.bind(subject("test"));

    UserIdMdcHelper.set();

    assertThat(UserIdMdcHelper.isSet(), is(true));
    assertThat(MDC.get(KEY), is("test"));
  }

  @Test
  void set_notSet_withoutSubject() {
    ThreadContext.bind(mock(SecurityManager.class));

    UserIdMdcHelper.set();

    assertThat(UserIdMdcHelper.isSet(), is(false));
    assertThat(MDC.get(KEY), is(UNKNOWN));
  }

  @Test
  void set_alreadySet() {
    MDC.put(KEY, "foo");

    ThreadContext.bind(subject("test"));

    UserIdMdcHelper.set();

    assertThat(UserIdMdcHelper.isSet(), is(true));
    assertThat(MDC.get(KEY), is("test"));
  }

  @Test
  void setIfNeeded_notSet() {
    ThreadContext.bind(subject("test"));

    UserIdMdcHelper.setIfNeeded();

    assertThat(UserIdMdcHelper.isSet(), is(true));
    assertThat(MDC.get(KEY), is("test"));
  }

  @Test
  void setIfNeeded_alreadySet() {
    MDC.put(KEY, "foo");

    ThreadContext.bind(subject("test"));

    UserIdMdcHelper.setIfNeeded();

    assertThat(UserIdMdcHelper.isSet(), is(true));
    assertThat(MDC.get(KEY), is("foo"));
  }
  
  @Test
  void virtualThread_propagatesMdcContext() throws Exception {
    // Set up MDC in the main thread
    MDC.put(KEY, "virtual-test");
    
    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    
    // Store the MDC value from the virtual thread
    AtomicReference<String> virtualThreadMdcValue = new AtomicReference<>();
    
    // Create and start a virtual thread
    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        // Get the MDC value in the virtual thread
        virtualThreadMdcValue.set(MDC.get(KEY));
      } finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await();
    
    // Verify that the MDC context was propagated to the virtual thread
    assertThat(virtualThreadMdcValue.get(), is("virtual-test"));
  }
  
  @Test
  void virtualThread_withSuspension_preservesMdcContext() throws Exception {
    // Set up MDC in the main thread
    MDC.put(KEY, "suspension-test");
    
    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    
    // Store the MDC values from the virtual thread before and after suspension
    AtomicReference<String> beforeSuspensionValue = new AtomicReference<>();
    AtomicReference<String> afterSuspensionValue = new AtomicReference<>();
    
    // Create and start a virtual thread that will be suspended
    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        // Get the MDC value before suspension
        beforeSuspensionValue.set(MDC.get(KEY));
        
        // Perform a blocking operation that will cause the virtual thread to be suspended
        Thread.sleep(Duration.ofMillis(100));
        
        // Get the MDC value after suspension and resumption
        afterSuspensionValue.set(MDC.get(KEY));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await();
    
    // Verify that the MDC context was preserved across suspension and resumption
    assertThat(beforeSuspensionValue.get(), is("suspension-test"));
    assertThat(afterSuspensionValue.get(), is("suspension-test"));
  }
}