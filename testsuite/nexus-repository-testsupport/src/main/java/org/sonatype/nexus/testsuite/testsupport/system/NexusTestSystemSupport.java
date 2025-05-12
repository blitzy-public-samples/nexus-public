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
package org.sonatype.nexus.testsuite.testsupport.system;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;
import javax.inject.Provider;

import org.sonatype.nexus.testsuite.helpers.ComponentAssetTestHelper;
import org.sonatype.nexus.testsuite.testsupport.fixtures.BlobStoreRule;
import org.sonatype.nexus.testsuite.testsupport.fixtures.CapabilitiesRule;
import org.sonatype.nexus.testsuite.testsupport.fixtures.SecurityRealmRule;
import org.sonatype.nexus.testsuite.testsupport.fixtures.SecurityRule;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base support class for Nexus test systems that provides access to various test fixtures and utilities.
 * <p>
 * This class has been updated to leverage Java 21 features, particularly virtual threads for improved
 * concurrency and I/O-bound operations in test fixtures. It has also been migrated from JUnit 4's
 * ExternalResource to JUnit Jupiter's extension model.
 * <p>
 * <strong>Migration Notes:</strong>
 * <ul>
 *   <li>This class now implements {@link BeforeAllCallback} and {@link AfterAllCallback} instead of extending
 *       JUnit 4's ExternalResource</li>
 *   <li>For JUnit Jupiter tests, use {@link NexusTestSystemExtension} with the {@code @ExtendWith} annotation</li>
 *   <li>For backward compatibility with JUnit 4 tests, {@link NexusTestSystemRule} is still available but deprecated</li>
 *   <li>Virtual threads are used for concurrent operations where appropriate, improving test performance</li>
 * </ul>
 *
 * @param <R> the repository test system type
 * @param <C> the capabilities rule type
 * @since Java 21
 */
