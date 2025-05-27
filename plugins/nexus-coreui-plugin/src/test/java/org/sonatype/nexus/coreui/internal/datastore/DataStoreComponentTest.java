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
package org.sonatype.nexus.coreui.internal.datastore;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.datastore.api.DataStore;
import org.sonatype.nexus.datastore.api.DataStoreConfiguration;
import org.sonatype.nexus.datastore.api.DataStoreManager;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.security.RepositoryPermissionChecker;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class DataStoreComponentTest
    extends TestSupport
{
  @Mock
  private DataStoreManager dataStoreManager;

  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private RepositoryPermissionChecker repositoryPermissionChecker;

  @Mock
  private DataStore<?> h2DataStore;

  @Mock
  private DataStore<?> postgresqlDataStore;

  private DataStoreComponent underTest;

  @BeforeEach
  public void setup() {
    DataStoreConfiguration contentConfig = new DataStoreConfiguration();
    contentConfig.setName("content");
    contentConfig.setType("jdbc");
    contentConfig.setSource("local");
    contentConfig.setAttributes(ImmutableMap.of("jdbcUrl", "jdbc:h2:som/datastore/url"));

    DataStoreConfiguration configConfig = new DataStoreConfiguration();
    configConfig.setName("config");
    configConfig.setType("jdbc");
    configConfig.setSource("local");
    configConfig.setAttributes(ImmutableMap.of("jdbcUrl", "jdbc:postgresql:some/datastore/url"));

    when(h2DataStore.getConfiguration()).thenReturn(contentConfig);
    when(postgresqlDataStore.getConfiguration()).thenReturn(configConfig);

    when(dataStoreManager.browse()).thenReturn(asList(h2DataStore, postgresqlDataStore));

    underTest = new DataStoreComponent(dataStoreManager, repositoryManager, repositoryPermissionChecker, true);
  }

  @Test
  public void readingDatabaseShouldReturnAllDataStores() {
    List<DataStoreXO> dataStores = underTest.read();
    assertThat(dataStores, hasSize(2));
    assertThat(dataStores.get(0).getName(), is("content"));
    assertThat(dataStores.get(1).getName(), is("config"));
    
    // Alternative assertion style using JUnit Jupiter
    assertEquals(2, dataStores.size(), "Should return two data stores");
    assertEquals("content", dataStores.get(0).getName(), "First data store should be 'content'");
    assertEquals("config", dataStores.get(1).getName(), "Second data store should be 'config'");
  }

  @Test
  public void readingH2DatabaseShouldReturnOnlyH2DataStores() {
    List<DataStoreXO> dataStores = underTest.readH2();
    assertThat(dataStores, hasSize(1));
    assertThat(dataStores.get(0).getName(), is("content"));
    
    // Alternative assertion style using JUnit Jupiter
    assertEquals(1, dataStores.size(), "Should return only one H2 data store");
    assertEquals("content", dataStores.get(0).getName(), "H2 data store should be 'content'");
  }
  
  @Test
  public void concurrentOperationsWithVirtualThreadsShouldSucceed() throws Exception {
    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Perform operations on the component
            List<DataStoreXO> dataStores = underTest.read();
            if (dataStores.size() != 2) {
              errorCount.incrementAndGet();
            }
            
            List<DataStoreXO> h2DataStores = underTest.readH2();
            if (h2DataStores.size() != 1) {
              errorCount.incrementAndGet();
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertEquals(0, errorCount.get(), "All virtual thread operations should complete successfully");
    } finally {
      executor.shutdown();
    }
  }
}
