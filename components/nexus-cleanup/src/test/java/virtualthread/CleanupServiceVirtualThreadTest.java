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
package virtualthread;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.cleanup.content.search.CleanupBrowseServiceFactory;
import org.sonatype.nexus.cleanup.content.search.CleanupComponentBrowse;
import org.sonatype.nexus.cleanup.internal.content.service.CleanupServiceImpl;
import org.sonatype.nexus.cleanup.internal.method.CleanupMethod;
import org.sonatype.nexus.cleanup.storage.CleanupPolicy;
import org.sonatype.nexus.cleanup.storage.CleanupPolicyStorage;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.cleanup.CleanupFeatureCheck;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.task.DeletionProgress;
import org.sonatype.nexus.repository.types.GroupType;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.elasticsearch.search.SearchContextMissingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.google.common.collect.Sets.newLinkedHashSet;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static java.util.stream.Stream.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.hamcrest.MockitoHamcrest.argThat;
import static org.sonatype.nexus.cleanup.config.CleanupPolicyConstants.LAST_BLOB_UPDATED_KEY;
import static org.sonatype.nexus.cleanup.config.CleanupPolicyConstants.LAST_DOWNLOADED_KEY;
import static org.sonatype.nexus.testcommon.matchers.NexusMatchers.streamContains;

/**
 * Tests for {@link CleanupServiceImpl} using Java 21 Virtual Threads.
 * 
 * This test class validates that the CleanupServiceImpl works correctly with Virtual Threads,
 * comparing performance and behavior between platform threads and virtual threads for cleanup operations.
 */
