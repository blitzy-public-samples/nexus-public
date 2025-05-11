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

import java.util.Optional;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.content.maven.MavenContentFacet;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.cache.RepositoryCacheInvalidationService;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.event.asset.AssetCreatedEvent;
import org.sonatype.nexus.repository.content.event.asset.AssetDeletedEvent;
import org.sonatype.nexus.repository.content.event.asset.AssetEvent;
import org.sonatype.nexus.repository.content.event.asset.AssetUploadedEvent;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.content.fluent.FluentAssetBuilder;
import org.sonatype.nexus.repository.content.fluent.FluentAssets;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.maven.internal.Maven2MavenPathParser;
import org.sonatype.nexus.validation.ConstraintViolationFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test for {@link MavenContentGroupFacetImpl} that verifies group repository behavior.
 * Updated for Java 21 compatibility with JUnit Jupiter and Mockito 4.11.0.
 * 
 * This test class demonstrates Java 21 features including:
 * - Pattern matching for switch
 * - Type patterns
 * - Guarded patterns with 'when' clause
 * - Local variable type inference with 'var'
 */
@ExtendWith(MockitoExtension.class)
public class MavenContentGroupFacetImplTest
    extends TestSupport
{
  private MavenContentGroupFacetImpl underTest;

  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private ConstraintViolationFactory constraintViolationFactory;

  @Mock
  private Type groupType;

  @Mock
  private RepositoryCacheInvalidationService repositoryCacheInvalidationService;

  @BeforeEach
  public void setup() {
    underTest = spy(new MavenContentGroupFacetImpl(repositoryManager, constraintViolationFactory, groupType,
        repositoryCacheInvalidationService));
  }

  /**
   * Ensure that the path used to find assets in the handler uses a path that is
   * prefixed with a "/"
   */
  @Test
  @DisplayName("Asset path should be prefixed with slash when finding assets")
  public void testHandleAssetEvent_path_find_with_slash() throws Exception {
    // Setup test mocks with pattern matching for cleaner code
    var assetBuilder = mock(FluentAssetBuilder.class);
    when(assetBuilder.find()).thenReturn(Optional.empty());
    
    var assets = mock(FluentAssets.class);
    when(assets.path(any())).thenReturn(assetBuilder);
    
    var contentFacet = mock(MavenContentFacet.class);
    when(contentFacet.getMavenPathParser()).thenReturn(new Maven2MavenPathParser());
    when(contentFacet.assets()).thenReturn(assets);
    
    var repository = mock(Repository.class);
    when(repository.getName()).thenReturn("repo1");
    when(repository.facet(MavenContentFacet.class)).thenReturn(contentFacet);
    when(repository.facet(ContentFacet.class)).thenReturn(contentFacet);
    
    doReturn(true).when(underTest).member(repository);
    underTest.attach(repository);
    
    var asset = mock(Asset.class);
    when(asset.path()).thenReturn("/com/example/foo/1.0-SNAPSHOT/maven-metadata.xml");
    when(asset.component()).thenReturn(Optional.empty());
    
    var event = mock(AssetUploadedEvent.class);
    when(event.getAsset()).thenReturn(asset);
    when(event.getRepository()).thenReturn(Optional.of(repository));
    
    // Execute the method under test
    underTest.onAssetUploadedEvent(event);
    
    // Verify the expected behavior
    verify(assets).path("/com/example/foo/1.0-SNAPSHOT/maven-metadata.xml");
  }
  
  /**
   * Tests pattern matching with different types of asset events.
   * This demonstrates Java 21 pattern matching for switch capabilities.
   */
  @Test
  @DisplayName("Pattern matching for switch should handle different asset event types")
  public void testPatternMatchingWithAssetEvents() {
    // Setup repository and asset similar to the previous test
    var assetBuilder = mock(FluentAssetBuilder.class);
    when(assetBuilder.find()).thenReturn(Optional.empty());
    
    var assets = mock(FluentAssets.class);
    when(assets.path(any())).thenReturn(assetBuilder);
    
    var contentFacet = mock(MavenContentFacet.class);
    when(contentFacet.getMavenPathParser()).thenReturn(new Maven2MavenPathParser());
    when(contentFacet.assets()).thenReturn(assets);
    
    var repository = mock(Repository.class);
    when(repository.getName()).thenReturn("repo1");
    when(repository.facet(MavenContentFacet.class)).thenReturn(contentFacet);
    when(repository.facet(ContentFacet.class)).thenReturn(contentFacet);
    
    doReturn(true).when(underTest).member(repository);
    underTest.attach(repository);
    
    var asset = mock(Asset.class);
    when(asset.path()).thenReturn("/com/example/foo/1.0-SNAPSHOT/maven-metadata.xml");
    when(asset.component()).thenReturn(Optional.empty());
    
    // Create different types of events to test pattern matching
    var uploadedEvent = mock(AssetUploadedEvent.class);
    when(uploadedEvent.getAsset()).thenReturn(asset);
    when(uploadedEvent.getRepository()).thenReturn(Optional.of(repository));
    
    var createdEvent = mock(AssetCreatedEvent.class);
    when(createdEvent.getAsset()).thenReturn(asset);
    when(createdEvent.getRepository()).thenReturn(Optional.of(repository));
    
    var deletedEvent = mock(AssetDeletedEvent.class);
    when(deletedEvent.getAsset()).thenReturn(asset);
    when(deletedEvent.getRepository()).thenReturn(Optional.of(repository));
    
    // Test pattern matching with different event types
    processAssetEvent(uploadedEvent);
    verify(assets).path("/com/example/foo/1.0-SNAPSHOT/maven-metadata.xml");
    
    // Reset and test with created event
    processAssetEvent(createdEvent);
    verify(assets, never()).path("/com/example/foo/1.0-SNAPSHOT/maven-metadata.xml");
  }
  
  /**
   * Demonstrates Java 21 pattern matching for switch with type patterns and guarded patterns.
   * This method shows how the implementation could use pattern matching to handle different event types.
   * 
   * @param event The asset event to process
   */
  private void processAssetEvent(AssetEvent event) {
    // This demonstrates how pattern matching for switch could be used in the implementation
    // to handle different types of events more elegantly in Java 21
    boolean shouldProcess = switch (event) {
      // Type pattern: match on specific event type and bind to variable
      case AssetUploadedEvent e -> {
        // Process uploaded event
        underTest.onAssetUploadedEvent(e);
        yield true;
      }
      // Type pattern with guard: match on type and additional condition
      case AssetCreatedEvent e when e.getRepository().isPresent() -> {
        // Process created event only if repository is present
        // In this test we're not actually processing it
        yield false;
      }
      // Multiple patterns in one case
      case AssetDeletedEvent e, null -> {
        // Handle deleted event or null case
        yield false;
      }
      // Default case to handle any other type of AssetEvent
      default -> false;
    };
    
    // The above pattern matching switch is equivalent to this traditional code:
    /*
    boolean shouldProcess = false;
    if (event instanceof AssetUploadedEvent) {
      AssetUploadedEvent e = (AssetUploadedEvent) event;
      underTest.onAssetUploadedEvent(e);
      shouldProcess = true;
    } else if (event instanceof AssetCreatedEvent && ((AssetCreatedEvent) event).getRepository().isPresent()) {
      shouldProcess = false;
    } else if (event instanceof AssetDeletedEvent || event == null) {
      shouldProcess = false;
    } else {
      shouldProcess = false;
    }
    */
  }
}