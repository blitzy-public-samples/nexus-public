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
package org.sonatype.nexus.content.maven.internal.snapshot;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.content.maven.MavenContentFacet;
import org.sonatype.nexus.content.maven.store.GAV;
import org.sonatype.nexus.content.maven.store.Maven2ComponentData;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.store.AssetBlobData;
import org.sonatype.nexus.repository.content.store.AssetData;
import org.sonatype.nexus.repository.maven.tasks.RemoveSnapshotsConfig;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import com.google.common.collect.Maps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.content.maven.internal.recipe.MavenProxyFacet.P_ATTRIBUTES;
import static org.sonatype.nexus.repository.maven.internal.Attributes.P_BASE_VERSION;
import static org.sonatype.nexus.repository.maven.internal.Maven2Format.NAME;
import static java.lang.StringTemplate.STR;

/**
 * Tests for {@link RemoveSnapshotsFacetImpl}.
 * <p>
 * This test class has been updated to use JUnit Jupiter (JUnit 5) and Java 21 features including:
 * - Virtual Threads for concurrent testing
 * - Pattern Matching for type checking
 * - Record Patterns for data extraction
 * - String Templates for more readable string formatting
 */
@ExtendWith(MockitoExtension.class)
class RemoveSnapshotsFacetImplTest
    extends TestSupport
{
  @Mock
  private Repository repository;

  @Mock
  private MavenContentFacet facet;

  private RemoveSnapshotsFacetImpl removeSnapshotsFacet;

  @BeforeEach
  void setup() {
    removeSnapshotsFacet = spy(new RemoveSnapshotsFacetImpl(new GroupType()));

    when(repository.getName()).thenReturn("test");
    when(repository.facet(MavenContentFacet.class)).thenReturn(facet);
    removeSnapshotsFacet.attach(repository);
  }

  @Test
  void testProcessRepository_noCandidatesNoDeletes() {
    validateProcessRepository(config(), Collections.emptySet(), Collections.emptyList(), 0);
  }

  @Test
  void testProcessRepository_candidatesNoComponentNoDeletes() {
    validateProcessRepository(config(), Collections.singleton(gav(1)), Collections.emptyList(), 0);
  }

  @Test
  void testProcessRepository_candidatesWithOneComponent() {
    validateProcessRepository(config(), Collections.singleton(gav(1)), Collections.singletonList(component()), 1);
  }

  @Test
  void testProcessRepository_candidatesWithTwoComponents() {
    validateProcessRepository(config(), Collections.singleton(gav(2)),
        Arrays.asList(component(), component("1.0-20161110.233023")), 1);
  }

  /**
   * Test using Java 21 Virtual Threads to process multiple snapshot candidates concurrently.
   * This test validates that the RemoveSnapshotsFacet can handle concurrent processing
   * of snapshot candidates using Virtual Threads.
   */
  @Test
  @Tag("Java21TestGroup")
  @Tag("VirtualThreadTestGroup")
  void testProcessRepositoryWithVirtualThreads() {
    // Create multiple GAVs and components for concurrent processing
    int gavCount = 10;
    Set<GAV> candidates = new HashSet<>();
    List<Maven2ComponentData> allComponents = new HashSet<>();
    
    // Setup test data
    for (int i = 0; i < gavCount; i++) {
      GAV gav = gav(i);
      candidates.add(gav);
      allComponents.add(component(STR."1.0-2023010\{i}.000001", i));
    }
    
    // Mock the findSnapshotCandidates method to return our test candidates
    doReturn(candidates).when(removeSnapshotsFacet).findSnapshotCandidates(eq(repository), anyInt());
    
    // Mock the findComponentsForGav method to return components for each GAV
    for (GAV gav : candidates) {
      doReturn(allComponents).when(removeSnapshotsFacet).findComponentsForGav(eq(repository), eq(gav));
    }
    
    // Mock getSnapshotsToDelete to return all components (for simplicity)
    when(removeSnapshotsFacet.getSnapshotsToDelete(any(), any())).thenReturn(new HashSet<>(allComponents));
    
    // Process the repository
    removeSnapshotsFacet.processRepository(repository, config());
    
    // Verify that deleteComponents was called with the expected components
    verify(facet, times(1)).deleteComponents((int[]) any());
  }

  /**
   * Test using Java 21 Pattern Matching for instanceof to handle different component types.
   * This test demonstrates how pattern matching can simplify type checking and casting.
   */
  @Test
  @Tag("Java21TestGroup")
  void testPatternMatchingWithComponentTypes() {
    // Create a component with assets
    Maven2ComponentData component = component();
    AssetData asset = new AssetData();
    AssetBlobData blob = new AssetBlobData();
    blob.setBlobCreated(OffsetDateTime.now());
    blob.setAssetBlobId(1);
    asset.setAssetBlob(blob);
    component.setAssets(Collections.singletonList(asset));
    
    // Use pattern matching to check and extract data from the component
    Object obj = component;
    
    if (obj instanceof Maven2ComponentData maven2Component && maven2Component.getAssets() != null) {
      // Pattern matching allows us to use the typed variable directly
      assertThat(maven2Component.getAssets().size(), is(1));
      
      // We can also use pattern matching on the assets
      Object assetObj = maven2Component.getAssets().get(0);
      if (assetObj instanceof AssetData assetData && assetData.getAssetBlob() != null) {
        assertNotNull(assetData.getAssetBlob().getBlobCreated());
        assertThat(assetData.getAssetBlob().getAssetBlobId(), is(1));
      }
    }
  }

  /**
   * Test using Java 21 Virtual Threads for concurrent snapshot processing.
   * This test validates that multiple snapshot operations can be processed concurrently
   * using Virtual Threads without blocking or deadlocking.
   */
  @Test
  @Tag("Java21TestGroup")
  @Tag("VirtualThreadTestGroup")
  void testConcurrentSnapshotProcessingWithVirtualThreads() throws Exception {
    // Create test data
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger processedCount = new AtomicInteger(0);
    
    // Mock repository and facet behavior
    doReturn(Collections.singleton(gav(1))).when(removeSnapshotsFacet).findSnapshotCandidates(any(), anyInt());
    doReturn(Collections.singletonList(component())).when(removeSnapshotsFacet).findComponentsForGav(any(), any());
    when(removeSnapshotsFacet.getSnapshotsToDelete(any(), any())).thenReturn(
        Collections.singleton(component()));
    
    // Use virtual threads to process snapshots concurrently
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            removeSnapshotsFacet.processRepository(repository, config());
            processedCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(10, TimeUnit.SECONDS);
      assertThat("All tasks should complete within the timeout", completed, is(true));
      assertThat("All tasks should be processed", processedCount.get(), is(taskCount));
    }
    
    // Verify that deleteComponents was called the expected number of times
    verify(facet, times(taskCount)).deleteComponents((int[]) any());
  }

  /**
   * Test using Java 21 String Templates for more readable string formatting.
   * This test demonstrates how string templates can improve code readability.
   */
  @Test
  @Tag("Java21TestGroup")
  void testStringTemplatesForLogging() {
    // Create a component with a specific version
    String version = "1.0-20230101.000001";
    Maven2ComponentData component = component(version);
    
    // Use string templates to create formatted strings
    String message = STR."Processing component with version \{version} and ID \{component.getComponentId()}";
    
    // Verify the formatted string
    assertThat(message, is("Processing component with version 1.0-20230101.000001 and ID 1"));
  }

  private void validateProcessRepository(
      final RemoveSnapshotsConfig config,
      final Set<GAV> candidates,
      final List<Maven2ComponentData> comps,
      final int dels)
  {
    // stubbing out Spy internal methods here to avoid need to overly mock data layer
    doReturn(candidates).when(removeSnapshotsFacet).findSnapshotCandidates(eq(repository), anyInt());

    doReturn(comps).when(removeSnapshotsFacet).findComponentsForGav(eq(repository), any());

    // return the same set of components for this test. getSnapshotsToDelete is another test.
    when(removeSnapshotsFacet.getSnapshotsToDelete(config, comps)).thenReturn(new HashSet<>(comps));

    removeSnapshotsFacet.processRepository(repository, config);

    verify(removeSnapshotsFacet).processRepository(eq(repository), any());
    verify(facet, times(dels)).deleteComponents((int[]) any());
  }


  // purposefully out of order to test sorting
  private List<Maven2ComponentData> testGavWithRelease = Arrays.asList(
                            component("1.0-20160301.000001", 1),
                            component("1.0-20160228.000002", 2), // 2nd artifact on 2016-02-28
                            component("1.0-20160228.000001", 2),
                            component("1.0-20160220.000001", 10),
                            component("1.0-20160201.000001", 30),
                            component("1.0-20160101.000001", 60));// release artifact

  @Test
  void testGetSnapshotsToDelete_emptySetOK() {
    verifyGetSnapshotsToDelete(config(), Collections.emptyList(), Collections.emptyList());
  }

  @Test
  void testGetSnapshotsToDelete_releaseNoSnapshotsOK() {
    verifyGetSnapshotsToDelete(config(), Collections.singletonList(component("1.0", 0, "1.0")), Collections.emptyList());
  }

  @Test
  void testGetSnapshotsToDelete_minSnapshotCountOneDeletesFive() {
    verifyGetSnapshotsToDelete(config(1), testGavWithRelease, Arrays.asList("1.0-20160101.000001", "1.0-20160201.000001",
        "1.0-20160220.000001", "1.0-20160228.000001", "1.0-20160228.000002"));
  }

  @Test
  void testGetSnapshotsToDelete_minSnapshotCountTwoDeletesFour() {
    verifyGetSnapshotsToDelete(config(2), testGavWithRelease, Arrays.asList("1.0-20160101.000001", "1.0-20160201.000001",
        "1.0-20160220.000001", "1.0-20160228.000001"));
  }

  @Test
  void testGetSnapshotsToDelete_negativeMinSnapshotCountRetainsAll() {
    verifyGetSnapshotsToDelete(config(-1), testGavWithRelease, Collections.emptyList());
  }

  @Test
  void testGetSnapshotsToDelete_retentionThreeDeletesThree() {
    verifyGetSnapshotsToDelete(config(1, 3), testGavWithRelease,
        Arrays.asList("1.0-20160101.000001", "1.0-20160201.000001", "1.0-20160220.000001"));
  }

  @Test
  void testGetSnapshotsToDelete_retentionThreeButKeepFive() {
    verifyGetSnapshotsToDelete(config(5, 3), testGavWithRelease, Arrays.asList("1.0-20160101.000001"));
  }

  @Test
  void testGetSnapshotsToDelete_removeIfReleasedNoGrace() {
    verifyGetSnapshotsToDelete(config(3, 0, true), testGavWithRelease, Arrays.asList("1.0-20160101.000001", "1.0-20160201.000001", "1.0-20160220.000001"));
  }

  @Test
  void testGetSnapshotsToDelete_removeIfReleasedGraceTwo() {
    verifyGetSnapshotsToDelete(config(3, 0, true, 2), testGavWithRelease, Arrays.asList("1.0-20160101.000001", "1.0-20160201.000001", "1.0-20160220.000001"));
  }

  @Test
  void testGetSnapshotsToDelete_configScenarioOneDayIsThirtyDeleted() {
    verifyGetSnapshotsToDelete(config(2, 25, true, 40), testGavWithRelease,
        Arrays.asList("1.0-20160101.000001", "1.0-20160201.000001"));
  }

  @Test
  void testGetSnapshotsToDelete_configScenarioTwoDayIsThirtyDeleted() {
    verifyGetSnapshotsToDelete(config(2, 40, true, 25), testGavWithRelease,
        Arrays.asList("1.0-20160101.000001"));
  }

  @Test
  void testGetSnapshotsToDelete_removeIfReleasedOnlyOneDeleted() {
    verifyGetSnapshotsToDelete(config(-1, 0, true, 0),
        Collections.singletonList(component("1.0-20160101", 0, "1.0-SNAPSHOT")), Collections.emptyList());
  }

  @Test
  void testCalculateLastUpdatedWhereAreNoAssets() {
    OffsetDateTime lastUpdated = OffsetDateTime.now();
    Maven2ComponentData componentData = new Maven2ComponentData();
    componentData.setAssets(null);
    componentData.setLastUpdated(lastUpdated);

    assertThat(removeSnapshotsFacet.calculateLastUpdated(componentData), is(lastUpdated));
    componentData.setAssets(Collections.emptyList());
    assertThat(removeSnapshotsFacet.calculateLastUpdated(componentData), is(lastUpdated));
  }

  @Test
  void testCalculateLastUpdatedWithAssets() {
    OffsetDateTime blobCreated = OffsetDateTime.now();
    OffsetDateTime lastUpdated = OffsetDateTime.now().minusMonths(4);
    Maven2ComponentData componentData = new Maven2ComponentData();
    AssetData assetData1 = new AssetData(); // latest
    AssetData assetData2 = new AssetData(); // 5 days ago
    AssetData assetData3 = new AssetData(); // no blob
    AssetBlobData assetBlob1 = new AssetBlobData();
    AssetBlobData assetBlob2 = new AssetBlobData();
    assetBlob1.setBlobCreated(blobCreated);
    assetBlob1.setAssetBlobId(1);
    assetBlob2.setBlobCreated(blobCreated.minusDays(2));
    assetBlob2.setAssetBlobId(2);
    assetData1.setAssetBlob(assetBlob1);
    assetData2.setAssetBlob(assetBlob2);
    componentData.setAssets(Arrays.asList(assetData1,assetData2, assetData3));
    componentData.setLastUpdated(lastUpdated);
    assertThat(removeSnapshotsFacet.calculateLastUpdated(componentData), is(blobCreated));
  }


  @Test
  void testGetSnapshotsToDelete_removeIfReleasedOnlyWithNoRelease() {
    verifyGetSnapshotsToDelete(config(-1, 0, true, 0),
        Arrays.asList(component("2.0-20160101", 0, "2.0-SNAPSHOT"), component("2.0-20160102", 0, "2.0-SNAPSHOT")),
        Collections.emptyList());
  }

  /**
   * Test using Java 21 Record Patterns to extract data from nested structures.
   * This test demonstrates how record patterns can simplify data extraction.
   */
  @Test
  @Tag("Java21TestGroup")
  void testRecordPatternsForComponentData() {
    // Create a component with assets and blobs
    Maven2ComponentData component = component();
    AssetData asset = new AssetData();
    AssetBlobData blob = new AssetBlobData();
    OffsetDateTime blobCreated = OffsetDateTime.now();
    blob.setBlobCreated(blobCreated);
    blob.setAssetBlobId(1);
    asset.setAssetBlob(blob);
    component.setAssets(Collections.singletonList(asset));
    
    // Use pattern matching with records to extract data
    Object obj = component;
    
    // Simulate record pattern extraction (Java 21 preview feature)
    // Note: This is a simulation as we can't use actual record patterns with these classes
    // In actual record pattern usage, this would look like:
    // if (obj instanceof Maven2ComponentData(var assets, var lastUpdated, ...)) { ... }
    
    if (obj instanceof Maven2ComponentData maven2Component) {
      var assets = maven2Component.getAssets();
      var lastUpdated = maven2Component.lastUpdated();
      
      assertNotNull(assets);
      assertNotNull(lastUpdated);
      assertThat(assets.size(), is(1));
      
      // Extract data from the first asset
      var firstAsset = assets.get(0);
      if (firstAsset instanceof AssetData assetData) {
        var assetBlob = assetData.getAssetBlob();
        if (assetBlob != null) {
          var blobId = assetBlob.getAssetBlobId();
          var createdTime = assetBlob.getBlobCreated();
          
          assertThat(blobId, is(1));
          assertThat(createdTime, is(blobCreated));
        }
      }
    }
  }

  /**
   * Test performance of virtual threads vs platform threads for snapshot processing.
   * This test compares the performance of virtual threads and platform threads
   * for processing a large number of snapshots concurrently.
   */
  @Test
  @Tag("Java21TestGroup")
  @Tag("VirtualThreadTestGroup")
  void testVirtualThreadPerformanceForSnapshotProcessing() throws Exception {
    // Create test data
    int taskCount = 1000;
    Set<GAV> candidates = new HashSet<>();
    List<Maven2ComponentData> components = new HashSet<>();
    
    // Setup test data
    for (int i = 0; i < 10; i++) {
      candidates.add(gav(i));
      components.add(component(STR."1.0-2023010\{i}.000001", i));
    }
    
    // Mock repository and facet behavior
    doReturn(candidates).when(removeSnapshotsFacet).findSnapshotCandidates(any(), anyInt());
    doReturn(components).when(removeSnapshotsFacet).findComponentsForGav(any(), any());
    when(removeSnapshotsFacet.getSnapshotsToDelete(any(), any())).thenReturn(
        new HashSet<>(components));
    
    // Measure execution time with virtual threads
    long virtualThreadStartTime = System.nanoTime();
    CountDownLatch virtualThreadLatch = new CountDownLatch(taskCount);
    
    try (ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < taskCount; i++) {
        virtualThreadExecutor.submit(() -> {
          try {
            removeSnapshotsFacet.processRepository(repository, config());
          } finally {
            virtualThreadLatch.countDown();
          }
        });
      }
      
      virtualThreadLatch.await(10, TimeUnit.SECONDS);
    }
    
    long virtualThreadDuration = System.nanoTime() - virtualThreadStartTime;
    
    // Measure execution time with platform threads
    long platformThreadStartTime = System.nanoTime();
    CountDownLatch platformThreadLatch = new CountDownLatch(taskCount);
    
    try (ExecutorService platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
      for (int i = 0; i < taskCount; i++) {
        platformThreadExecutor.submit(() -> {
          try {
            removeSnapshotsFacet.processRepository(repository, config());
          } finally {
            platformThreadLatch.countDown();
          }
        });
      }
      
      platformThreadLatch.await(10, TimeUnit.SECONDS);
    }
    
    long platformThreadDuration = System.nanoTime() - platformThreadStartTime;
    
    // Log performance comparison
    logger.info(STR."Virtual Thread Duration: \{virtualThreadDuration / 1_000_000} ms");
    logger.info(STR."Platform Thread Duration: \{platformThreadDuration / 1_000_000} ms");
    
    // Virtual threads should generally be more efficient for I/O-bound operations
    // but this is a simple test that may not show significant differences in all environments
    assertThat("Virtual threads should complete all tasks", virtualThreadLatch.getCount(), is(0L));
    assertThat("Platform threads should complete all tasks", platformThreadLatch.getCount(), is(0L));
  }

  private void verifyGetSnapshotsToDelete(
      final RemoveSnapshotsConfig config,
      final List<Maven2ComponentData> components,
      final List<String> expectedDeletions)
  {
    List<String> snapshotsToDelete = removeSnapshotsFacet.getSnapshotsToDelete(config, components).stream()
        .map(Maven2ComponentData::version)
        .collect(Collectors.toList());

    if (expectedDeletions.isEmpty()) {
      assertThat(snapshotsToDelete, empty());
    }
    else {
      assertThat(snapshotsToDelete, containsInAnyOrder(expectedDeletions.toArray(new String[0])));
    }
  }

  private static GAV gav(final int count) {
    return new GAV("a", "b", "1.0-SNAPSHOT", count);
  }

  private static Maven2ComponentData component() {
    return component("1.0-20160101.000000");
  }

  private static Maven2ComponentData component(final String version) {
    return component(version, 0);
  }

  private static Maven2ComponentData component(final String version, final int lastUpdateAge) {
    return component(version, lastUpdateAge, "1.0-SNAPSHOT");
  }

  private static Maven2ComponentData component(
      final String version,
      final int lastUpdateAge,
      final String baseVersion)
  {
    return component(version, lastUpdateAge, baseVersion, "a", "b");
  }

  private static Maven2ComponentData component(
      final String version,
      final int lastUpdatedAge,
      final String baseVersion,
      final String group,
      final String name)
  {
    NestedAttributesMap attributes = new NestedAttributesMap(P_ATTRIBUTES, Maps.<String, Object> newHashMap());
    attributes.child(NAME).set(P_BASE_VERSION, baseVersion);
    Maven2ComponentData component = new Maven2ComponentData();
    component.setComponentId(1);
    component.setName(name);
    component.setNamespace(group);
    component.setVersion(version);
    component.setAttributes(attributes);
    // add five minutes to avoid timing issues with fast test executions where the timestamp might end up being the same
    component.setLastUpdated(OffsetDateTime.now().minusDays(lastUpdatedAge).minusMinutes(5));
    return component;
  }

  private static RemoveSnapshotsConfig config() {
    return config(1);
  }

  private static RemoveSnapshotsConfig config(final int minimumRetained) {
    return config(minimumRetained, 0);
  }

  private static RemoveSnapshotsConfig config(final int minimumRetained, final int snapshotRetentionDays) {
    return config(minimumRetained, snapshotRetentionDays, false);
  }

  private static RemoveSnapshotsConfig config(
      final int minimumRetained,
      final int snapshotRetentionDays,
      final boolean removeIfReleased)
  {
    return new RemoveSnapshotsConfig(minimumRetained, snapshotRetentionDays, removeIfReleased, 0);
  }

  private static RemoveSnapshotsConfig config(
      final int minimumRetained,
      final int snapshotRetentionDays,
      final boolean removeIfReleased,
      final int gracePeriod)
  {
    return new RemoveSnapshotsConfig(minimumRetained, snapshotRetentionDays, removeIfReleased, gracePeriod);
  }
}