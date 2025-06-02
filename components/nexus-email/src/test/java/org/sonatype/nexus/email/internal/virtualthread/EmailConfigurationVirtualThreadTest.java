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
package org.sonatype.nexus.email.internal.virtualthread;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.crypto.secrets.Secret;
import org.sonatype.nexus.crypto.secrets.SecretsFactory;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.datastore.mybatis.handlers.SecretTypeHandler;
import org.sonatype.nexus.email.EmailConfiguration;
import org.sonatype.nexus.internal.email.EmailConfigurationDAO;
import org.sonatype.nexus.internal.email.EmailConfigurationData;
import org.sonatype.nexus.testdb.DataSessionRule;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Tests for {@link EmailConfiguration} operations under Java 21 virtual threads.
 * 
 * @since 3.60
 */
public class EmailConfigurationVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_THREADS = 100;
  private static final int TIMEOUT_SECONDS = 10;

  private final SecretsFactory secretsFactory = mock(SecretsFactory.class);

  private final DataSessionRule sessionRule =
      new DataSessionRule().access(EmailConfigurationDAO.class).handle(new SecretTypeHandler(secretsFactory));

  private DataSession<?> session;

  private EmailConfigurationDAO dao;

  private Secret secret;
  
  private ExecutorService virtualThreadExecutor;

  @BeforeEach
  public void setup() {
    session = sessionRule.openSession(DEFAULT_DATASTORE_NAME);
    dao = session.access(EmailConfigurationDAO.class);
    secret = mock(Secret.class);
    
    // Create a virtual thread executor for concurrent testing
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
  }

  @AfterEach
  public void tearDown() {
    session.close();
    virtualThreadExecutor.shutdown();
  }

  private EmailConfigurationData createEmailConfigurationData(Secret secret) {
    when(secret.getId()).thenReturn("_1");
    when(secretsFactory.from("_1")).thenReturn(secret);
    EmailConfigurationData entity = new EmailConfigurationData();
    entity.setEnabled(true);
    entity.setHost("localhost");
    entity.setPort(25);
    entity.setUsername("email_user");
    entity.setPassword(secret);
    entity.setFromAddress("foo@example.com");
    entity.setSubjectPrefix("PREFIX: ");
    entity.setStartTlsEnabled(true);
    entity.setStartTlsRequired(false);
    entity.setSslOnConnectEnabled(true);
    entity.setSslCheckServerIdentityEnabled(false);
    entity.setNexusTrustStoreEnabled(true);
    return entity;
  }

  /**
   * Tests that concurrent reads of email configuration from multiple virtual threads
   * return consistent results.
   */
  @Test
  public void testConcurrentReadsWithVirtualThreads() throws Exception {
    // Set up initial configuration
    EmailConfigurationData entity = createEmailConfigurationData(secret);
    dao.set(entity);
    
    // Create a latch to synchronize all threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    
    // Track any inconsistencies
    AtomicBoolean foundInconsistency = new AtomicBoolean(false);
    
    // Launch multiple virtual threads to read the configuration
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Read the configuration
          EmailConfiguration config = dao.get().orElse(null);
          
          // Verify configuration is consistent
          if (config == null || 
              !config.isEnabled() ||
              !"localhost".equals(config.getHost()) ||
              config.getPort() != 25 ||
              !"email_user".equals(config.getUsername()) ||
              config.getPassword() != secret ||
              !"foo@example.com".equals(config.getFromAddress()) ||
              !"PREFIX: ".equals(config.getSubjectPrefix()) ||
              !config.isStartTlsEnabled() ||
              config.isStartTlsRequired() ||
              !config.isSslOnConnectEnabled() ||
              config.isSslCheckServerIdentityEnabled() ||
              !config.isNexusTrustStoreEnabled()) {
            foundInconsistency.set(true);
          }
        }
        catch (Exception e) {
          foundInconsistency.set(true);
          logger.error("Error in virtual thread", e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertThat("All virtual threads should complete in time",
        completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
    
    // Verify no inconsistencies were found
    assertThat("No inconsistencies should be found in concurrent reads", 
        foundInconsistency.get(), is(false));
  }

  /**
   * Tests that concurrent updates to email configuration from multiple virtual threads
   * maintain data consistency.
   */
  @Test
  public void testConcurrentUpdatesWithVirtualThreads() throws Exception {
    // Set up initial configuration
    EmailConfigurationData entity = createEmailConfigurationData(secret);
    dao.set(entity);
    
    // Create a latch to synchronize all threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    
    // Track update success count
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Launch multiple virtual threads to update the configuration
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      final int threadId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Create a unique secret for this thread
          Secret threadSecret = mock(Secret.class);
          String secretId = "_thread" + threadId;
          when(threadSecret.getId()).thenReturn(secretId);
          when(secretsFactory.from(secretId)).thenReturn(threadSecret);
          
          // Get current configuration
          EmailConfigurationData config = dao.get().orElse(null);
          if (config != null) {
            // Update with thread-specific values
            config.setHost("host" + threadId);
            config.setPort(2000 + threadId);
            config.setUsername("user" + threadId);
            config.setPassword(threadSecret);
            config.setFromAddress("thread" + threadId + "@example.com");
            
            // Attempt to save the update
            dao.set(config);
            successCount.incrementAndGet();
          }
        }
        catch (Exception e) {
          logger.error("Error in virtual thread " + threadId, e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertThat("All virtual threads should complete in time",
        completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
    
    // At least one update should succeed
    assertThat("At least one update should succeed", 
        successCount.get() > 0, is(true));
    
    // Verify the final configuration exists and is valid
    EmailConfiguration finalConfig = dao.get().orElse(null);
    assertThat(finalConfig, is(notNullValue()));
    assertThat(finalConfig.getHost().startsWith("host"), is(true));
    assertThat(finalConfig.getPort() >= 2000, is(true));
    assertThat(finalConfig.getUsername().startsWith("user"), is(true));
    assertThat(finalConfig.getFromAddress().contains("@example.com"), is(true));
  }

  /**
   * Tests that configuration copy operations maintain consistency when accessed
   * from multiple virtual threads simultaneously.
   */
  @Test
  public void testConcurrentCopyOperationsWithVirtualThreads() throws Exception {
    // Set up initial configuration
    EmailConfigurationData entity = createEmailConfigurationData(secret);
    dao.set(entity);
    
    // Create a list to hold all the futures
    List<CompletableFuture<EmailConfiguration>> futures = new ArrayList<>();
    
    // Launch multiple virtual threads to copy the configuration
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      CompletableFuture<EmailConfiguration> future = CompletableFuture.supplyAsync(() -> {
        // Get the configuration
        EmailConfiguration config = dao.get().orElse(null);
        if (config != null) {
          // Create a copy
          return config.copy();
        }
        return null;
      }, virtualThreadExecutor);
      
      futures.add(future);
    }
    
    // Wait for all futures to complete and verify the copies
    for (CompletableFuture<EmailConfiguration> future : futures) {
      EmailConfiguration copy = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify the copy is valid
      assertThat(copy, is(notNullValue()));
      assertThat(copy.isEnabled(), is(true));
      assertThat(copy.getHost(), is("localhost"));
      assertThat(copy.getPort(), is(25));
      assertThat(copy.getUsername(), is("email_user"));
      assertThat(copy.getPassword(), is(secret));
      assertThat(copy.getFromAddress(), is("foo@example.com"));
      assertThat(copy.getSubjectPrefix(), is("PREFIX: "));
      assertThat(copy.isStartTlsEnabled(), is(true));
      assertThat(copy.isStartTlsRequired(), is(false));
      assertThat(copy.isSslOnConnectEnabled(), is(true));
      assertThat(copy.isSslCheckServerIdentityEnabled(), is(false));
      assertThat(copy.isNexusTrustStoreEnabled(), is(true));
    }
  }

  /**
   * Tests that password secrets are handled correctly when accessed concurrently
   * from multiple virtual threads.
   */
  @Test
  public void testConcurrentSecretHandlingWithVirtualThreads() throws Exception {
    // Set up initial configuration with a secret
    EmailConfigurationData entity = createEmailConfigurationData(secret);
    dao.set(entity);
    
    // Create a latch to synchronize all threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    
    // Track any inconsistencies with secret handling
    AtomicBoolean secretInconsistency = new AtomicBoolean(false);
    
    // Create a collection of different secrets for testing
    List<Secret> secrets = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      Secret threadSecret = mock(Secret.class);
      String secretId = "_secret" + i;
      when(threadSecret.getId()).thenReturn(secretId);
      when(secretsFactory.from(secretId)).thenReturn(threadSecret);
      secrets.add(threadSecret);
    }
    
    // Launch multiple virtual threads to update and read secrets
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      final int threadId = i;
      final Secret threadSecret = secrets.get(i);
      
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Get current configuration
          EmailConfigurationData config = dao.get().orElse(null);
          if (config != null) {
            // Update the password with this thread's secret
            config.setPassword(threadSecret);
            dao.set(config);
            
            // Read it back immediately
            EmailConfiguration readConfig = dao.get().orElse(null);
            
            // Verify the secret is handled correctly
            if (readConfig == null || readConfig.getPassword() == null) {
              secretInconsistency.set(true);
            }
          }
        }
        catch (Exception e) {
          secretInconsistency.set(true);
          logger.error("Error in virtual thread " + threadId, e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertThat("All virtual threads should complete in time",
        completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
    
    // Verify no inconsistencies were found with secret handling
    assertThat("No inconsistencies should be found in secret handling", 
        secretInconsistency.get(), is(false));
    
    // Verify the final configuration has a valid secret
    EmailConfiguration finalConfig = dao.get().orElse(null);
    assertThat(finalConfig, is(notNullValue()));
    assertThat(finalConfig.getPassword(), is(notNullValue()));
  }

  /**
   * Tests that configuration state visibility is consistent across virtual thread boundaries.
   */
  @Test
  public void testConfigurationStateVisibilityAcrossVirtualThreads() throws Exception {
    // Set up initial configuration
    EmailConfigurationData entity = createEmailConfigurationData(secret);
    dao.set(entity);
    
    // Reference to hold the configuration from the first thread
    AtomicReference<EmailConfiguration> firstThreadConfig = new AtomicReference<>();
    
    // Create a latch to ensure the first thread completes before the second starts
    CountDownLatch firstThreadLatch = new CountDownLatch(1);
    
    // First virtual thread reads the configuration
    CompletableFuture<Void> firstThread = CompletableFuture.runAsync(() -> {
      try {
        // Get the configuration
        EmailConfiguration config = dao.get().orElse(null);
        firstThreadConfig.set(config);
        
        // Signal that the first thread has completed
        firstThreadLatch.countDown();
      }
      catch (Exception e) {
        logger.error("Error in first virtual thread", e);
      }
    }, virtualThreadExecutor);
    
    // Wait for the first thread to complete
    assertThat("First virtual thread should complete in time",
        firstThreadLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
    
    // Second virtual thread updates the configuration
    CompletableFuture<Void> secondThread = CompletableFuture.runAsync(() -> {
      try {
        // Get the configuration
        EmailConfigurationData config = dao.get().orElse(null);
        if (config != null) {
          // Update with new values
          config.setHost("updatedhost");
          config.setPort(587);
          config.setUsername("updateduser");
          
          // Save the update
          dao.set(config);
        }
      }
      catch (Exception e) {
        logger.error("Error in second virtual thread", e);
      }
    }, virtualThreadExecutor);
    
    // Wait for the second thread to complete
    secondThread.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Third virtual thread reads the updated configuration
    CompletableFuture<EmailConfiguration> thirdThread = CompletableFuture.supplyAsync(() -> {
      // Get the configuration
      return dao.get().orElse(null);
    }, virtualThreadExecutor);
    
    // Get the configuration from the third thread
    EmailConfiguration updatedConfig = thirdThread.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify the first thread saw the initial configuration
    EmailConfiguration initialConfig = firstThreadConfig.get();
    assertThat(initialConfig, is(notNullValue()));
    assertThat(initialConfig.getHost(), is("localhost"));
    assertThat(initialConfig.getPort(), is(25));
    assertThat(initialConfig.getUsername(), is("email_user"));
    
    // Verify the third thread saw the updated configuration
    assertThat(updatedConfig, is(notNullValue()));
    assertThat(updatedConfig.getHost(), is("updatedhost"));
    assertThat(updatedConfig.getPort(), is(587));
    assertThat(updatedConfig.getUsername(), is("updateduser"));
  }

  /**
   * Tests that deleting the configuration is thread-safe when accessed from virtual threads.
   */
  @Test
  public void testConcurrentDeleteWithVirtualThreads() throws Exception {
    // Set up initial configuration
    EmailConfigurationData entity = createEmailConfigurationData(secret);
    dao.set(entity);
    
    // Create a latch to synchronize all threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    
    // Track delete success count
    AtomicInteger deleteSuccessCount = new AtomicInteger(0);
    
    // Launch multiple virtual threads to try to delete the configuration
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Try to delete the configuration
          dao.clear();
          deleteSuccessCount.incrementAndGet();
        }
        catch (Exception e) {
          logger.error("Error in virtual thread", e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertThat("All virtual threads should complete in time",
        completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
    
    // Verify the configuration was deleted
    EmailConfiguration finalConfig = dao.get().orElse(null);
    assertThat(finalConfig, is(nullValue()));
    
    // At least one delete should have succeeded
    assertThat("At least one delete should succeed", 
        deleteSuccessCount.get() > 0, is(true));
  }
}