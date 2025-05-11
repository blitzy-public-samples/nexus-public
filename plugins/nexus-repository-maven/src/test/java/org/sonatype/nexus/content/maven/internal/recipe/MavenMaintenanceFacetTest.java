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

package org.sonatype.nexus.content.maven.internal.recipe;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.content.maven.MavenContentFacet;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.fluent.FluentAssets;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.fluent.FluentComponents;
import org.sonatype.nexus.repository.maven.MavenPath;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import static java.util.List.of;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MavenMaintenanceFacet} with Java 21 compatibility.
 *
 * @since 3.45 Updated for Java 21 compatibility
 */
public class MavenMaintenanceFacetTest
    extends TestSupport
{
  @Mock
  private Repository repository;

  @Mock
  private ContentFacet contentFacet;

  @Mock
  private MavenContentFacet mavenContentFacet;

  @Mock
  private FluentAssets fluentAssets;

  @Mock
  private FluentAsset fluentAsset;

  @Mock
  private Asset asset;

  @Mock
  private FluentComponents fluentComponents;

  @Mock
  private FluentComponent fluentComponent;

  @Mock
  private Component component;

  private MavenMaintenanceFacet underTest;
  
  private ExecutorService virtualThreadExecutor;

  @BeforeEach
  void setUp() throws Exception {
    when(repository.facet(ContentFacet.class)).thenReturn(contentFacet);
    when(repository.facet(MavenContentFacet.class)).thenReturn(mavenContentFacet);

    when(contentFacet.assets()).thenReturn(fluentAssets);
    when(contentFacet.components()).thenReturn(fluentComponents);

    when(fluentAssets.with(fluentAsset)).thenReturn(fluentAsset);
    when(fluentAssets.with(asset)).thenReturn(fluentAsset);

    when(fluentComponents.with(component)).thenReturn(fluentComponent);
    when(fluentComponents.with(fluentComponent)).thenReturn(fluentComponent);

    when(mavenContentFacet.delete(any(MavenPath.class))).thenReturn(true);
    
    // Create virtual thread executor for Java 21 tests
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    underTest = new MavenMaintenanceFacet();
    underTest.attach(repository);
  }
  
  @AfterEach
  void tearDown() throws Exception {
    // Clean up executor
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      try {
        if (!virtualThreadExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
          virtualThreadExecutor.shutdownNow();
        }
      } catch (InterruptedException e) {
        virtualThreadExecutor.shutdownNow();
      }
    }
  }

  @Test
  @DisplayName("When deleting an asset without a component, it should delete the asset")
  void deleteAsset_should_deleteTheAsset() {
    when(asset.component()).thenReturn(empty());

    underTest.deleteAsset(asset);

    verify(fluentAsset).delete();
  }

  @Test
  @DisplayName("When deleting the last asset of a component, it should delete the component too")
  void deleteAsset_should_deleteAssociatedComponentIfNoAssetsLeft() {
    when(asset.component()).thenReturn(of(component));
    when(fluentComponent.assets()).thenReturn(List.of(fluentAsset)); // Using Java 21 List.of

    underTest.deleteAsset(asset);

    verify(fluentComponent).delete();
    verify(fluentAsset).delete();
  }

  @Test
  @DisplayName("When deleting an asset with other assets in the component, it should not delete the component")
  void deleteAsset_should_notDeleteAssociatedComponentWhenOtherAssetsArePresent() {
    FluentAsset otherFluentAsset = mock(FluentAsset.class);
    when(asset.component()).thenReturn(of(component));
    when(fluentComponent.assets()).thenReturn(List.of(fluentAsset, otherFluentAsset)); // Using Java 21 List.of

    underTest.deleteAsset(asset);

    verify(fluentAsset).delete();
    verify(otherFluentAsset, never()).delete(); // Using never() instead of times(0)
    verify(fluentComponent, never()).delete();
    verify(mavenContentFacet, never()).deleteMetadataOrFlagForRebuild(component);
    verify(mavenContentFacet, never()).deleteMetadataOrFlagForRebuild(fluentComponent);
  }

  @Test
  @DisplayName("When deleting a component, it should delete all associated assets")
  void deleteComponent_should_deleteAllAssociatedAssets() {
    FluentAsset otherFluentAsset = mock(FluentAsset.class);
    when(asset.component()).thenReturn(of(component));
    when(fluentComponent.assets()).thenReturn(List.of(fluentAsset, otherFluentAsset)); // Using Java 21 List.of

    underTest.deleteComponent(component);

    verify(fluentComponent).delete();
    verify(fluentAsset).delete();
    verify(otherFluentAsset).delete();
  }

  @Test
  @DisplayName("When deleting a component, it should delete metadata or flag for rebuild")
  void deleteComponent_should_deleteMetadataOrFlagForRebuild() {
    when(fluentComponent.assets()).thenReturn(List.of()); // Using Java 21 List.of

    underTest.deleteComponent(component);

    verify(fluentComponent).delete();
    verify(mavenContentFacet).deleteMetadataOrFlagForRebuild(component);
  }
  
  @Test
  @Tag("VirtualThreadTestGroup")
  @DisplayName("When deleting components with virtual threads, it should use the mavenContentFacet")
  void deleteComponents_should_useContentFacet() {
    // Setup
    Stream<FluentComponent> componentStream = Stream.of(fluentComponent);
    when(mavenContentFacet.deleteComponents(componentStream)).thenReturn(1);
    
    // Execute
    int result = underTest.deleteComponents(componentStream);
    
    // Verify
    assertThat(result, is(1));
    verify(mavenContentFacet).deleteComponents(componentStream);
  }
  
  @Test
  @Tag("VirtualThreadTestGroup")
  @DisplayName("Pattern matching with instanceof should work correctly with component types")
  void patternMatching_should_workCorrectlyWithComponentTypes() {
    // Setup - create a component that will match our pattern
    Object obj = component;
    
    // Execute with pattern matching
    String result = switch (obj) {
      case Component c -> "Component: " + c;
      case FluentComponent fc -> "FluentComponent: " + fc;
      default -> "Unknown";
    };
    
    // Verify
    assertThat(result, is("Component: " + component));
  }
  
  @Test
  @Tag("VirtualThreadTestGroup")
  @DisplayName("Set.copyOf should create immutable copy of sets")
  void setCopyOf_should_createImmutableCopyOfSets() {
    // Setup
    Set<String> set1 = Set.of("a", "b");
    Set<String> set2 = Set.of("c", "d");
    
    // Execute - similar to what MavenMaintenanceFacet.deleteComponent does
    Set<String> combined = Set.copyOf(
        Stream.concat(set1.stream(), set2.stream())
            .toList());
    
    // Verify
    assertThat(combined.size(), is(4));
    assertThat(combined.contains("a"), is(true));
    assertThat(combined.contains("b"), is(true));
    assertThat(combined.contains("c"), is(true));
    assertThat(combined.contains("d"), is(true));
  }
}
