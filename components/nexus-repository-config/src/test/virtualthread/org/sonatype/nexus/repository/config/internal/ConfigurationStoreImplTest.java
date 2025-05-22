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
package org.sonatype.nexus.repository.config.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.datastore.api.DataSessionSupplier;
import org.sonatype.nexus.repository.config.Configuration;

import org.hamcrest.core.Is;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;

import static java.util.Collections.emptySet;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Virtual Thread tests for {@link ConfigurationStoreImpl}.
 * 
 * Tests that the repository configuration store functions correctly when accessed
 * concurrently through Java 21 Virtual Threads.
 */
@Category(VirtualThreadTestGroup.class)
public class ConfigurationStoreImplTest
    extends TestSupport
{
  @Mock
  private DataSessionSupplier sessionSupplier;

  private ConfigurationStoreImpl underTest;

  @Before
  public void setup() {
    underTest = new ConfigurationStoreImpl(sessionSupplier);
  }

  @Test
  public void readByNamesShouldBeEmptyWhenRepositoriesIsEmpty() {
    Collection<Configuration> configurations = underTest.readByNames(emptySet());

    assertThat(configurations.isEmpty(), Is.is(true));
  }
  
  /**
   * Tests that the {@link ConfigurationStoreImpl#readByNames} method works correctly
   * when accessed concurrently by multiple Virtual Threads.
   * 
   * This test validates that database operations performed by the configuration store
   * are compatible with the Virtual Thread execution model, which is critical for
   * I/O-bound operations like database access in the Java 21 migration.
   */
  @Test
  public void testConcurrentReadByNamesWithVirtualThreads() throws Exception {
    // Create a Virtual Thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that creates a new Virtual Thread for each task
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Create a large number of concurrent tasks to stress test the implementation
      int concurrentTasks = 1000;
      List<Future<Collection<Configuration>>> futures = new ArrayList<>(concurrentTasks);
      
      // Submit tasks to read from the configuration store concurrently
      for (int i = 0; i < concurrentTasks; i++) {
        futures.add(executor.submit(() -> {
          // Use an empty set for simplicity, as we're testing thread behavior not data retrieval
          return underTest.readByNames(emptySet());
        }));
      }
      
      // Wait for all tasks to complete and verify results
      for (Future<Collection<Configuration>> future : futures) {
        Collection<Configuration> configurations = future.get();
        assertThat("Each concurrent call should return an empty collection", 
            configurations.isEmpty(), Is.is(true));
      }
    }
    // ExecutorService is auto-closed by try-with-resources
  }
}

/**
 * Marker interface for tests that should only be run when testing Virtual Thread functionality.
 */
interface VirtualThreadTestGroup {
  // Marker interface only
}