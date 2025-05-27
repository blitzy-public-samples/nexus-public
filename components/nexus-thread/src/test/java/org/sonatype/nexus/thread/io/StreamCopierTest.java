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
package org.sonatype.nexus.thread.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

public class StreamCopierTest
    extends VirtualThreadTestSupport
{
  private String DEFAULT_READ_OUTPUT = "Test read";

  private StreamCopier<String> underTest;

  @BeforeEach
  void setUp() {
    underTest = null;
  }

  @Test
  void validOutputWhenReadSimple() {
    underTest = new StreamCopier<>(outputStream -> {
    }, inputStream -> DEFAULT_READ_OUTPUT);

    assertEquals(DEFAULT_READ_OUTPUT, underTest.read());
  }

  @Test
  void readWrittenValueMultipleTimesWhenWriteToOutputStream() {
    underTest = new StreamCopier<>(this::writeString, this::readString);

    assertEquals(DEFAULT_READ_OUTPUT, underTest.read());
    assertEquals(DEFAULT_READ_OUTPUT, underTest.read());
  }

  @Test
  void failToReadValueWhenWriteThrowsException() {
    underTest = new StreamCopier<>(outputStream -> {
      throw new RuntimeException("Test writing failure");
    }, this::readString);

    // using a short timeout to make test fail faster
    RuntimeException exception = assertThrows(RuntimeException.class, () -> underTest.read(1000));
    assertEquals("Unable to properly read from stream", exception.getMessage());
  }

  @Test
  void failToReadValueWhenLeavingStreamsOpenException() {
    underTest = new StreamCopier<>(this::writeString, this::readString);
    underTest.afterReadLeaveStreamsOpen();

    // using a short timeout to make test fail faster
    RuntimeException exception = assertThrows(RuntimeException.class, () -> underTest.read(1000));
    assertEquals("Unable to properly read from stream", exception.getMessage());
  }

  @Test
  void readWrittenValueMultipleTimesWhenLeavingStreamsOpenAndClosingManually() {
    underTest = new StreamCopier<>(this::writeStringAndClose, this::readString);
    underTest.afterReadLeaveStreamsOpen();

    assertEquals(DEFAULT_READ_OUTPUT, underTest.read());
    assertEquals(DEFAULT_READ_OUTPUT, underTest.read());
  }

  @Test
  void failToReadValueWhenReadThrowsException() {
    underTest = new StreamCopier<>(this::writeString, inputStream -> {
      throw new RuntimeException("Test Reading failure");
    });

    // using a short timeout to make test fail faster
    RuntimeException exception = assertThrows(RuntimeException.class, () -> underTest.read(1000));
    assertEquals("Unable to properly read from stream", exception.getMessage());
  }
  
  @Test
  @Tag("VirtualThreadTestGroup")
  void concurrentIOOperationsWithVirtualThreads() throws Exception {
    assumeVirtualThreadSupported();
    
    // Create a StreamCopier with a virtual thread executor
    ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    underTest = new StreamCopier<>(this::writeString, this::readString, virtualExecutor);
    
    // Run multiple concurrent read operations
    int concurrentOperations = 100;
    AtomicInteger successCount = new AtomicInteger(0);
    
    runConcurrently(concurrentOperations, () -> {
      String result = underTest.read();
      if (DEFAULT_READ_OUTPUT.equals(result)) {
        successCount.incrementAndGet();
      }
    });
    
    assertEquals(concurrentOperations, successCount.get(), 
        "All concurrent operations should complete successfully");
    
    virtualExecutor.shutdown();
  }
  
  @Test
  @Tag("VirtualThreadTestGroup")
  void highConcurrencyWithVirtualThreads() throws Exception {
    assumeVirtualThreadSupported();
    
    // Create a StreamCopier with a virtual thread executor
    ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    underTest = new StreamCopier<>(this::writeString, this::readString, virtualExecutor);
    
    // Run a high number of concurrent operations (1000+)
    int concurrentOperations = 1000;
    AtomicInteger successCount = new AtomicInteger(0);
    
    runConcurrently(concurrentOperations, () -> {
      String result = underTest.read();
      if (DEFAULT_READ_OUTPUT.equals(result)) {
        successCount.incrementAndGet();
      }
    });
    
    assertEquals(concurrentOperations, successCount.get(), 
        "All high-concurrency operations should complete successfully");
    
    virtualExecutor.shutdown();
  }
  
  @Test
  @Tag("VirtualThreadTestGroup")
  void threadPinningDetectionAndPrevention() throws Exception {
    assumeVirtualThreadSupported();
    
    // Create a task that might cause thread pinning (using synchronized block)
    Runnable potentiallyPinningTask = () -> {
      synchronized (this) {
        // Simulate some work inside a synchronized block
        try {
          Thread.sleep(50);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    };
    
    // Detect if pinning occurs
    boolean pinningDetected = detectThreadPinning(potentiallyPinningTask);
    
    // Create a StreamCopier that avoids pinning by not using synchronized blocks
    ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    underTest = new StreamCopier<>(this::writeString, this::readString, virtualExecutor);
    
    // Verify the StreamCopier can handle concurrent operations even with potential pinning
    int concurrentOperations = 50;
    AtomicInteger successCount = new AtomicInteger(0);
    
    runConcurrently(concurrentOperations, () -> {
      String result = underTest.read();
      if (DEFAULT_READ_OUTPUT.equals(result)) {
        successCount.incrementAndGet();
      }
      
      // Also run the potentially pinning task
      potentiallyPinningTask.run();
    });
    
    assertEquals(concurrentOperations, successCount.get(), 
        "All operations should complete successfully despite potential thread pinning");
    
    virtualExecutor.shutdown();
  }
  
  @Test
  @Tag("VirtualThreadTestGroup")
  void comparePerformanceBetweenPlatformAndVirtualThreads() throws Exception {
    assumeVirtualThreadSupported();
    
    // Create StreamCopiers with different executor types
    ExecutorService platformExecutor = Executors.newFixedThreadPool(10);
    ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    StreamCopier<String> platformCopier = new StreamCopier<>(this::writeString, this::readString, platformExecutor);
    StreamCopier<String> virtualCopier = new StreamCopier<>(this::writeString, this::readString, virtualExecutor);
    
    // Measure platform thread performance
    int operationCount = 100;
    long platformStart = System.currentTimeMillis();
    
    for (int i = 0; i < operationCount; i++) {
      assertEquals(DEFAULT_READ_OUTPUT, platformCopier.read());
    }
    
    long platformDuration = System.currentTimeMillis() - platformStart;
    
    // Measure virtual thread performance
    long virtualStart = System.currentTimeMillis();
    
    for (int i = 0; i < operationCount; i++) {
      assertEquals(DEFAULT_READ_OUTPUT, virtualCopier.read());
    }
    
    long virtualDuration = System.currentTimeMillis() - virtualStart;
    
    // Log the results (no assertion as performance can vary by environment)
    log.info("Platform threads: {} operations in {} ms", operationCount, platformDuration);
    log.info("Virtual threads: {} operations in {} ms", operationCount, virtualDuration);
    
    platformExecutor.shutdown();
    virtualExecutor.shutdown();
  }
  
  @Test
  @Tag("VirtualThreadTestGroup")
  void customVirtualThreadExecutorService() throws Exception {
    assumeVirtualThreadSupported();
    
    // Create a custom ExecutorService using Virtual Threads
    ExecutorService customExecutor = newVirtualThreadExecutor("StreamCopier-Test-");
    underTest = new StreamCopier<>(this::writeString, this::readString, customExecutor);
    
    // Verify it works correctly
    assertEquals(DEFAULT_READ_OUTPUT, underTest.read());
    
    // Run multiple concurrent operations
    int concurrentOperations = 50;
    AtomicInteger successCount = new AtomicInteger(0);
    
    runConcurrently(concurrentOperations, () -> {
      String result = underTest.read();
      if (DEFAULT_READ_OUTPUT.equals(result)) {
        successCount.incrementAndGet();
      }
    });
    
    assertEquals(concurrentOperations, successCount.get(), 
        "All operations with custom executor should complete successfully");
    
    customExecutor.shutdown();
    customExecutor.awaitTermination(5, TimeUnit.SECONDS);
  }
  
  private void writeStringAndClose(OutputStream outputStream) {
    try {
      this.writeString(outputStream);

      outputStream.close();
    }
    catch (IOException e) {
      fail(e.getMessage());
    }
  }

  private void writeString(OutputStream outputStream) {
    try {
      outputStream.write(DEFAULT_READ_OUTPUT.getBytes());
    }
    catch (IOException e) {
      fail(e.getMessage());
    }
  }

  private String readString(final InputStream inputStream) {
    try {
      return IOUtils.toString(inputStream);
    }
    catch (IOException e) {
      fail(e.getMessage());
    }
    return null;
  }
}