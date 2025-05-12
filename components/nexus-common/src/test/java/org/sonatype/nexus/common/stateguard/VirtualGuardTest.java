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
package org.sonatype.nexus.common.stateguard;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link VirtualGuard} implementation in {@link StateGuard}.
 * 
 * @since Java 21
 */
@EnabledOnJre(JRE.JAVA_21)
public class VirtualGuardTest
    extends TestSupport
{
  private static final String STATE_NEW = "NEW";
  private static final String STATE_STARTED = "STARTED";
  private static final String STATE_INVALID = "INVALID";
  
  private StateGuard underTest;

  @BeforeEach
  public void setUp() {
    underTest = new StateGuard.Builder()
        .initial(STATE_NEW)
        .create();

    assertThat(underTest.getCurrent(), is(STATE_NEW));
  }

  @Test
  public void testVirtualGuardSuccess() throws Exception {
    // Setup a latch to wait for the async operation
    CountDownLatch latch = new CountDownLatch(1);
    
    // Result holder
    final Object[] resultHolder = new Object[1];
    final Exception[] exceptionHolder = new Exception[1];
    
    // Execute an action in a virtual thread
    underTest.virtualGuard(STATE_NEW).runAsync(
        () -> {
          // Simulate some I/O work
          Thread.sleep(100);
          return "SUCCESS";
        },
        new VirtualActionCallback<String>() {
          @Override
          public void onSuccess(String result) {
            resultHolder[0] = result;
            latch.countDown();
          }

          @Override
          public void onFailure(Exception exception) {
            exceptionHolder[0] = exception;
            latch.countDown();
          }
        });
    
    // Wait for the async operation to complete
    assertTrue(latch.await(1, TimeUnit.SECONDS), "Async operation timed out");
    
    // Verify the result
    assertThat(resultHolder[0], is("SUCCESS"));
    assertThat(exceptionHolder[0], is(nullValue()));
  }

  @Test
  public void testVirtualGuardFailure() throws Exception {
    // Setup a latch to wait for the async operation
    CountDownLatch latch = new CountDownLatch(1);
    
    // Result holder
    final Object[] resultHolder = new Object[1];
    final Exception[] exceptionHolder = new Exception[1];
    
    // Execute an action in a virtual thread with an invalid state
    underTest.virtualGuard(STATE_STARTED).runAsync(
        () -> {
          // This should not be executed
          return "SUCCESS";
        },
        new VirtualActionCallback<String>() {
          @Override
          public void onSuccess(String result) {
            resultHolder[0] = result;
            latch.countDown();
          }

          @Override
          public void onFailure(Exception exception) {
            exceptionHolder[0] = exception;
            latch.countDown();
          }
        });
    
    // Wait for the async operation to complete
    assertTrue(latch.await(1, TimeUnit.SECONDS), "Async operation timed out");
    
    // Verify the exception
    assertThat(resultHolder[0], is(nullValue()));
    assertTrue(exceptionHolder[0] instanceof InvalidStateException, "Expected InvalidStateException");
  }

  @Test
  public void testVirtualGuardWithException() throws Exception {
    // Setup a latch to wait for the async operation
    CountDownLatch latch = new CountDownLatch(1);
    
    // Result holder
    final Object[] resultHolder = new Object[1];
    final Exception[] exceptionHolder = new Exception[1];
    
    // Execute an action in a virtual thread that throws an exception
    underTest.virtualGuard(STATE_NEW).runAsync(
        () -> {
          throw new RuntimeException("Test exception");
        },
        new VirtualActionCallback<Void>() {
          @Override
          public void onSuccess(Void result) {
            resultHolder[0] = result;
            latch.countDown();
          }

          @Override
          public void onFailure(Exception exception) {
            exceptionHolder[0] = exception;
            latch.countDown();
          }
        });
    
    // Wait for the async operation to complete
    assertTrue(latch.await(1, TimeUnit.SECONDS), "Async operation timed out");
    
    // Verify the exception
    assertThat(resultHolder[0], is(nullValue()));
    assertTrue(exceptionHolder[0] instanceof RuntimeException, "Expected RuntimeException");
    assertThat(exceptionHolder[0].getMessage(), is("Test exception"));
  }

  @Test
  public void testVirtualGuardWithStateChange() throws Exception {
    // Setup a latch to wait for the async operation
    CountDownLatch latch = new CountDownLatch(1);
    
    // Result holder
    final Object[] resultHolder = new Object[1];
    final Exception[] exceptionHolder = new Exception[1];
    
    // Change state after submitting the task but before it executes
    underTest.virtualGuard(STATE_NEW).runAsync(
        () -> {
          // Simulate some delay to allow state change
          Thread.sleep(200);
          return "SUCCESS";
        },
        new VirtualActionCallback<String>() {
          @Override
          public void onSuccess(String result) {
            resultHolder[0] = result;
            latch.countDown();
          }

          @Override
          public void onFailure(Exception exception) {
            exceptionHolder[0] = exception;
            latch.countDown();
          }
        });
    
    // Change state immediately after submitting
    Thread.sleep(50);
    underTest.transition(STATE_STARTED).from(STATE_NEW).run(new Action<Void>() {
      @Override
      public Void run() throws Exception {
        return null;
      }
    });
    
    // Wait for the async operation to complete
    assertTrue(latch.await(1, TimeUnit.SECONDS), "Async operation timed out");
    
    // Verify the exception (should fail because state changed)
    assertThat(resultHolder[0], is(nullValue()));
    assertTrue(exceptionHolder[0] instanceof InvalidStateException, "Expected InvalidStateException");
  }
}