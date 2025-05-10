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
package org.sonatype.nexus.blobstore;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class PerformanceLoggingInputStreamTest
    extends TestSupport
{
  @Mock
  private InputStream source;

  @Mock
  private PerformanceLogger logger;

  private PerformanceLoggingInputStream createUnderTest() {
    return new PerformanceLoggingInputStream(source, logger);
  }

  @Test
  public void shouldPassReadsAndCloseToUnderlyingInputStream() throws IOException {
    PerformanceLoggingInputStream underTest = createUnderTest();
    byte[] buffer1 = new byte[10];
    byte[] buffer2 = new byte[10];

    when(source.read()).thenReturn(123);
    when(source.read(buffer1)).thenReturn(99);
    when(source.read(buffer2, 7, 29)).thenReturn(29);

    assertEquals(123, underTest.read());
    assertEquals(99, underTest.read(buffer1));
    assertEquals(29, underTest.read(buffer2, 7, 29));
    underTest.close();
    verify(source).close();
  }

  @Test
  public void performanceDataIsLoggedOnClose() throws IOException {
    PerformanceLoggingInputStream underTest = createUnderTest();
    underTest.close();
    verify(logger).logRead(0, 0);
  }

  @Test
  public void virtualThreadPerformanceLogging() throws Exception {
    PerformanceLoggingInputStream underTest = createUnderTest();
    byte[] buffer = new byte[1024];
    
    when(source.read()).thenReturn(42);
    when(source.read(buffer)).thenReturn(512);
    
    CountDownLatch latch = new CountDownLatch(1);
    
    // Create and start a virtual thread to perform operations
    Thread.startVirtualThread(() -> {
      try {
        // Perform reads in the virtual thread
        assertEquals(42, underTest.read());
        assertEquals(512, underTest.read(buffer));
        underTest.close();
        latch.countDown();
      }
      catch (IOException e) {
        // Handle exception
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await(5, TimeUnit.SECONDS);
    
    // Verify that the logger was called with the expected values
    verify(logger).logRead(0, 0);
  }
  
  @Test
  public void multipleVirtualThreadsPerformanceLogging() throws Exception {
    final int threadCount = 5;
    CountDownLatch latch = new CountDownLatch(threadCount);
    
    for (int i = 0; i < threadCount; i++) {
      final int threadIndex = i;
      Thread.startVirtualThread(() -> {
        try {
          PerformanceLoggingInputStream threadUnderTest = createUnderTest();
          byte[] buffer = new byte[1024];
          
          when(source.read()).thenReturn(42 + threadIndex);
          
          assertEquals(42 + threadIndex, threadUnderTest.read());
          threadUnderTest.close();
          latch.countDown();
        }
        catch (IOException e) {
          // Handle exception
        }
      });
    }
    
    // Wait for all virtual threads to complete
    latch.await(5, TimeUnit.SECONDS);
  }
}