@ExtendWith(MockitoExtension.class)
@Tag("java21")
public class CleanupServiceVirtualThreadTest
    extends TestSupport
{
  private static final String POLICY_1_NAME = "policy1";

  private static final String POLICY_2_NAME = "policy2";

  private static final int RETRY_LIMIT = 3;

  private static final int LARGE_REPOSITORY_COUNT = 100;
  
  private static final int COMPONENTS_PER_REPOSITORY = 1000;

  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private Repository repository1, repository2, repository3;

  @Mock
  private CleanupComponentBrowse browseService;

  @Mock
  private CleanupPolicyStorage cleanupPolicyStorage;

  @Mock
  private CleanupPolicy cleanupPolicy1, cleanupPolicy2;

  @Mock
  private CleanupMethod cleanupMethod;

  @Mock
  private Format format;

  @Mock
  private Type type;

  @Mock
  private BooleanSupplier cancelledCheck;

  @Mock
  private DeletionProgress deletionProgress;

  @Mock
  private CleanupBrowseServiceFactory cleanupBrowseFactory;

  @Mock
  private CleanupFeatureCheck cleanupFeatureCheck;

  private CleanupServiceImpl underTest;

  @BeforeEach
  public void setup() throws Exception {
    when(cleanupBrowseFactory.get(any())).thenReturn(browseService);

    underTest = new CleanupServiceImpl(repositoryManager, cleanupPolicyStorage, cleanupMethod,
        new GroupType(), RETRY_LIMIT, cleanupBrowseFactory, cleanupFeatureCheck);

    setupRepository(repository1, POLICY_1_NAME);
    setupRepository(repository2, POLICY_2_NAME);
    setupRepository(repository3, null);

    when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository1, repository2));

    when(cleanupPolicyStorage.get(POLICY_1_NAME)).thenReturn(cleanupPolicy1);
    when(cleanupPolicyStorage.get(POLICY_2_NAME)).thenReturn(cleanupPolicy2);

    when(cleanupPolicy1.getCriteria()).thenReturn(ImmutableMap.of(LAST_BLOB_UPDATED_KEY, "1"));
    when(cleanupPolicy2.getCriteria()).thenReturn(ImmutableMap.of(LAST_DOWNLOADED_KEY, "2"));

    when(browseService.browse(cleanupPolicy1, repository1)).thenReturn(ImmutableList.of(mock(FluentComponent.class), mock(FluentComponent.class)).stream());
    when(browseService.browse(cleanupPolicy2, repository2)).thenReturn(ImmutableList.of(mock(FluentComponent.class)).stream());

    when(cancelledCheck.getAsBoolean()).thenReturn(false);

    when(deletionProgress.isFailed()).thenReturn(false);
    when(cleanupMethod.run(any(), any(), any())).thenReturn(deletionProgress);

    when(repository1.getFormat()).thenReturn(format);
    when(repository2.getFormat()).thenReturn(format);
    when(repository3.getFormat()).thenReturn(format);
    when(format.getValue()).thenReturn("maven2");

    when(cleanupFeatureCheck.isRetainSupported(any())).thenReturn(true);
  }

  /**
   * Validates that the cleanup service works correctly with virtual threads.
   */
  @Test
  void basicCleanupWithVirtualThreads() {
    // Run cleanup with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> underTest.cleanup(cancelledCheck), executor);
      future.join(); // Wait for completion
    }

    // Verify the cleanup was performed correctly
    verify(cleanupMethod).run(eq(repository1), argThat(streamContains(any(FluentComponent.class), any(FluentComponent.class))), eq(cancelledCheck));
    verify(cleanupMethod).run(eq(repository2), argThat(streamContains(any(FluentComponent.class))), eq(cancelledCheck));
  }

  /**
   * Tests cleanup with multiple repositories and policies using virtual threads.
   */
  @Test
  void multiRepositoryCleanupWithVirtualThreads() {
    // Create a large number of repositories with policies
    List<Repository> repositories = new ArrayList<>();
    for (int i = 0; i < LARGE_REPOSITORY_COUNT; i++) {
      Repository repo = mock(Repository.class);
      String policyName = "policy" + i;
      setupRepository(repo, policyName);
      
      CleanupPolicy policy = mock(CleanupPolicy.class);
      when(policy.getCriteria()).thenReturn(ImmutableMap.of(LAST_BLOB_UPDATED_KEY, "1"));
      when(cleanupPolicyStorage.get(policyName)).thenReturn(policy);
      
      // Create a stream of mock components
      Stream<FluentComponent> components = IntStream.range(0, COMPONENTS_PER_REPOSITORY)
          .mapToObj(j -> mock(FluentComponent.class));
      when(browseService.browse(policy, repo)).thenReturn(components);
      
      when(repo.getFormat()).thenReturn(format);
      repositories.add(repo);
    }
    
    when(repositoryManager.browse()).thenReturn(repositories);

    // Run cleanup with virtual threads
    long startTime = System.nanoTime();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> underTest.cleanup(cancelledCheck), executor);
      future.join(); // Wait for completion
    }
    long duration = System.nanoTime() - startTime;

    // Verify each repository was processed
    for (Repository repo : repositories) {
      verify(cleanupMethod).run(eq(repo), any(), eq(cancelledCheck));
    }
    
    // Log performance metrics
    log.info("Cleaned up {} repositories with {} components each in {} ms using virtual threads",
        LARGE_REPOSITORY_COUNT, COMPONENTS_PER_REPOSITORY, TimeUnit.NANOSECONDS.toMillis(duration));
  }

  /**
   * Compares performance between platform threads and virtual threads for cleanup operations.
   */
  @Test
  void compareThreadPerformance() {
    // Setup a large number of repositories with policies
    List<Repository> repositories = new ArrayList<>();
    for (int i = 0; i < LARGE_REPOSITORY_COUNT; i++) {
      Repository repo = mock(Repository.class);
      String policyName = "policy" + i;
      setupRepository(repo, policyName);
      
      CleanupPolicy policy = mock(CleanupPolicy.class);
      when(policy.getCriteria()).thenReturn(ImmutableMap.of(LAST_BLOB_UPDATED_KEY, "1"));
      when(cleanupPolicyStorage.get(policyName)).thenReturn(policy);
      
      // Create a stream of mock components
      Stream<FluentComponent> components = IntStream.range(0, COMPONENTS_PER_REPOSITORY)
          .mapToObj(j -> mock(FluentComponent.class));
      when(browseService.browse(policy, repo)).thenReturn(components);
      
      when(repo.getFormat()).thenReturn(format);
      repositories.add(repo);
    }
    
    when(repositoryManager.browse()).thenReturn(repositories);

    // Run with platform threads
    long platformStart = System.nanoTime();
    try (ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> underTest.cleanup(cancelledCheck), executor);
      future.join(); // Wait for completion
    }
    long platformDuration = System.nanoTime() - platformStart;
    
    // Reset mocks for second run
    for (Repository repo : repositories) {
      String policyName = (String) ((Map<String, Object>) repo.getConfiguration().getAttributes().get("cleanup")).get("policyName");
      CleanupPolicy policy = cleanupPolicyStorage.get(policyName);
      Stream<FluentComponent> components = IntStream.range(0, COMPONENTS_PER_REPOSITORY)
          .mapToObj(j -> mock(FluentComponent.class));
      when(browseService.browse(policy, repo)).thenReturn(components);
    }

    // Run with virtual threads
    long virtualStart = System.nanoTime();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> underTest.cleanup(cancelledCheck), executor);
      future.join(); // Wait for completion
    }
    long virtualDuration = System.nanoTime() - virtualStart;

    // Log performance comparison
    log.info("Platform thread cleanup duration: {} ms", TimeUnit.NANOSECONDS.toMillis(platformDuration));
    log.info("Virtual thread cleanup duration: {} ms", TimeUnit.NANOSECONDS.toMillis(virtualDuration));
    log.info("Performance improvement: {}%", 
        (platformDuration > virtualDuration) ? 
            ((platformDuration - virtualDuration) * 100.0 / platformDuration) : 
            "No improvement");
    
    // Verify each repository was processed twice (once per run)
    for (Repository repo : repositories) {
      verify(cleanupMethod, times(2)).run(eq(repo), any(), eq(cancelledCheck));
    }
  }

  /**
   * Tests concurrent cleanup operations with virtual threads.
   */
  @Test
  void concurrentCleanupOperations() throws Exception {
    // Setup multiple repositories
    List<Repository> repositories = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      Repository repo = mock(Repository.class);
      String policyName = "policy" + i;
      setupRepository(repo, policyName);
      
      CleanupPolicy policy = mock(CleanupPolicy.class);
      when(policy.getCriteria()).thenReturn(ImmutableMap.of(LAST_BLOB_UPDATED_KEY, "1"));
      when(cleanupPolicyStorage.get(policyName)).thenReturn(policy);
      
      Stream<FluentComponent> components = ImmutableList.of(mock(FluentComponent.class), mock(FluentComponent.class)).stream();
      when(browseService.browse(policy, repo)).thenReturn(components);
      
      when(repo.getFormat()).thenReturn(format);
      repositories.add(repo);
    }
    
    when(repositoryManager.browse()).thenReturn(repositories);

    // Run multiple concurrent cleanup operations with virtual threads
    int concurrentOperations = 5;
    CountDownLatch latch = new CountDownLatch(concurrentOperations);
    AtomicInteger completedOperations = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < concurrentOperations; i++) {
        executor.submit(() -> {
          try {
            underTest.cleanup(cancelledCheck);
            completedOperations.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete with timeout
      boolean allCompleted = latch.await(30, TimeUnit.SECONDS);
      assertTrue(allCompleted, "All concurrent cleanup operations should complete within timeout");
      assertEquals(concurrentOperations, completedOperations.get(), "All operations should complete successfully");
    }
    
    // Verify each repository was processed multiple times
    for (Repository repo : repositories) {
      verify(cleanupMethod, times(concurrentOperations)).run(eq(repo), any(), eq(cancelledCheck));
    }
  }

  /**
   * Tests error recovery and retry behavior with virtual threads.
   */
  @Test
  void errorRecoveryWithVirtualThreads() {
    // Configure cleanup method to fail on first attempt but succeed on second attempt
    when(deletionProgress.isFailed()).thenReturn(true).thenReturn(false);
    when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository1));
    
    // Run cleanup with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> underTest.cleanup(cancelledCheck), executor);
      future.join(); // Wait for completion
    }
    
    // Verify retry behavior
    verify(cleanupMethod, times(2)).run(any(), any(), any());
  }

  /**
   * Tests cleanup retry on search context missing exception with virtual threads.
   */
  @Test
  void searchContextMissingExceptionWithVirtualThreads() {
    // Configure cleanup method to throw exception on first attempt but succeed on second attempt
    when(cleanupMethod.run(any(), any(), any()))
        .thenThrow(new RuntimeException(new SearchContextMissingException(10L)))
        .thenReturn(deletionProgress);
    
    // Run cleanup with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> underTest.cleanup(cancelledCheck), executor);
      future.join(); // Wait for completion
    }
    
    // Verify retry behavior
    verify(cleanupMethod, times(2)).run(eq(repository1), any(), eq(cancelledCheck));
    verify(cleanupMethod).run(eq(repository2), any(), eq(cancelledCheck));
  }

  /**
   * Tests cleanup with a large number of components using virtual threads.
   */
  @Test
  void largeComponentCleanupWithVirtualThreads() {
    // Setup repository with a large number of components
    int componentCount = 100_000;
    Stream<FluentComponent> largeComponentStream = IntStream.range(0, componentCount)
        .mapToObj(i -> mock(FluentComponent.class));
    when(browseService.browse(cleanupPolicy1, repository1)).thenReturn(largeComponentStream);
    
    when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository1));
    
    // Run cleanup with virtual threads and measure performance
    long startTime = System.nanoTime();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> underTest.cleanup(cancelledCheck), executor);
      future.join(); // Wait for completion
    }
    long duration = System.nanoTime() - startTime;
    
    // Log performance metrics
    log.info("Cleaned up {} components in {} ms using virtual threads",
        componentCount, TimeUnit.NANOSECONDS.toMillis(duration));
    
    // Verify cleanup was performed
    verify(cleanupMethod).run(eq(repository1), any(), eq(cancelledCheck));
  }

  /**
   * Tests cancellation behavior with virtual threads.
   */
  @Test
  void cancellationWithVirtualThreads() {
    // Configure cleanup method to set cancelled flag after first repository
    doAnswer(i -> {
      when(cancelledCheck.getAsBoolean()).thenReturn(true);
      return deletionProgress;
    }).when(cleanupMethod).run(eq(repository1), any(), eq(cancelledCheck));
    
    // Run cleanup with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> underTest.cleanup(cancelledCheck), executor);
      future.join(); // Wait for completion
    }
    
    // Verify cancellation behavior
    verify(cleanupMethod).run(eq(repository1), any(), eq(cancelledCheck));
    verify(cleanupMethod, never()).run(eq(repository2), any(), eq(cancelledCheck));
  }

  /**
   * Tests virtual thread behavior with multiple cleanup policies per repository.
   */
  @Test
  void multiPolicyCleanupWithVirtualThreads() {
    // Setup repository with multiple policies
    String[] policyNames = {"policy-a", "policy-b", "policy-c"};
    setupRepository(repository1, policyNames);
    
    // Configure policies and components
    for (String policyName : policyNames) {
      CleanupPolicy policy = mock(CleanupPolicy.class);
      when(policy.getCriteria()).thenReturn(ImmutableMap.of(LAST_BLOB_UPDATED_KEY, "1"));
      when(cleanupPolicyStorage.get(policyName)).thenReturn(policy);
      
      Stream<FluentComponent> components = ImmutableList.of(
          mock(FluentComponent.class), 
          mock(FluentComponent.class)
      ).stream();
      when(browseService.browse(policy, repository1)).thenReturn(components);
    }
    
    when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository1));
    
    // Run cleanup with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> underTest.cleanup(cancelledCheck), executor);
      future.join(); // Wait for completion
    }
    
    // Verify each policy was processed
    verify(cleanupMethod, times(policyNames.length)).run(eq(repository1), any(), eq(cancelledCheck));
  }

  /**
   * Tests virtual thread behavior with empty component streams.
   */
  @Test
  void emptyComponentStreamWithVirtualThreads() {
    // Configure empty component streams
    when(browseService.browse(cleanupPolicy1, repository1)).thenReturn(empty());
    when(browseService.browse(cleanupPolicy2, repository2)).thenReturn(empty());
    
    // Run cleanup with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> underTest.cleanup(cancelledCheck), executor);
      future.join(); // Wait for completion
    }
    
    // Verify no cleanup was performed
    verify(cleanupMethod, never()).run(eq(repository1), any(), eq(cancelledCheck));
    verify(cleanupMethod, never()).run(eq(repository2), any(), eq(cancelledCheck));
  }

  /**
   * Tests virtual thread behavior with empty criteria.
   */
  @Test
  void emptyCriteriaWithVirtualThreads() {
    // Configure empty criteria
    when(cleanupPolicy1.getCriteria()).thenReturn(emptyMap());
    when(cleanupPolicy2.getCriteria()).thenReturn(emptyMap());
    
    // Run cleanup with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> underTest.cleanup(cancelledCheck), executor);
      future.join(); // Wait for completion
    }
    
    // Verify no cleanup was performed
    verify(cleanupMethod, never()).run(eq(repository1), any(), eq(cancelledCheck));
    verify(cleanupMethod, never()).run(eq(repository2), any(), eq(cancelledCheck));
  }

  private void setupRepository(final Repository repository, final String... policyName) {
    Configuration repositoryConfig = mock(Configuration.class);
    when(repository.getConfiguration()).thenReturn(repositoryConfig);

    ImmutableMap<String, Map<String, Object>> attributes = ImmutableMap
        .of("cleanup", singletonMap("policyName", policyName != null ? newLinkedHashSet(asList(policyName)) : null));
    when(repositoryConfig.getAttributes()).thenReturn(attributes);

    when(repository.getType()).thenReturn(type);
  }
}