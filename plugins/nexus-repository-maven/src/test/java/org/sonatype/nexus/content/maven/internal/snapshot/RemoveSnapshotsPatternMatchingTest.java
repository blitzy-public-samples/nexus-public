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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.nexus.content.maven.MavenContentFacet;
import org.sonatype.nexus.content.maven.store.GAV;
import org.sonatype.nexus.content.maven.store.Maven2ComponentData;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.maven.tasks.RemoveSnapshotsConfig;
import org.sonatype.nexus.repository.types.GroupType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link RemoveSnapshotsFacetImpl} using Java 21 pattern matching features.
 *
 * @since 3.70
 */
@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
public class RemoveSnapshotsPatternMatchingTest
    extends TestSupport
{
  @Mock
  private Repository repository;

  @Mock
  private MavenContentFacet mavenContentFacet;

  private RemoveSnapshotsFacetImpl underTest;

  @BeforeEach
  void setup() {
    underTest = new RemoveSnapshotsFacetImpl(new GroupType());
    underTest.attach(repository);

    when(repository.facet(MavenContentFacet.class)).thenReturn(mavenContentFacet);
  }

  /**
   * Tests the findSnapshotCandidates method using pattern matching for switch to handle different GAV types.
   */
  @Test
  void testFindSnapshotCandidatesWithPatternMatchingForSwitch() {
    // Setup test data
    Set<GAV> expectedGavs = Set.of(
        new GAV("group1", "artifact1", "1.0-SNAPSHOT"),
        new GAV("group2", "artifact2", "2.0-SNAPSHOT")
    );

    // Mock the facet behavior
    when(mavenContentFacet.findGavsWithSnaphots(1)).thenReturn(expectedGavs);

    // Execute the method
    Set<GAV> result = underTest.findSnapshotCandidates(repository, 1);

    // Verify results using pattern matching for switch
    assertThat(result, hasSize(2));

    // Use pattern matching for switch to validate GAV objects
    for (GAV gav : result) {
      switch (gav) {
        case GAV g when g.group.equals("group1") && g.name.equals("artifact1") ->
            assertThat(g.baseVersion, is("1.0-SNAPSHOT"));
        case GAV g when g.group.equals("group2") && g.name.equals("artifact2") ->
            assertThat(g.baseVersion, is("2.0-SNAPSHOT"));
        default -> throw new AssertionError("Unexpected GAV: " + gav);
      }
    }
  }

  /**
   * Tests the getSnapshotsToDelete method using record patterns to extract and validate component data.
   */
  @Test
  void testGetSnapshotsToDeleteWithRecordPatterns() {
    // Setup test data with different timestamps
    OffsetDateTime now = OffsetDateTime.now();
    OffsetDateTime oldTimestamp = now.minusDays(40);
    OffsetDateTime recentTimestamp = now.minusDays(5);

    List<Maven2ComponentData> components = new ArrayList<>();
    
    // Create components with different timestamps
    Maven2ComponentData oldComponent1 = createMockComponent("group1", "artifact1", "1.0-SNAPSHOT", "1.0-20230101.123456-1", oldTimestamp);
    Maven2ComponentData oldComponent2 = createMockComponent("group1", "artifact1", "1.0-SNAPSHOT", "1.0-20230201.123456-2", oldTimestamp);
    Maven2ComponentData recentComponent = createMockComponent("group1", "artifact1", "1.0-SNAPSHOT", "1.0-20230301.123456-3", recentTimestamp);
    
    components.add(recentComponent); // Keep this one (most recent)
    components.add(oldComponent1);   // Delete this one (old)
    components.add(oldComponent2);   // Delete this one (old)

    // Configure retention policy: keep minimum 1 snapshot, delete those older than 30 days
    RemoveSnapshotsConfig config = new RemoveSnapshotsConfig(1, 30, true, 14);

    // Execute the method
    Set<Maven2ComponentData> result = underTest.getSnapshotsToDelete(config, components);

    // Verify results using record patterns
    assertThat(result, hasSize(2));
    
    // Use record patterns to extract and validate component data
    for (Maven2ComponentData component : result) {
      // Using record pattern to destructure the component
      if (component instanceof Maven2ComponentData(var group, var name, var version, var assets, var lastUpdated)) {
        // Verify it's one of the old components
        assertThat(version, is("1.0-20230101.123456-1").or(is("1.0-20230201.123456-2")));
        assertThat(group, is("group1"));
        assertThat(name, is("artifact1"));
        assertThat(lastUpdated.isBefore(now.minusDays(30)), is(true));
      }
    }

    // Verify the recent component is not in the deletion set
    for (Maven2ComponentData component : result) {
      assertThat(component.version(), is(not("1.0-20230301.123456-3")));
    }
  }

  /**
   * Tests the calculateLastUpdated method using nested record patterns to extract asset blob data.
   */
  @Test
  void testCalculateLastUpdatedWithNestedRecordPatterns() {
    // Setup test data
    OffsetDateTime componentTimestamp = OffsetDateTime.now().minusDays(10);
    OffsetDateTime olderBlobTimestamp = OffsetDateTime.now().minusDays(8);
    OffsetDateTime newerBlobTimestamp = OffsetDateTime.now().minusDays(5);
    
    // Create component with assets having different blob timestamps
    Maven2ComponentData component = mock(Maven2ComponentData.class);
    when(component.lastUpdated()).thenReturn(componentTimestamp);
    
    // Create assets with blobs
    List<Asset> assets = new ArrayList<>();
    assets.add(createAssetWithBlob(olderBlobTimestamp));
    assets.add(createAssetWithBlob(newerBlobTimestamp));
    
    when(component.getAssets()).thenReturn(assets);

    // Execute the method
    OffsetDateTime result = underTest.calculateLastUpdated(component);

    // Verify the result is the newest blob timestamp using pattern matching
    assertThat(result, is(newerBlobTimestamp));
    
    // Verify using pattern matching that the result matches the expected pattern
    switch (result) {
      case OffsetDateTime dt when dt.isEqual(newerBlobTimestamp) -> 
          assertThat(true, is(true)); // Test passes
      case OffsetDateTime dt when dt.isEqual(olderBlobTimestamp) -> 
          throw new AssertionError("Result matched older blob timestamp instead of newer");
      case OffsetDateTime dt when dt.isEqual(componentTimestamp) -> 
          throw new AssertionError("Result matched component timestamp instead of blob timestamp");
      default -> 
          throw new AssertionError("Result didn't match any expected timestamp");
    }
  }

  /**
   * Tests the behavior when a component has no assets, using pattern matching.
   */
  @Test
  void testCalculateLastUpdatedWithNoAssets() {
    // Setup test data
    OffsetDateTime componentTimestamp = OffsetDateTime.now().minusDays(10);
    
    // Create component with no assets
    Maven2ComponentData component = mock(Maven2ComponentData.class);
    when(component.lastUpdated()).thenReturn(componentTimestamp);
    when(component.getAssets()).thenReturn(null);

    // Execute the method
    OffsetDateTime result = underTest.calculateLastUpdated(component);

    // Verify using pattern matching for switch
    switch (component) {
      case Maven2ComponentData c when c.getAssets() == null -> 
          assertThat(result, is(componentTimestamp));
      default -> 
          throw new AssertionError("Component unexpectedly has assets");
    }
  }

  /**
   * Helper method to create a mock component with the specified attributes.
   */
  private Maven2ComponentData createMockComponent(
      String group, 
      String name, 
      String baseVersion, 
      String version, 
      OffsetDateTime lastUpdated) 
  {
    Maven2ComponentData component = mock(Maven2ComponentData.class);
    when(component.group()).thenReturn(group);
    when(component.name()).thenReturn(name);
    when(component.baseVersion()).thenReturn(baseVersion);
    when(component.version()).thenReturn(version);
    when(component.lastUpdated()).thenReturn(lastUpdated);
    
    // Create an asset with the same timestamp
    List<Asset> assets = List.of(createAssetWithBlob(lastUpdated));
    when(component.getAssets()).thenReturn(assets);
    
    return component;
  }

  /**
   * Helper method to create a mock asset with a blob having the specified creation timestamp.
   */
  private Asset createAssetWithBlob(OffsetDateTime blobCreated) {
    AssetBlob assetBlob = mock(AssetBlob.class);
    when(assetBlob.blobCreated()).thenReturn(blobCreated);
    
    Asset asset = mock(Asset.class);
    when(asset.blob()).thenReturn(Optional.of(assetBlob));
    
    return asset;
  }
}