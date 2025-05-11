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
package org.sonatype.nexus.common.thread;

import java.security.SecureClassLoader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Tests for {@link TcclBlock}.
 *
 * @since 3.0
 */
@DisplayName("TcclBlock Thread Context ClassLoader Tests")
public class TcclBlockTest
    extends TestSupport
{
  @Test
  @DisplayName("Begin and restore ClassLoader in platform thread")
  public void testBeginAndRestoreClassLoader() {
    Thread thread = Thread.currentThread();
    ClassLoader original = thread.getContextClassLoader();
    ClassLoader classLoader = new SecureClassLoader(getClass().getClassLoader())
    {
    };

    try (TcclBlock tccl = TcclBlock.begin(classLoader)) {
      assertThat(thread.getContextClassLoader(), is(classLoader));
    }
    assertThat(thread.getContextClassLoader(), is(original));
  }
  
  @Test
  @DisplayName("Begin and restore ClassLoader in virtual thread")
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  public void testBeginAndRestoreClassLoaderInVirtualThread() throws Exception {
    // Skip test if running on JDK < 21 which doesn't support virtual threads
    try {
      Thread.class.getMethod("startVirtualThread", Runnable.class);
    } 
    catch (NoSuchMethodException e) {
      // Skip test on JDK < 21
      return;
    }
    
    // Create a custom class loader for testing
    ClassLoader testClassLoader = new SecureClassLoader(getClass().getClassLoader()) {};
    
    // Use CountDownLatch to coordinate test execution
    CountDownLatch latch = new CountDownLatch(1);
    
    // Create a thread factory that uses virtual threads
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Reference holders for values from the virtual thread
    final ClassLoader[] originalInVirtual = new ClassLoader[1];
    final ClassLoader[] duringBlockInVirtual = new ClassLoader[1];
    final ClassLoader[] afterBlockInVirtual = new ClassLoader[1];
    
    // Execute test in a virtual thread
    executor.submit(() -> {
      try {
        // Capture the original class loader in the virtual thread
        originalInVirtual[0] = Thread.currentThread().getContextClassLoader();
        assertThat("Original ClassLoader in virtual thread", originalInVirtual[0], is(notNullValue()));
        
        // Set and verify the test class loader using TcclBlock
        try (TcclBlock tccl = TcclBlock.begin(testClassLoader)) {
          duringBlockInVirtual[0] = Thread.currentThread().getContextClassLoader();
          assertThat("ClassLoader during block in virtual thread", 
              duringBlockInVirtual[0], is(sameInstance(testClassLoader)));
        }
        
        // Verify the class loader is restored after the block
        afterBlockInVirtual[0] = Thread.currentThread().getContextClassLoader();
        assertThat("ClassLoader after block in virtual thread", 
            afterBlockInVirtual[0], is(sameInstance(originalInVirtual[0])));
      }
      finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await();
    executor.shutdown();
    
    // Verify the results from the virtual thread
    assertThat("Original ClassLoader was captured", originalInVirtual[0], is(notNullValue()));
    assertThat("ClassLoader during block was set correctly", duringBlockInVirtual[0], is(testClassLoader));
    assertThat("ClassLoader after block was restored correctly", 
        afterBlockInVirtual[0], is(sameInstance(originalInVirtual[0])));
  }
  
  @Test
  @DisplayName("Begin with Class parameter")
  public void testBeginWithClass() {
    Thread thread = Thread.currentThread();
    ClassLoader original = thread.getContextClassLoader();
    
    try (TcclBlock tccl = TcclBlock.begin(String.class)) {
      assertThat(thread.getContextClassLoader(), is(String.class.getClassLoader()));
    }
    assertThat(thread.getContextClassLoader(), is(original));
  }
  
  @Test
  @DisplayName("Begin with Object parameter")
  public void testBeginWithObject() {
    Thread thread = Thread.currentThread();
    ClassLoader original = thread.getContextClassLoader();
    String testObject = "test";
    
    try (TcclBlock tccl = TcclBlock.begin(testObject)) {
      assertThat(thread.getContextClassLoader(), is(testObject.getClass().getClassLoader()));
    }
    assertThat(thread.getContextClassLoader(), is(original));
  }
}