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
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.crypto.secrets.Secret;
import org.sonatype.nexus.crypto.secrets.SecretsFactory;
import org.sonatype.nexus.email.EmailConfiguration;
import org.sonatype.nexus.internal.email.EmailConfigurationDAO;
import org.sonatype.nexus.internal.email.EmailConfigurationData;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EmailConfiguration} operations under Java 21 virtual threads.
 * 
 * This test class validates that email configuration operations (retrieval, updating, copying)
 * work correctly when accessed concurrently by multiple virtual threads.
 */
public class EmailConfigurationVirtualThreadTest
    extends TestSupport
{
  @Mock
  private EmailConfigurationDAO dao;
  
  @Mock
  private SecretsFactory secretsFactory;
  
  @Mock
  private Secret secret1;
  
  @Mock
  private Secret secret2;
  
  private EmailConfigurationData config;
  
  @Before
  public void setup() {
    // Setup a basic email configuration
    config = new EmailConfigurationData();
    config.setEnabled(true);
    config.setHost("localhost");
    config.setPort(25);
    config.setUsername("email_user");
    config.setPassword(secret1);
    config.setFromAddress("test@example.com");
    config.setSubjectPrefix("TEST: ");
    config.setStartTlsEnabled(true);
    config.setStartTlsRequired(false);
    config.setSslOnConnectEnabled(true);
    config.setSslCheckServerIdentityEnabled(false);
    config.setNexusTrustStoreEnabled(true);
    
    // Setup secrets
    when(secret1.getId()).thenReturn("_1");
    when(secretsFactory.from("_1")).thenReturn(secret1);
    when(secret2.getId()).thenReturn("_2");
    when(secretsFactory.from("_2")).thenReturn(secret2);
    
    // Setup DAO to return our config
    when(dao.get()).thenReturn(Optional.of(config));
  }
  
  @After
  public void tearDown() {
    // No specific teardown needed
  }
  
  /**
   * Tests concurrent retrieval of email configuration using virtual threads.
   * 
   * This test verifies that multiple virtual threads can retrieve the email configuration
   * simultaneously without causing inconsistencies or race conditions.
   */
  @Test
  public void testConcurrentConfigurationRetrieval() throws Exception {
    int threadCount = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicBoolean hasErrors = new AtomicBoolean(false);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to retrieve configuration concurrently
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            
            // Retrieve configuration
            EmailConfiguration config = dao.get().orElse(null);
            
            // Verify configuration is valid
            if (config == null || !config.isEnabled() || !"localhost".equals(config.getHost()) ||
                config.getPort() != 25 || !"email_user".equals(config.getUsername()) ||
                config.getPassword() != secret1 || !"test@example.com".equals(config.getFromAddress())) {
              hasErrors.set(true);
            }
          }
          catch (Exception e) {
            log.error("Error in virtual thread", e);
            hasErrors.set(true);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      completionLatch.await(5, TimeUnit.SECONDS);
    }
    
    assertThat("No errors should occur during concurrent configuration retrieval", hasErrors.get(), is(false));
  }
  
  /**
   * Tests concurrent updates to email configuration using virtual threads.
   * 
   * This test verifies that multiple virtual threads can update the email configuration
   * without causing data corruption or inconsistent state.
   */
  @Test
  public void testConcurrentConfigurationUpdates() throws Exception {
    int threadCount = 50;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicReference<EmailConfigurationData> lastConfig = new AtomicReference<>(config);
    AtomicBoolean hasErrors = new AtomicBoolean(false);
    
    // Setup DAO to update our reference when set is called
    Mockito.doAnswer(invocation -> {
      EmailConfigurationData newConfig = invocation.getArgument(0);
      lastConfig.set(newConfig);
      return null;
    }).when(dao).set(Mockito.any(EmailConfigurationData.class));
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to update configuration concurrently
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            
            // Get current config
            EmailConfigurationData currentConfig = dao.get().orElse(null);
            if (currentConfig == null) {
              hasErrors.set(true);
              return;
            }
            
            // Create a modified copy
            EmailConfigurationData updatedConfig = new EmailConfigurationData();
            updatedConfig.setEnabled(currentConfig.isEnabled());
            updatedConfig.setHost("host-" + index);
            updatedConfig.setPort(currentConfig.getPort() + index % 10);
            updatedConfig.setUsername(currentConfig.getUsername() + "-" + index);
            updatedConfig.setPassword(index % 2 == 0 ? secret1 : secret2); // Alternate between secrets
            updatedConfig.setFromAddress("user" + index + "@example.com");
            updatedConfig.setSubjectPrefix("PREFIX-" + index + ": ");
            updatedConfig.setStartTlsEnabled(index % 2 == 0);
            updatedConfig.setStartTlsRequired(index % 3 == 0);
            updatedConfig.setSslOnConnectEnabled(index % 2 != 0);
            updatedConfig.setSslCheckServerIdentityEnabled(index % 3 != 0);
            updatedConfig.setNexusTrustStoreEnabled(index % 5 == 0);
            
            // Update configuration
            dao.set(updatedConfig);
          }
          catch (Exception e) {
            log.error("Error in virtual thread", e);
            hasErrors.set(true);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      completionLatch.await(5, TimeUnit.SECONDS);
    }
    
    // Verify final configuration state
    EmailConfigurationData finalConfig = lastConfig.get();
    assertThat("Final configuration should not be null", finalConfig, is(notNullValue()));
    assertThat("No errors should occur during concurrent configuration updates", hasErrors.get(), is(false));
  }
  
  /**
   * Tests concurrent access to password secrets in email configuration using virtual threads.
   * 
   * This test verifies that encrypted credentials (secrets) are handled properly when
   * accessed by multiple virtual threads simultaneously.
   */
  @Test
  public void testConcurrentSecretAccess() throws Exception {
    int threadCount = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    List<Secret> accessedSecrets = new ArrayList<>();
    Object lock = new Object();
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to access secrets concurrently
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            
            // Get current config
            EmailConfigurationData currentConfig = dao.get().orElse(null);
            if (currentConfig != null) {
              // Access the secret
              Secret secret = currentConfig.getPassword();
              
              // Record which secret was accessed (thread-safe)
              synchronized (lock) {
                accessedSecrets.add(secret);
              }
              
              // If index is even, update the secret
              if (index % 2 == 0) {
                currentConfig.setPassword(secret2);
                dao.set(currentConfig);
              }
            }
          }
          catch (Exception e) {
            log.error("Error in virtual thread", e);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      completionLatch.await(5, TimeUnit.SECONDS);
    }
    
    // Verify that secrets were accessed correctly
    assertThat("Secrets should have been accessed", accessedSecrets.size(), is(threadCount));
  }
  
  /**
   * Tests configuration copy operations across virtual threads.
   * 
   * This test verifies that configuration copy operations maintain consistency
   * when performed across multiple virtual threads.
   */
  @Test
  public void testConfigurationCopyAcrossVirtualThreads() throws Exception {
    int threadCount = 20;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicBoolean hasErrors = new AtomicBoolean(false);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to copy configuration concurrently
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            
            // Get current config
            EmailConfigurationData sourceConfig = dao.get().orElse(null);
            if (sourceConfig == null) {
              hasErrors.set(true);
              return;
            }
            
            // Create a copy
            EmailConfigurationData copy = new EmailConfigurationData();
            copy.setEnabled(sourceConfig.isEnabled());
            copy.setHost(sourceConfig.getHost());
            copy.setPort(sourceConfig.getPort());
            copy.setUsername(sourceConfig.getUsername());
            copy.setPassword(sourceConfig.getPassword()); // Copy the secret reference
            copy.setFromAddress(sourceConfig.getFromAddress());
            copy.setSubjectPrefix(sourceConfig.getSubjectPrefix());
            copy.setStartTlsEnabled(sourceConfig.isStartTlsEnabled());
            copy.setStartTlsRequired(sourceConfig.isStartTlsRequired());
            copy.setSslOnConnectEnabled(sourceConfig.isSslOnConnectEnabled());
            copy.setSslCheckServerIdentityEnabled(sourceConfig.isSslCheckServerIdentityEnabled());
            copy.setNexusTrustStoreEnabled(sourceConfig.isNexusTrustStoreEnabled());
            
            // Verify the copy is consistent with the source
            if (!copy.isEnabled() == sourceConfig.isEnabled() ||
                !copy.getHost().equals(sourceConfig.getHost()) ||
                copy.getPort() != sourceConfig.getPort() ||
                !copy.getUsername().equals(sourceConfig.getUsername()) ||
                copy.getPassword() != sourceConfig.getPassword() ||
                !copy.getFromAddress().equals(sourceConfig.getFromAddress()) ||
                !copy.getSubjectPrefix().equals(sourceConfig.getSubjectPrefix()) ||
                copy.isStartTlsEnabled() != sourceConfig.isStartTlsEnabled() ||
                copy.isStartTlsRequired() != sourceConfig.isStartTlsRequired() ||
                copy.isSslOnConnectEnabled() != sourceConfig.isSslOnConnectEnabled() ||
                copy.isSslCheckServerIdentityEnabled() != sourceConfig.isSslCheckServerIdentityEnabled() ||
                copy.isNexusTrustStoreEnabled() != sourceConfig.isNexusTrustStoreEnabled()) {
              hasErrors.set(true);
            }
          }
          catch (Exception e) {
            log.error("Error in virtual thread", e);
            hasErrors.set(true);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      completionLatch.await(5, TimeUnit.SECONDS);
    }
    
    assertThat("No errors should occur during configuration copy operations", hasErrors.get(), is(false));
  }
  
  /**
   * Tests configuration state visibility across virtual thread boundaries.
   * 
   * This test verifies that changes to the configuration state are properly visible
   * across different virtual threads.
   */
  @Test
  public void testConfigurationStateVisibilityAcrossThreads() throws Exception {
    CountDownLatch updateLatch = new CountDownLatch(1);
    CountDownLatch verifyLatch = new CountDownLatch(1);
    AtomicBoolean updateComplete = new AtomicBoolean(false);
    AtomicBoolean verifyComplete = new AtomicBoolean(false);
    AtomicBoolean stateVisibilityCorrect = new AtomicBoolean(false);
    
    // Setup a reference to track the updated configuration
    AtomicReference<EmailConfigurationData> updatedConfigRef = new AtomicReference<>();
    
    // Setup DAO to update our reference when set is called
    Mockito.doAnswer(invocation -> {
      EmailConfigurationData newConfig = invocation.getArgument(0);
      updatedConfigRef.set(newConfig);
      when(dao.get()).thenReturn(Optional.of(newConfig));
      return null;
    }).when(dao).set(Mockito.any(EmailConfigurationData.class));
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Thread 1: Updates the configuration
      executor.submit(() -> {
        try {
          // Get current config
          EmailConfigurationData currentConfig = dao.get().orElse(null);
          if (currentConfig != null) {
            // Create an updated configuration
            EmailConfigurationData updatedConfig = new EmailConfigurationData();
            updatedConfig.setEnabled(true);
            updatedConfig.setHost("updated-host");
            updatedConfig.setPort(587);
            updatedConfig.setUsername("updated-user");
            updatedConfig.setPassword(secret2); // Use different secret
            updatedConfig.setFromAddress("updated@example.com");
            updatedConfig.setSubjectPrefix("UPDATED: ");
            updatedConfig.setStartTlsEnabled(false);
            updatedConfig.setStartTlsRequired(true);
            updatedConfig.setSslOnConnectEnabled(false);
            updatedConfig.setSslCheckServerIdentityEnabled(true);
            updatedConfig.setNexusTrustStoreEnabled(false);
            
            // Update the configuration
            dao.set(updatedConfig);
            updateComplete.set(true);
          }
        }
        catch (Exception e) {
          log.error("Error in update thread", e);
        }
        finally {
          updateLatch.countDown(); // Signal that update is complete
        }
      });
      
      // Thread 2: Verifies the updated configuration is visible
      executor.submit(() -> {
        try {
          // Wait for Thread 1 to complete the update
          updateLatch.await(2, TimeUnit.SECONDS);
          
          // Get the configuration after update
          EmailConfigurationData config = dao.get().orElse(null);
          
          // Verify the configuration reflects the updates
          if (config != null && 
              config.isEnabled() &&
              "updated-host".equals(config.getHost()) &&
              config.getPort() == 587 &&
              "updated-user".equals(config.getUsername()) &&
              config.getPassword() == secret2 &&
              "updated@example.com".equals(config.getFromAddress()) &&
              "UPDATED: ".equals(config.getSubjectPrefix()) &&
              !config.isStartTlsEnabled() &&
              config.isStartTlsRequired() &&
              !config.isSslOnConnectEnabled() &&
              config.isSslCheckServerIdentityEnabled() &&
              !config.isNexusTrustStoreEnabled()) {
            stateVisibilityCorrect.set(true);
          }
          
          verifyComplete.set(true);
        }
        catch (Exception e) {
          log.error("Error in verify thread", e);
        }
        finally {
          verifyLatch.countDown(); // Signal that verification is complete
        }
      });
      
      // Wait for both threads to complete
      verifyLatch.await(5, TimeUnit.SECONDS);
    }
    
    // Verify results
    assertThat("Update operation should complete", updateComplete.get(), is(true));
    assertThat("Verify operation should complete", verifyComplete.get(), is(true));
    assertThat("Configuration state should be correctly visible across threads", stateVisibilityCorrect.get(), is(true));
  }
}