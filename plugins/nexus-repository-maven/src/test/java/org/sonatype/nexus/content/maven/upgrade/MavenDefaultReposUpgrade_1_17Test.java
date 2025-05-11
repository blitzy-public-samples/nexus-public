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
package org.sonatype.nexus.content.maven.upgrade;

import java.sql.Connection;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport; // Updated to version 3.0 for Java 21 compatibility
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.datastore.api.DataStore;
import org.sonatype.nexus.repository.config.ConfigurationDAO;
import org.sonatype.nexus.repository.config.internal.ConfigurationData;
import org.sonatype.nexus.repository.maven.internal.MavenDefaultRepositoriesContributor;
import org.sonatype.nexus.testdb.DataSessionRule;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Tests for {@link MavenDefaultReposUpgrade_1_17} that verify the migration of Maven repository
 * content disposition settings.
 * 
 * Updated for Java 21 compatibility using JUnit Jupiter and MockitoExtension.
 * This test validates the database migration step that changes the content disposition
 * setting for default Maven repositories from null to "INLINE".
 */
@ExtendWith(MockitoExtension.class)
public class MavenDefaultReposUpgrade_1_17Test
    extends TestSupport
{
  @RegisterExtension
  public DataSessionRule sessionRule = new DataSessionRule(DEFAULT_DATASTORE_NAME)
      .access(ConfigurationDAO.class);

  private DataStore<?> store;

  private ConfigurationDAO configurationDAO;

  private MavenDefaultReposUpgrade_1_17 migrationStep;

  private ConfigurationData hostedRepo;

  private ConfigurationData proxyRepo;

  private ConfigurationData groupRepo;

  private ConfigurationData nonDefaultRepo;

  @BeforeEach
  public void setUp() {
    createMockData();

    MavenDefaultRepositoriesContributor mockContributor = mock(MavenDefaultRepositoriesContributor.class);
    when(mockContributor.getRepositoryConfigurations()).thenReturn(Arrays.asList(hostedRepo, proxyRepo, groupRepo));

    migrationStep = new MavenDefaultReposUpgrade_1_17(mockContributor);
  }

  private void createMockData() {
    store = sessionRule.getDataStore(DEFAULT_DATASTORE_NAME).get();
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      configurationDAO = session.access(ConfigurationDAO.class);

      hostedRepo = createConfig("hosted1", "maven2-hosted", null);
      nonDefaultRepo = createConfig("hosted2", "maven2-hosted", "ATTACHMENT");
      proxyRepo = createConfig("proxy", "maven2-proxy", null);
      groupRepo = createConfig("group", "maven2-group", "ATTACHMENT");

      session.getTransaction().commit();
    }
  }

  /**
   * Tests that the migration correctly updates the content disposition setting
   * for default Maven repositories while preserving settings for non-default repositories.
   */
  @Test
  public void testMigrationWorksAsExpected() throws Exception {
    try (Connection conn = store.openConnection()) {
      migrationStep.migrate(conn);
    }

    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      configurationDAO = session.access(ConfigurationDAO.class);

      ConfigurationData modifiedHostedRepo = configurationDAO.readByName(hostedRepo.getName()).get();
      ConfigurationData modifiedProxyRepo = configurationDAO.readByName(proxyRepo.getName()).get();
      ConfigurationData groupRepo = configurationDAO.readByName(this.groupRepo.getName()).get();

      ConfigurationData nonDefault = configurationDAO.readByName(nonDefaultRepo.getName()).get();

      assertEquals("INLINE", modifiedHostedRepo.attributes("maven").get("contentDisposition", String.class));
      assertEquals("INLINE", modifiedProxyRepo.attributes("maven").get("contentDisposition", String.class));

      // If it is a group repo, it shouldn't change
      assertEquals("ATTACHMENT", groupRepo.attributes("maven").get("contentDisposition", String.class));

      // If it is a non-default repo, then the value shouldn't change
      assertEquals("ATTACHMENT", nonDefault.attributes("maven").get("contentDisposition", String.class));
    }
  }

  private ConfigurationData createConfig(final String name, final String recipeName, String contentDisposition) {
    ConfigurationData config = new ConfigurationData();
    config.setName(name);
    config.setRecipeName(recipeName);
    config.setAttributes(ImmutableMap.of("maven", contentDisposition != null ? ImmutableMap.of("contentDisposition",
        contentDisposition) : Collections.emptyMap()));

    configurationDAO.create(config);
    return config;
  }

  /**
   * Tests concurrent access to repository configurations after migration using Java 21 Virtual Threads.
   * This demonstrates how the migration results can be accessed concurrently in a production environment.
   */
  @Test
  @Tag("Java21TestGroup")
  @Tag("VirtualThreadTestGroup")
  public void testConcurrentAccessWithVirtualThreads() throws Exception {
    // First perform the migration
    try (Connection conn = store.openConnection()) {
      migrationStep.migrate(conn);
    }
    
    // Now test concurrent access to the migrated data using virtual threads
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Use Java 21 Virtual Threads via newVirtualThreadPerTaskExecutor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Launch multiple virtual threads to concurrently access the repository configurations
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
            ConfigurationDAO dao = session.access(ConfigurationDAO.class);
            
            // Verify hosted repository has INLINE content disposition
            ConfigurationData hosted = dao.readByName(hostedRepo.getName()).get();
            if ("INLINE".equals(hosted.attributes("maven").get("contentDisposition", String.class))) {
              successCount.incrementAndGet();
            }
          } catch (Exception e) {
            // Count failures by not incrementing successCount
            log.error("Error in virtual thread", e);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete (with timeout)
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify all threads completed successfully
      assertTrue(completed, "All virtual threads should complete within timeout");
      assertEquals(threadCount, successCount.get(), 
          "All virtual threads should successfully verify the migration result");
    }
  }
}