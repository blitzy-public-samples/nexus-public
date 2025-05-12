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
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for {@link TcclBlock}.
 * 
 * Validates thread context ClassLoader management for both platform threads and Java 21 virtual threads.
 * These tests ensure that the TcclBlock utility correctly manages thread context ClassLoader state
 * in both traditional platform threads and the new lightweight virtual threads introduced in Java 21.
 */
public class TcclBlockTest
    extends TestSupport
{
  /**
   * Verifies that TcclBlock correctly sets and restores the thread context ClassLoader
   * for a platform thread.
   */
  @Test
  @DisplayName("TcclBlock should set and restore ClassLoader in platform thread")
  public void beginAndRestoreClassLoader() {
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
  
  /**
   * Verifies that TcclBlock correctly sets and restores the thread context ClassLoader
   * for a Java 21 virtual thread.
   */
  @Test
  @DisplayName("TcclBlock should set and restore ClassLoader in Java 21 virtual thread")
  @EnabledForJreRange(min = JRE.JAVA_21)
  public void beginAndRestoreClassLoaderWithVirtualThread() throws Exception {
    // Create a custom class loader for testing
    ClassLoader testClassLoader = new SecureClassLoader(getClass().getClassLoader()) {};
    
    // Use a latch to coordinate with the virtual thread
    CountDownLatch latch = new CountDownLatch(1);
    
    // Store results from the virtual thread
    final ClassLoader[] virtualThreadClassLoaders = new ClassLoader[2]; // [0] = during block, [1] = after block
    
    // Create and start a virtual thread
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      // Get the original class loader
      ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
      
      // Use TcclBlock to set and restore the class loader
      try (TcclBlock tccl = TcclBlock.begin(testClassLoader)) {
        // Store the class loader during the block
        virtualThreadClassLoaders[0] = Thread.currentThread().getContextClassLoader();
      }
      
      // Store the class loader after the block
      virtualThreadClassLoaders[1] = Thread.currentThread().getContextClassLoader();
      
      // Signal completion
      latch.countDown();
    });
    
    // Wait for the virtual thread to complete
    boolean completed = latch.await(5, TimeUnit.SECONDS);
    assertThat("Virtual thread did not complete in time", completed, is(true));
    
    // Verify the class loaders were correctly set and restored
    assertThat("Virtual thread should not be null", virtualThread, notNullValue());
    assertThat("Class loader not correctly set in virtual thread", 
        virtualThreadClassLoaders[0], is(testClassLoader));
    assertThat("Class loader not correctly restored in virtual thread", 
        virtualThreadClassLoaders[1], is(virtualThread.getContextClassLoader()));
  }
  
  /**
   * Verifies that TcclBlock correctly handles multiple nested blocks in a virtual thread.
   * This test ensures that when multiple TcclBlock instances are nested, each one properly
   * restores the previous context ClassLoader when closed.
   */
  @Test
  @DisplayName("TcclBlock should handle nested blocks correctly in virtual thread")
  @EnabledForJreRange(min = JRE.JAVA_21)
  public void nestedTcclBlocksInVirtualThread() throws Exception {
    // Create custom class loaders for testing
    ClassLoader outerClassLoader = new SecureClassLoader(getClass().getClassLoader()) {};
    ClassLoader innerClassLoader = new SecureClassLoader(getClass().getClassLoader()) {};
    
    // Use a latch to coordinate with the virtual thread
    CountDownLatch latch = new CountDownLatch(1);
    
    // Store results from the virtual thread
    final ClassLoader[] virtualThreadClassLoaders = new ClassLoader[4]; // [0] = original, [1] = outer block, [2] = inner block, [3] = after all blocks
    
    // Create and start a virtual thread
    Thread.ofVirtual().start(() -> {
      // Store the original class loader
      virtualThreadClassLoaders[0] = Thread.currentThread().getContextClassLoader();
      
      // Create nested TcclBlocks
      try (TcclBlock outerBlock = TcclBlock.begin(outerClassLoader)) {
        // Store the class loader during the outer block
        virtualThreadClassLoaders[1] = Thread.currentThread().getContextClassLoader();
        
        // Create an inner block
        try (TcclBlock innerBlock = TcclBlock.begin(innerClassLoader)) {
          // Store the class loader during the inner block
          virtualThreadClassLoaders[2] = Thread.currentThread().getContextClassLoader();
        }
        
        // Verify the inner block restored the outer block's class loader
        assertThat(Thread.currentThread().getContextClassLoader(), is(outerClassLoader));
      }
      
      // Store the class loader after all blocks
      virtualThreadClassLoaders[3] = Thread.currentThread().getContextClassLoader();
      
      // Signal completion
      latch.countDown();
    });
    
    // Wait for the virtual thread to complete
    boolean completed = latch.await(5, TimeUnit.SECONDS);
    assertThat("Virtual thread did not complete in time", completed, is(true));
    
    // Verify the class loaders were correctly set and restored at each level
    assertThat("Inner class loader not correctly set", virtualThreadClassLoaders[2], is(innerClassLoader));
    assertThat("Outer class loader not correctly set", virtualThreadClassLoaders[1], is(outerClassLoader));
    assertThat("Original class loader not correctly restored", virtualThreadClassLoaders[3], is(virtualThreadClassLoaders[0]));
  }
}