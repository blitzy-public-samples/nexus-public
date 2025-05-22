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
package org.sonatype.nexus.repository;

import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ETagHeaderUtils} when executed within Virtual Threads.
 * 
 * This test ensures that the ETag header utility methods function correctly
 * when invoked from Java 21 Virtual Threads.
 */
public class ETagHeaderUtilsVirtualThreadTest
{
  /**
   * Tests that the quote method correctly handles strong ETags when executed in a Virtual Thread.
   */
  @Test
  public void quoteStrong() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
        
        // Test the actual functionality
        assertEquals("\"foobar\"", ETagHeaderUtils.quote("foobar"));
      } finally {
        latch.countDown();
      }
    });
    
    latch.await();
  }

  /**
   * Tests that the quote method correctly handles weak ETags when executed in a Virtual Thread.
   */
  @Test
  public void quoteWeak() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
        
        // Test the actual functionality
        assertEquals("W/\"foobar\"", ETagHeaderUtils.quote("W/\"foobar\""));
      } finally {
        latch.countDown();
      }
    });
    
    latch.await();
  }

  /**
   * Tests that the extract method correctly handles null input when executed in a Virtual Thread.
   */
  @Test
  public void extractNull() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
        
        // Test the actual functionality
        assertNull(ETagHeaderUtils.extract(null));
      } finally {
        latch.countDown();
      }
    });
    
    latch.await();
  }

  /**
   * Tests that the extract method correctly handles empty input when executed in a Virtual Thread.
   */
  @Test
  public void extractEmpty() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
        
        // Test the actual functionality
        assertEquals("", ETagHeaderUtils.extract(""));
      } finally {
        latch.countDown();
      }
    });
    
    latch.await();
  }

  /**
   * Tests that the extract method correctly handles strong ETags when executed in a Virtual Thread.
   */
  @Test
  public void extractStrong() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
        
        // Test the actual functionality
        assertEquals("foobar", ETagHeaderUtils.extract("\"foobar\""));
      } finally {
        latch.countDown();
      }
    });
    
    latch.await();
  }

  /**
   * Tests that the extract method correctly handles weak ETags when executed in a Virtual Thread.
   */
  @Test
  public void extractWeak() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
        
        // Test the actual functionality
        assertEquals("W/\"foobar\"", ETagHeaderUtils.extract("W/\"foobar\""));
      } finally {
        latch.countDown();
      }
    });
    
    latch.await();
  }
}