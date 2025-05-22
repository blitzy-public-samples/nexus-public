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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.sonatype.goodies.testsupport.TestSupport;

import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.model.Model;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link MavenModels} with Java 21 Virtual Threads.
 */
@Tag("VirtualThreadTestGroup")
public class MavenModelsVirtualThreadTest
    extends TestSupport
{
  private static final int THREAD_COUNT = 50;
  private static final int ITERATIONS = 20;
  
  private ExecutorService executorService;
  private String notXml = "not xml";

  @BeforeEach
  void setUp() {
    // Create an executor service that uses virtual threads
    executorService = Executors.newVirtualThreadPerTaskExecutor();
  }

  @AfterEach
  void tearDown() {
    if (executorService != null) {
      executorService.shutdownNow();
    }
  }

  @Test
  void testReadModel_emptyInputStreamIsNullWithVirtualThreads() throws Exception {
    List<Future<Model>> futures = new ArrayList<>();
    
    // Submit multiple tasks to read empty models concurrently using virtual threads
    for (int i = 0; i < THREAD_COUNT; i++) {
      futures.add(executorService.submit(() -> {
        Model model = null;
        for (int j = 0; j < ITERATIONS; j++) {
          model = MavenModels.readModel(new ByteArrayInputStream(new byte[0]));
          assertThat(model, nullValue());
        }
        return model;
      }));
    }
    
    // Wait for all tasks to complete
    for (Future<Model> future : futures) {
      assertThat(future.get(30, TimeUnit.SECONDS), nullValue());
    }
  }

  @Test
  void testReadModel_NotXmlIsNullWithVirtualThreads() throws Exception {
    List<Future<Model>> futures = new ArrayList<>();
    
    // Submit multiple tasks to read non-XML models concurrently using virtual threads
    for (int i = 0; i < THREAD_COUNT; i++) {
      futures.add(executorService.submit(() -> {
        Model model = null;
        for (int j = 0; j < ITERATIONS; j++) {
          model = MavenModels.readModel(new ByteArrayInputStream(notXml.getBytes()));
          assertThat(model, nullValue());
        }
        return model;
      }));
    }
    
    // Wait for all tasks to complete
    for (Future<Model> future : futures) {
      assertThat(future.get(30, TimeUnit.SECONDS), nullValue());
    }
  }

  @Test
  void testReadModel_WithoutClosingTagsIsNullWithVirtualThreads() throws Exception {
    List<Future<Metadata>> futures = new ArrayList<>();
    
    // Submit multiple tasks to read malformed XML metadata concurrently using virtual threads
    for (int i = 0; i < THREAD_COUNT; i++) {
      futures.add(executorService.submit(() -> {
        Metadata metadata = null;
        for (int j = 0; j < ITERATIONS; j++) {
          metadata = MavenModels.readMetadata(
              getClass().getResourceAsStream("/org/sonatype/nexus/repository/maven/metadataWithoutClosingTags.xml"));
          assertThat(metadata, nullValue());
        }
        return metadata;
      }));
    }
    
    // Wait for all tasks to complete
    for (Future<Metadata> future : futures) {
      assertThat(future.get(30, TimeUnit.SECONDS), nullValue());
    }
  }
  
  @Test
  void testConcurrentModelParsingWithVirtualThreads() throws Exception {
    final int threadCount = 100; // Higher concurrency for stress testing
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch endLatch = new CountDownLatch(threadCount);
    
    // Create tasks that will all start at the same time for maximum concurrency
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      Thread.ofVirtual().start(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          
          // Alternate between different test cases based on thread ID
          switch (threadId % 3) {
            case 0:
              // Test empty input
              for (int j = 0; j < ITERATIONS; j++) {
                Model model = MavenModels.readModel(new ByteArrayInputStream(new byte[0]));
                assertThat(model, nullValue());
              }
              break;
              
            case 1:
              // Test non-XML input
              for (int j = 0; j < ITERATIONS; j++) {
                Model model = MavenModels.readModel(new ByteArrayInputStream(notXml.getBytes()));
                assertThat(model, nullValue());
              }
              break;
              
            case 2:
              // Test malformed XML
              for (int j = 0; j < ITERATIONS; j++) {
                Metadata metadata = MavenModels.readMetadata(
                    getClass().getResourceAsStream("/org/sonatype/nexus/repository/maven/metadataWithoutClosingTags.xml"));
                assertThat(metadata, nullValue());
              }
              break;
          }
        }
        catch (Exception e) {
          log.error("Error in virtual thread test", e);
          throw new RuntimeException(e);
        }
        finally {
          endLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete with timeout
    assertThat("All virtual threads should complete in time", 
        endLatch.await(60, TimeUnit.SECONDS), is(true));
  }
  
  @Test
  void testExceptionHandlingWithVirtualThreads() throws Exception {
    // Test that exceptions are properly propagated when using virtual threads
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      Future<?> future = executor.submit(() -> {
        // Create a scenario that should throw an exception
        // We'll use a null input stream which should cause a NullPointerException
        MavenModels.readModel(null);
        return null;
      });
      
      // The future.get() should throw an ExecutionException wrapping the NullPointerException
      assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
    }
    finally {
      executor.shutdownNow();
    }
  }
}