public abstract class NexusTestSystemSupport<R extends RepositoryTestSystem, C extends CapabilitiesRule>
    implements BeforeAllCallback, AfterAllCallback
{
  protected final Logger log = LoggerFactory.getLogger(getClass());

  private final R repositories;

  private final C capabilities;

  @Inject
  private BlobStoreRule blobstores;

  @Inject
  private CleanupTestSystem cleanup;

  @Inject
  private ComponentAssetTestHelper components;

  @Inject
  private LogTestSystem logs;

  @Inject
  private RestTestHelper restTestHelper;

  @Inject
  private SearchTestSystem searchTestSystem;

  @Inject
  private SecurityRule security;

  @Inject
  private TaskTestSystem tasks;

  @Inject
  private SecurityRealmRule securityRealms;

  @Inject
  private ServerTestSystem servers;

  @Inject
  private ConfigTestSystem config;

  protected NexusTestSystemSupport(
      final R repositories,
      final C capabilities)
  {
    this.repositories = repositories;
    this.capabilities = capabilities;
  }

  /**
   * Creates a virtual thread executor service for I/O-bound test operations.
   * <p>
   * Virtual threads are lightweight threads in Java 21 that are managed by the JVM rather than the OS,
   * making them ideal for I/O-bound operations in tests. They allow for higher concurrency with lower
   * resource usage compared to platform threads.
   *
   * @return an executor service that creates a new virtual thread for each task
   * @since Java 21
   */
  protected ExecutorService createVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
  
  /**
   * Creates a named virtual thread executor service for I/O-bound test operations.
   * <p>
   * This method creates virtual threads with a specific naming pattern to make debugging easier.
   * Each virtual thread will be named according to the pattern: "[prefix]-[counter]"
   *
   * @param prefix the prefix to use for thread names
   * @return an executor service that creates named virtual threads
   * @since Java 21
   */
  protected ExecutorService createNamedVirtualThreadExecutor(String prefix) {
    AtomicInteger counter = new AtomicInteger();
    ThreadFactory factory = Thread.ofVirtual()
        .name(prefix, counter::getAndIncrement)
        .factory();
    return Executors.newThreadPerTaskExecutor(factory);
  }
  
  /**
   * Detects if the current JVM supports virtual threads.
   * <p>
   * This method can be used to conditionally enable virtual thread features
   * based on the runtime environment.
   *
   * @return true if virtual threads are supported, false otherwise
   * @since Java 21
   */
  protected boolean supportsVirtualThreads() {
    try {
      // Try to create a virtual thread as a test
      Thread vThread = Thread.ofVirtual().start(() -> {});
      vThread.join();
      return true;
    } catch (Exception e) {
      log.debug("Virtual threads not supported in this environment", e);
      return false;
    }
  }
  
  /**
   * Enables thread pinning detection for virtual threads.
   * <p>
   * Thread pinning occurs when a virtual thread cannot be unmounted from its carrier thread,
   * typically when executing synchronized blocks or native methods. This can reduce the
   * performance benefits of virtual threads.
   * <p>
   * This method enables JVM flags to detect and log thread pinning events, which is useful
   * for diagnosing performance issues in tests.
   *
   * @since Java 21
   */
  protected void enableThreadPinningDetection() {
    if (supportsVirtualThreads()) {
      try {
        System.setProperty("jdk.tracePinnedThreads", "full");
        log.info("Enabled virtual thread pinning detection");
      } catch (SecurityException e) {
        log.warn("Unable to enable thread pinning detection due to security restrictions", e);
      }
    }
  }

  /**
   * Test fixture for managing BlobStores
   */
  public BlobStoreRule blobStores() {
    return blobstores;
  }

  /**
   * Test fixture for managing Capabilities
   */
  public C capabilities() {
    return capabilities;
  }

  /**
   * Test fixture for managing Cleanup Policies
   */
  public CleanupTestSystem cleanup() {
    return cleanup;
  }

  /**
   * Test helpers for verifying Components and Assets in database agnostic ways.
   */
  public ComponentAssetTestHelper components() {
    return components;
  }

  /**
   * Test fixture of managing Logger settings
   */
  public LogTestSystem logs() {
    return logs;
  }

  /**
   * Test fixture of managing Repositories
   */
  public R repositories() {
    return repositories;
  }

  /**
   * Test helpers for making HTTP/REST requests to Nexus
   */
  public RestTestHelper rest() {
    return restTestHelper;
  }

  /**
   * Test helpers for verifying ElasticSearch
   */
  public SearchTestSystem search() {
    return searchTestSystem;
  }

  /**
   * Test fixture for managing the security subsystem (privileges, roles, users, etc.)
   */
  public SecurityRule security() {
    return security;
  }

  /**
   * Test fixture for managing the security realms
   */
  public SecurityRealmRule securityRealms() {
    return securityRealms;
  }

  /**
   * Test fixture for managing the tasks
   */
  public TaskTestSystem tasks() {
    return tasks;
  }

  public ServerTestSystem servers() {
    return servers;
  }

  public ConfigTestSystem config() {
    return config;
  }

  /**
   * Wait for nexus to be done active processing of any running tasks and any elasticsearch updates.
   * <p>
   * If you have a test that is acting 'flaky' not having this after running a task or adding/removing
   * components is likely the cause.
   * <p>
   * This implementation uses virtual threads to efficiently handle the waiting for both tasks and search
   * operations concurrently, improving test performance especially for I/O-bound operations.
   */
  public void waitForCalmPeriod() {
    if (supportsVirtualThreads()) {
      waitForCalmPeriodWithVirtualThreads();
    } else {
      // Fallback to sequential execution for environments without virtual thread support
      tasks.waitForCalmPeriod();
      searchTestSystem.waitForSearch();
    }
  }
  
  /**
   * Implementation of waitForCalmPeriod using virtual threads for concurrent execution.
   * <p>
   * This method uses Java 21 virtual threads to parallelize the waiting operations,
   * which is particularly beneficial for I/O-bound operations.
   *
   * @since Java 21
   */
  protected void waitForCalmPeriodWithVirtualThreads() {
    try (ExecutorService executor = createNamedVirtualThreadExecutor("nexus-calm-wait")) {
      CompletableFuture<Void> tasksFuture = CompletableFuture.runAsync(() -> tasks.waitForCalmPeriod(), executor)
          .orTimeout(Duration.ofMinutes(5));
      
      CompletableFuture<Void> searchFuture = CompletableFuture.runAsync(() -> searchTestSystem.waitForSearch(), executor)
          .orTimeout(Duration.ofMinutes(5));
      
      // Wait for both operations to complete
      try {
        CompletableFuture.allOf(tasksFuture, searchFuture).join();
      } catch (Exception e) {
        log.warn("Exception while waiting for calm period", e);
        // If virtual thread execution fails, fall back to sequential execution
        tasks.waitForCalmPeriod();
        searchTestSystem.waitForSearch();
      }
    }
  }

  @Override
  public void beforeAll(ExtensionContext context) throws Exception {
    if (supportsVirtualThreads()) {
      log.info("Using Java 21 virtual threads for test orchestration");
      beforeAllWithVirtualThreads(context);
    } else {
      log.info("Using sequential execution for test orchestration (virtual threads not available)");
      securityRealms.before();
      repositories.before();
      config.before();
    }
  }
  
  /**
   * Implementation of beforeAll using virtual threads for concurrent setup operations.
   * <p>
   * This method uses Java 21 virtual threads to parallelize setup operations where possible,
   * while respecting dependencies between operations.
   *
   * @param context the extension context
   * @throws Exception if any setup operation fails
   * @since Java 21
   */
  protected void beforeAllWithVirtualThreads(ExtensionContext context) throws Exception {
    // Security realms must be set up first
    securityRealms.before();
    
    // Then repositories and config can be set up in parallel
    try (ExecutorService executor = createNamedVirtualThreadExecutor("nexus-setup")) {
      CompletableFuture<Void> repositoriesFuture = CompletableFuture.runAsync(() -> {
        try {
          repositories.before();
        } catch (Throwable e) {
          throw new RuntimeException("Failed to set up repositories", e);
        }
      }, executor);
      
      CompletableFuture<Void> configFuture = CompletableFuture.runAsync(() -> {
        try {
          config.before();
        } catch (Throwable e) {
          throw new RuntimeException("Failed to set up config", e);
        }
      }, executor);
      
      // Wait for both operations to complete
      CompletableFuture.allOf(repositoriesFuture, configFuture).join();
    } catch (Exception e) {
      // If there was an exception, unwrap it to get the original cause
      Throwable cause = e.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      } else {
        throw e;
      }
    }
  }

  @Override
  public void afterAll(ExtensionContext context) {
    log.info("Cleaning up test entities");
    try (ExecutorService executor = createVirtualThreadExecutor()) {
      // Use virtual threads for cleanup operations to improve performance
      // Create all futures first to start operations in parallel
      CompletableFuture<Void> serversFuture = CompletableFuture.runAsync(() -> {
        try {
          servers.after();
        } catch (Exception e) {
          log.warn("Error during servers cleanup", e);
        }
      }, executor);
      
      CompletableFuture<Void> cleanupFuture = CompletableFuture.runAsync(() -> {
        try {
          cleanup.after();
        } catch (Exception e) {
          log.warn("Error during cleanup", e);
        }
      }, executor);
      
      CompletableFuture<Void> capabilitiesFuture = CompletableFuture.runAsync(() -> {
        try {
          capabilities.after();
        } catch (Exception e) {
          log.warn("Error during capabilities cleanup", e);
        }
      }, executor);
      
      CompletableFuture<Void> tasksFuture = CompletableFuture.runAsync(() -> {
        try {
          tasks.after();
        } catch (Exception e) {
          log.warn("Error during tasks cleanup", e);
        }
      }, executor);
      
      CompletableFuture<Void> repositoriesFuture = CompletableFuture.runAsync(() -> {
        try {
          repositories.after();
        } catch (Exception e) {
          log.warn("Error during repositories cleanup", e);
        }
      }, executor);
      
      CompletableFuture<Void> blobstoresFuture = CompletableFuture.runAsync(() -> {
        try {
          blobstores.after();
        } catch (Exception e) {
          log.warn("Error during blobstores cleanup", e);
        }
      }, executor);
      
      CompletableFuture<Void> securityFuture = CompletableFuture.runAsync(() -> {
        try {
          security.after();
        } catch (Exception e) {
          log.warn("Error during security cleanup", e);
        }
      }, executor);
      
      CompletableFuture<Void> securityRealmsFuture = CompletableFuture.runAsync(() -> {
        try {
          securityRealms.after();
        } catch (Exception e) {
          log.warn("Error during security realms cleanup", e);
        }
      }, executor);
      
      CompletableFuture<Void> configFuture = CompletableFuture.runAsync(() -> {
        try {
          config.after();
        } catch (Exception e) {
          log.warn("Error during config cleanup", e);
        }
      }, executor);
      
      // Wait for all cleanup operations to complete
      CompletableFuture.allOf(
          serversFuture, cleanupFuture, capabilitiesFuture, tasksFuture,
          repositoriesFuture, blobstoresFuture, securityFuture, securityRealmsFuture, configFuture
      ).join();
    }
  }

  /**
   * JUnit Jupiter extension that delegates to a NexusTestSystemSupport instance.
   * <p>
   * This class replaces the JUnit 4 ExternalResource-based rule with a JUnit Jupiter extension
   * that provides the same lifecycle behavior. It should be used with the {@code @ExtendWith}
   * annotation in JUnit Jupiter tests.
   * <p>
   * Example usage:
   * <pre>
   * {@code
   * @ExtendWith(NexusTestSystemExtension.class)
   * class MyTest {
   *     @Inject
   *     private Provider<NexusTestSystemSupport<?, ?>> nexusProvider;
   *     
   *     // Test methods...
   * }
   * }
   * </pre>
   *
   * @since Java 21
   */
  public static class NexusTestSystemExtension
      implements BeforeAllCallback, AfterAllCallback
  {
    private final Provider<? extends NexusTestSystemSupport<?, ?>> nexus;

    public NexusTestSystemExtension(final Provider<? extends NexusTestSystemSupport<?, ?>> nexus) {
      this.nexus = nexus;
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
      nexus.get().beforeAll(context);
    }

    @Override
    public void afterAll(ExtensionContext context) {
      nexus.get().afterAll(context);
    }
  }
  
  /**
   * For backward compatibility with JUnit 4 tests that use the ExternalResource-based rule.
   * <p>
   * This class maintains compatibility with existing tests that use the JUnit 4 rule-based approach.
   * New tests should use the {@link NexusTestSystemExtension} with JUnit Jupiter's {@code @ExtendWith}
   * annotation instead.
   *
   * @deprecated Use {@link NexusTestSystemExtension} with JUnit Jupiter's {@code @ExtendWith} annotation instead
   */
  @Deprecated
  public static class NexusTestSystemRule
      implements BeforeAllCallback, AfterAllCallback
  {
    private final Provider<? extends NexusTestSystemSupport<?, ?>> nexus;

    public NexusTestSystemRule(final Provider<? extends NexusTestSystemSupport<?, ?>> nexus) {
      this.nexus = nexus;
    }
    
    /**
     * For backward compatibility with JUnit 4's ExternalResource.before()
     */
    public void before() throws Throwable {
      nexus.get().securityRealms.before();
      nexus.get().repositories.before();
      nexus.get().config.before();
    }

    /**
     * For backward compatibility with JUnit 4's ExternalResource.after()
     */
    public void after() {
      NexusTestSystemSupport<?, ?> system = nexus.get();
      system.log.info("Cleaning up test entities");
      system.servers.after();
      system.cleanup.after();
      system.capabilities.after();
      system.tasks.after();
      system.repositories.after();
      system.blobstores.after();
      system.security.after();
      system.securityRealms.after();
      system.config.after();
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
      try {
        before();
      } catch (Exception e) {
        throw e;
      } catch (Throwable t) {
        throw new Exception(t);
      }
    }

    @Override
    public void afterAll(ExtensionContext context) {
      after();
    }
  }
}