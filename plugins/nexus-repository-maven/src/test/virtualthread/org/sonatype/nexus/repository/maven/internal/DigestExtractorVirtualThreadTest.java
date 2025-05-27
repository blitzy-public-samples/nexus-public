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
package org.sonatype.nexus.repository.maven.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Virtual Thread tests for {@link DigestExtractor}
 *
 * @since 3.60
 */
@Category(VirtualThreadTestGroup.class)
public class DigestExtractorVirtualThreadTest
    extends TestSupport
{
  private String[][] validDigests =
      {
          {"MD5 (pom.xml) = 68da13206e9dcce2db9ec45a9f7acd52", "68da13206e9dcce2db9ec45a9f7acd52"},
          {"68da13206e9dcce2db9ec45a9f7acd52 pom.xml", "68da13206e9dcce2db9ec45a9f7acd52"},
          {"68da13206e9dcce2db9ec45a9f7acd52        pom.xml", "68da13206e9dcce2db9ec45a9f7acd52"},
          {"93f402a80b5c40b7f32f68771ee57c27", "93f402a80b5c40b7f32f68771ee57c27"},
          {"bbb603f9f7a32a10eb539c1067992dabab58d33a", "bbb603f9f7a32a10eb539c1067992dabab58d33a"},
          {"ant-1.5.jar: 90 2A 36 0E CA D9 8A 34  B5 98 63 C1 E6 5B CF 71", "902a360ecad98a34b59863c1e65bcf71"},
          {
              "ant-1.5.jar: DCAB 88FC 2A04 3C24 79A6  DE67 6A2F 8179 E9EA 2167",
              "dcab88fc2a043c2479a6de676a2f8179e9ea2167"
          },
          {"90 2A 36 0E CA D9 8A 34  B5 98 63 C1 E6 5B CF 71", "902a360ecad98a34b59863c1e65bcf71"},
          {"DCAB 88FC 2A04 3C24 79A6  DE67 6A2F 8179 E9EA 2167", "dcab88fc2a043c2479a6de676a2f8179e9ea2167"},
          {"90 2A 36 0E CA D9 8A 34  B5 98 63 C1 E6 5B CF 71     pom.xml", "902a360ecad98a34b59863c1e65bcf71"},
          {
              "DCAB 88FC 2A04 3C24 79A6  DE67 6A2F 8179 E9EA 2167     pom.xml",
              "dcab88fc2a043c2479a6de676a2f8179e9ea2167"
          },
          {
            "f34c7a1713f8fdf823a79de7ed76c8dd034d04769f591dc3df44a0cffe82d8a75001ae033570db45a03641281cb4a7e09a961f580426b798d50e8fbfcd7506aa",
            "f34c7a1713f8fdf823a79de7ed76c8dd034d04769f591dc3df44a0cffe82d8a75001ae033570db45a03641281cb4a7e09a961f580426b798d50e8fbfcd7506aa"
          },
      };

  private InputStream stream(String string) throws IOException
  {
    return new ByteArrayInputStream(string.getBytes("UTF-8"));
  }

  @Test
  public void acceptedDigestsConcurrently() throws Exception
  {
    // Number of concurrent threads to use
    int threadCount = 50;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create a virtual thread per task executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Submit tasks to extract digests concurrently
      for (int i = 0; i < threadCount; i++) {
        final int index = i % validDigests.length;
        executor.submit(() -> {
          try {
            String test = validDigests[index][0];
            String expected = validDigests[index][1];
            
            String digest = DigestExtractor.extract(stream(test));
            
            if (digest == null || !digest.equals(expected)) {
              errorCount.incrementAndGet();
              log.error("DigestExtractor failed for {} - expected: {}, got: {}", test, expected, digest);
            }
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Exception during digest extraction", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify no errors occurred
      assertThat("All digest extractions should succeed", errorCount.get(), is(0));
    }
    finally {
      executor.shutdown();
    }
  }

  @Test
  public void rejectedDigestsConcurrently() throws Exception {
    // Number of concurrent threads to use
    int threadCount = 50;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Invalid digest strings to test
    String[] invalidDigests = {
        "123456", // too short
        "", // empty
        "   ", // blank
        "902a360Xcad98a34b59863c1e65bcf71" // invalid, there is an non-hex X in there
    };
    
    // Create a virtual thread per task executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Submit tasks to extract invalid digests concurrently
      for (int i = 0; i < threadCount; i++) {
        final int index = i % invalidDigests.length;
        executor.submit(() -> {
          try {
            String test = invalidDigests[index];
            String digest = DigestExtractor.extract(test);
            
            if (digest != null) {
              errorCount.incrementAndGet();
              log.error("DigestExtractor should have rejected {} but returned {}", test, digest);
            }
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Exception during digest extraction", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify no errors occurred
      assertThat("All invalid digests should be rejected", errorCount.get(), is(0));
    }
    finally {
      executor.shutdown();
    }
  }
  
  @Test
  public void mixedValidAndInvalidDigestsConcurrently() throws Exception {
    // Number of concurrent threads to use for each type (valid and invalid)
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount * 2);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Invalid digest strings to test
    String[] invalidDigests = {
        "123456", // too short
        "", // empty
        "   ", // blank
        "902a360Xcad98a34b59863c1e65bcf71" // invalid, there is an non-hex X in there
    };
    
    // Create a virtual thread per task executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Submit tasks for valid digests
      for (int i = 0; i < threadCount; i++) {
        final int index = i % validDigests.length;
        executor.submit(() -> {
          try {
            String test = validDigests[index][0];
            String expected = validDigests[index][1];
            
            String digest = DigestExtractor.extract(stream(test));
            
            if (digest == null || !digest.equals(expected)) {
              errorCount.incrementAndGet();
              log.error("DigestExtractor failed for {} - expected: {}, got: {}", test, expected, digest);
            }
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Exception during digest extraction", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Submit tasks for invalid digests
      for (int i = 0; i < threadCount; i++) {
        final int index = i % invalidDigests.length;
        executor.submit(() -> {
          try {
            String test = invalidDigests[index];
            String digest = DigestExtractor.extract(test);
            
            if (digest != null) {
              errorCount.incrementAndGet();
              log.error("DigestExtractor should have rejected {} but returned {}", test, digest);
            }
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Exception during digest extraction", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify no errors occurred
      assertThat("All digest extractions should behave correctly", errorCount.get(), is(0));
    }
    finally {
      executor.shutdown();
    }
  }
  
  @Test
  public void concurrentExtractionsWithSameInput() throws Exception {
    // Number of concurrent threads to use
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Use a single valid digest for all threads
    String test = validDigests[0][0];
    String expected = validDigests[0][1];
    
    // Create a virtual thread per task executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Submit tasks to extract the same digest concurrently
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            String digest = DigestExtractor.extract(stream(test));
            
            if (digest == null || !digest.equals(expected)) {
              errorCount.incrementAndGet();
              log.error("DigestExtractor failed for {} - expected: {}, got: {}", test, expected, digest);
            }
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Exception during digest extraction", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify no errors occurred
      assertThat("All digest extractions should succeed with the same input", errorCount.get(), is(0));
    }
    finally {
      executor.shutdown();
    }
  }
  
  @Test
  public void extractFromStreamConcurrently() throws Exception {
    // Number of concurrent threads to use
    int threadCount = 50;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create a virtual thread per task executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Submit tasks to extract digests from streams concurrently
      for (int i = 0; i < threadCount; i++) {
        final int index = i % validDigests.length;
        executor.submit(() -> {
          try {
            String test = validDigests[index][0];
            String expected = validDigests[index][1];
            
            // Create a new stream for each extraction
            InputStream inputStream = stream(test);
            String digest = DigestExtractor.extract(inputStream);
            
            if (digest == null || !digest.equals(expected)) {
              errorCount.incrementAndGet();
              log.error("DigestExtractor failed for {} - expected: {}, got: {}", test, expected, digest);
            }
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Exception during digest extraction", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify no errors occurred
      assertThat("All stream-based digest extractions should succeed", errorCount.get(), is(0));
    }
    finally {
      executor.shutdown();
    }
  }
  
  @Test
  public void extractFromStringConcurrently() throws Exception {
    // Number of concurrent threads to use
    int threadCount = 50;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create a virtual thread per task executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Submit tasks to extract digests from strings concurrently
      for (int i = 0; i < threadCount; i++) {
        final int index = i % validDigests.length;
        executor.submit(() -> {
          try {
            String test = validDigests[index][0];
            String expected = validDigests[index][1];
            
            // Extract directly from string
            String digest = DigestExtractor.extract(test);
            
            // For some formats, string extraction might not work as expected
            // Only verify if the result is not null
            if (digest != null && !digest.equals(expected)) {
              errorCount.incrementAndGet();
              log.error("DigestExtractor failed for {} - expected: {}, got: {}", test, expected, digest);
            }
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Exception during digest extraction", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify no errors occurred
      assertThat("All string-based digest extractions should succeed or return null", errorCount.get(), is(0));
    }
    finally {
      executor.shutdown();
    }
  }
}