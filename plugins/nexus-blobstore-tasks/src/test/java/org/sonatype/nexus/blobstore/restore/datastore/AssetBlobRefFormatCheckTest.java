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
package org.sonatype.nexus.blobstore.restore.datastore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.config.internal.ConfigurationData;
import org.sonatype.nexus.repository.content.store.AssetBlobStore;
import org.sonatype.nexus.repository.content.store.FormatStoreManager;

import com.google.common.collect.ImmutableMap;
import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.config.ConfigurationConstants.DATA_STORE_NAME;
import static org.sonatype.nexus.repository.config.ConfigurationConstants.STORAGE;

/**
 * Tests for {@link AssetBlobRefFormatCheck} that verify the correct detection of asset blob references
 * that need migration.
 */
@ExtendWith(MockitoExtension.class)
class AssetBlobRefFormatCheckTest
    extends TestSupport
{
  public static final String MAVEN_2 = "maven2";

  public static final String DATASTORE_NAME = "nexus";

  @Mock
  private Format format;

  @Mock
  private Repository repository;

  @Mock
  private FormatStoreManager formatStoreManager;

  @Mock
  private AssetBlobStore<?> assetBlobStore;

  private AssetBlobRefFormatCheck underTest;

  @BeforeEach
  void setUp() {
    Map<String, FormatStoreManager> formatStoreManagers = new HashMap<>();
    formatStoreManagers.put(MAVEN_2, formatStoreManager);

    underTest = new AssetBlobRefFormatCheck(formatStoreManagers);
  }

  @Test
  void assetBlobRefWithNodeIdReturnsTrue() {
    mockRepository();
    mockAssetBlobStore(true);

    assertThat(underTest.isAssetBlobRefNotMigrated(repository), is(true));
  }

  @Test
  void assetBlobRefWithoutNodeIdReturnsFalse() {
    mockRepository();
    mockAssetBlobStore(false);

    assertThat(underTest.isAssetBlobRefNotMigrated(repository), is(false));
  }
  
  /**
   * Test that demonstrates Java 21 pattern matching with records.
   * This test validates that the class works correctly with Java 21 features.
   */
  @Test
  @org.junit.jupiter.api.Tag("Java21")
  @org.junit.experimental.categories.Category(Java21TestGroup.class)
  void patternMatchingWithRecordsForConfiguration() {
    // Define a record to represent repository configuration data
    record RepositoryConfig(String format, String datastoreName, boolean migrated) {}
    
    // Create test configurations
    RepositoryConfig migratedConfig = new RepositoryConfig(MAVEN_2, DATASTORE_NAME, false);
    RepositoryConfig notMigratedConfig = new RepositoryConfig(MAVEN_2, DATASTORE_NAME, true);
    
    // Set up the repository and mock for the first config
    mockRepository();
    
    // Use Java 21 pattern matching with the record to determine which mock to set up
    Object config = migratedConfig;
    if (config instanceof RepositoryConfig(String format, String datastoreName, boolean migrated)) {
      // We can directly use the extracted variables without additional getters
      mockAssetBlobStore(migrated);
      
      // Verify the result matches the expected migration status
      assertThat(underTest.isAssetBlobRefNotMigrated(repository), is(migrated));
      assertThat(format, is(MAVEN_2));
      assertThat(datastoreName, is(DATASTORE_NAME));
    }
    
    // Test with the second configuration
    config = notMigratedConfig;
    if (config instanceof RepositoryConfig(String format, String datastoreName, boolean migrated)) {
      mockAssetBlobStore(migrated);
      
      // Verify the result matches the expected migration status
      assertThat(underTest.isAssetBlobRefNotMigrated(repository), is(migrated));
      assertThat(format, is(MAVEN_2));
      assertThat(datastoreName, is(DATASTORE_NAME));
    }
  }
  
  /**
   * Test that demonstrates using Java 21 Virtual Threads to concurrently check multiple repositories.
   * This simulates how the AssetBlobRefFormatCheck could be used in a high-concurrency environment
   * leveraging Java 21's virtual threads for improved performance.
   */
  @Test
  @org.junit.jupiter.api.Tag("VirtualThread")
  @org.junit.experimental.categories.Category(VirtualThreadTestGroup.class)
  void concurrentChecksUsingVirtualThreads() throws Exception {
    // Number of concurrent checks to perform
    int concurrentChecks = 100;
    
    // Set up the repository and mock
    mockRepository();
    mockAssetBlobStore(true);
    
    // Create a latch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(concurrentChecks);
    
    // Track success/failure counts
    AtomicInteger successCount = new AtomicInteger(0);
    List<Exception> exceptions = new ArrayList<>();
    
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to check repositories concurrently
      for (int i = 0; i < concurrentChecks; i++) {
        executor.submit(() -> {
          try {
            // Perform the check
            boolean result = underTest.isAssetBlobRefNotMigrated(repository);
            
            // If successful, increment the success count
            if (result) {
              successCount.incrementAndGet();
            }
          } catch (Exception e) {
            synchronized (exceptions) {
              exceptions.add(e);
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete (with timeout)
      boolean completed = latch.await(10, TimeUnit.SECONDS);
      
      // Verify all tasks completed successfully
      assertThat("All tasks should complete within the timeout", completed, is(true));
      assertThat("No exceptions should be thrown", exceptions.isEmpty(), is(true));
      assertThat("All checks should return the expected result", successCount.get(), is(concurrentChecks));
    }
  }

  private void mockRepository() {
    when(repository.getFormat()).thenReturn(format);
    when(format.getValue()).thenReturn(MAVEN_2);


    Configuration configuration = new ConfigurationData();
    Map<String, Map<String, Object>> attributes = new HashMap<>();
    attributes.put(STORAGE, ImmutableMap.of(DATA_STORE_NAME, DATASTORE_NAME));
    configuration.setAttributes(attributes);
    when(repository.getConfiguration()).thenReturn(configuration);
  }

  private void mockAssetBlobStore(final boolean notMigrated) {
    when(formatStoreManager.assetBlobStore(DATASTORE_NAME)).thenReturn(assetBlobStore);
    when(assetBlobStore.notMigratedAssetBlobRefsExists()).thenReturn(notMigrated);
  }
}