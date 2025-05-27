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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.sonatype.goodies.testsupport.TestSupport;

import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.model.Model;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link MavenModels} with Java 21 Virtual Threads.
 * 
 * This test class verifies that the MavenModels utility functions correctly
 * when used concurrently with Virtual Threads.
 */
@Tag("VirtualThreadTestGroup")
public class MavenModelsVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_THREADS = 100;
  private static final int ITERATIONS_PER_THREAD = 10;
  
  private String notXml;

  @BeforeEach
  void setUp() {
    notXml = "not xml";
  }

  /**
   * Tests that reading an empty input stream returns null when executed concurrently with Virtual Threads.
   */
  @Test
  void testReadModel_emptyInputStreamIsNull_withVirtualThreads() throws Exception {
    List<Future<Model>> futures = new ArrayList<>();
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        futures.add(executor.submit(() -> {
          Model model = null;
          for (int j = 0; j < ITERATIONS_PER_THREAD; j++) {
            model = MavenModels.readModel(new ByteArrayInputStream(new byte[0]));
            assertThat(model, nullValue());
          }
          return model;
        }));
      }
      
      // Wait for all tasks to complete
      for (Future<Model> future : futures) {
        future.get(); // This will throw an exception if any task failed
      }
    }
  }

  /**
   * Tests that reading non-XML content returns null when executed concurrently with Virtual Threads.
   */
  @Test
  void testReadModel_NotXmlIsNull_withVirtualThreads() throws Exception {
    List<Future<Model>> futures = new ArrayList<>();
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        futures.add(executor.submit(() -> {
          Model model = null;
          for (int j = 0; j < ITERATIONS_PER_THREAD; j++) {
            model = MavenModels.readModel(new ByteArrayInputStream(notXml.getBytes()));
            assertThat(model, nullValue());
          }
          return model;
        }));
      }
      
      // Wait for all tasks to complete
      for (Future<Model> future : futures) {
        future.get(); // This will throw an exception if any task failed
      }
    }
  }

  /**
   * Tests that reading XML without closing tags returns null when executed concurrently with Virtual Threads.
   */
  @Test
  void testReadModel_WithoutClosingTagsIsNull_withVirtualThreads() throws Exception {
    List<Future<Metadata>> futures = new ArrayList<>();
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        futures.add(executor.submit(() -> {
          Metadata metadata = null;
          for (int j = 0; j < ITERATIONS_PER_THREAD; j++) {
            metadata = MavenModels.readMetadata(
                getClass().getResourceAsStream("/org/sonatype/nexus/repository/maven/metadataWithoutClosingTags.xml"));
            assertThat(metadata, nullValue());
          }
          return metadata;
        }));
      }
      
      // Wait for all tasks to complete
      for (Future<Metadata> future : futures) {
        future.get(); // This will throw an exception if any task failed
      }
    }
  }
  
  /**
   * Tests that concurrent model parsing with Virtual Threads doesn't cause exceptions.
   */
  @Test
  void testConcurrentModelParsing_withVirtualThreads() throws Exception {
    // Create a large number of virtual threads to stress test the model parsing
    List<Thread> threads = new ArrayList<>();
    
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      Thread thread = Thread.ofVirtual().name("model-parser-" + i).start(() -> {
        for (int j = 0; j < ITERATIONS_PER_THREAD; j++) {
          assertDoesNotThrow(() -> {
            MavenModels.readModel(new ByteArrayInputStream(new byte[0]));
            MavenModels.readModel(new ByteArrayInputStream(notXml.getBytes()));
            MavenModels.readMetadata(
                getClass().getResourceAsStream("/org/sonatype/nexus/repository/maven/metadataWithoutClosingTags.xml"));
          });
        }
      });
      threads.add(thread);
    }
    
    // Wait for all threads to complete
    for (Thread thread : threads) {
      thread.join();
    }
  }
  
  /**
   * Tests that concurrent model parsing with a mix of valid and invalid inputs doesn't cause unexpected exceptions.
   */
  @Test
  void testMixedInputModelParsing_withVirtualThreads() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        final int threadNum = i;
        futures.add(executor.submit(() -> {
          for (int j = 0; j < ITERATIONS_PER_THREAD; j++) {
            // Mix different types of inputs based on thread number to create varied load
            if (threadNum % 3 == 0) {
              // Empty input
              assertThat(MavenModels.readModel(new ByteArrayInputStream(new byte[0])), nullValue());
            } 
            else if (threadNum % 3 == 1) {
              // Non-XML input
              assertThat(MavenModels.readModel(new ByteArrayInputStream(notXml.getBytes())), nullValue());
            } 
            else {
              // Malformed XML input
              assertThat(MavenModels.readMetadata(
                  getClass().getResourceAsStream("/org/sonatype/nexus/repository/maven/metadataWithoutClosingTags.xml")), 
                  nullValue());
            }
          }
        }));
      }
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get(30, TimeUnit.SECONDS); // Add timeout to prevent test hanging
      }
    }
  }
}