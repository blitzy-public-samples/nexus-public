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

import javax.inject.Provider;

import org.sonatype.nexus.content.maven.internal.index.MavenContentHostedIndexFacet;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.maven.PurgeUnusedSnapshotsFacet;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;
import org.sonatype.nexus.repository.types.HostedType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import static org.mockito.Mockito.verify;

/**
 * Tests for {@link MavenHostedRecipe}.
 * <p>
 * Updated for Java 21 compatibility with JUnit Jupiter 5.10.1 and Mockito 4.11.0.
 * This test validates the proper attachment of facets to Maven hosted repositories.
 *
 * @since 3.60.0
 */
public class MavenHostedRecipeTest
    extends MavenRecipeTestSupport
{
  @Mock
  private Repository mavenHostedRepository;

  @Mock
  private MavenContentHostedIndexFacet mavenHostedIndexFacet;

  private final Provider<MavenContentHostedIndexFacet> mavenHostedIndexFacetProvider = () -> mavenHostedIndexFacet;

  @Mock
  private PurgeUnusedSnapshotsFacet purgeUnusedSnapshotsFacet;

  private final Provider<PurgeUnusedSnapshotsFacet> mavenPurgeUnusedSnapshotsFacetProvider =
      () -> purgeUnusedSnapshotsFacet;

  private MavenHostedRecipe underTest;

  @BeforeEach
  public void setup() {
    underTest = new MavenHostedRecipe(new HostedType(), new Maven2Format(), mavenHostedIndexFacetProvider,
        mavenPurgeUnusedSnapshotsFacetProvider);
    mockFacets(underTest);
    mockHandlers(underTest);
  }

  @Test
  public void testExpectedFacetsAreAttached() throws Exception {
    underTest.apply(mavenHostedRepository);
    verify(mavenHostedRepository).attach(securityFacet);
    verify(mavenHostedRepository).attach(viewFacet);
    verify(mavenHostedRepository).attach(mavenMetadataRebuildFacet);
    verify(mavenHostedRepository).attach(mavenContentFacet);
    verify(mavenHostedRepository).attach(searchFacet);
    verify(mavenHostedRepository).attach(browseFacet);
    verify(mavenHostedRepository).attach(mavenArchetypeCatalogFacet);
    verify(mavenHostedRepository).attach(mavenHostedIndexFacet);
    verify(mavenHostedRepository).attach(mavenMaintenanceFacet);
    verify(mavenHostedRepository).attach(removeSnapshotsFacet);
    verify(mavenHostedRepository).attach(purgeUnusedSnapshotsFacet);
  }
  
  /**
   * Tests the recipe configuration using Java 21 pattern matching for switch.
   * This demonstrates how pattern matching can be used to validate different
   * repository configurations in a more concise way.
   */
  @Test
  public void testRecipeConfigurationWithPatternMatching() {
    // Create the recipe and validate its configuration using pattern matching
    var recipe = underTest;
    
    // Using pattern matching to check the recipe type and format
    switch (recipe) {
      case MavenHostedRecipe r when r.getFormat() instanceof Maven2Format -> {
        // This is the expected case - Maven2Format is correctly configured
        verify(mavenHostedRepository).attach(mavenHostedIndexFacet);
      }
      case MavenHostedRecipe r -> {
        // This would be unexpected - the recipe should have Maven2Format
        throw new AssertionError("Recipe has unexpected format: " + r.getFormat());
      }
      default -> throw new AssertionError("Unexpected recipe type: " + recipe.getClass());
    }
  }
}