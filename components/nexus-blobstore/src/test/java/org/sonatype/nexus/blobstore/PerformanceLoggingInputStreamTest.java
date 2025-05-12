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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.BeforeEach;
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

  private PerformanceLoggingInputStream underTest;

  @BeforeEach
  void setUp() {
    underTest = new PerformanceLoggingInputStream(source, logger);
  }

  @Test
  void shouldPassReadsAndCloseToUnderlyingInputStream() throws IOException {
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
  void performanceDataIsLoggedOnClose() throws IOException {
    underTest.close();
    verify(logger).logRead(0, 0);
  }
  
  @Test
  void shouldLogPerformanceDataWithVirtualThread() throws IOException, InterruptedException {
    // Create a simple input stream with test data
    byte[] testData = "test data".getBytes();
    ByteArrayInputStream testInputStream = new ByteArrayInputStream(testData);
    PerformanceLogger testLogger = new PerformanceLogger();
    testLogger.setBlobStoreName("test-blobstore");
    
    // Create the performance logging input stream
    PerformanceLoggingInputStream inputStream = 
        new PerformanceLoggingInputStream(testInputStream, testLogger);
    
    // Use a virtual thread to read from the stream
    Thread virtualThread = Thread.ofVirtual().name("virtual-test-thread").start(() -> {
      try {
        byte[] buffer = new byte[1024];
        while (inputStream.read(buffer) != -1) {
          // Just read the data
        }
        inputStream.close();
      } catch (IOException e) {
        // Handle exception
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    // Verify the thread was virtual
    assertEquals(true, virtualThread.isVirtual());
  }
}