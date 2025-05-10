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
package org.sonatype.nexus.repository.rest.internal.resources;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.WebApplicationException;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.BaseUrlHolder;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Recipe;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.config.ConfigurationStore;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.rest.api.RepositoryXO;
import org.sonatype.nexus.repository.security.RepositoryPermissionChecker;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class RepositoryManagerRESTAdapterImplTest
    extends TestSupport
{
  private static final String REPOSITORY_NAME = "repoName";

  private static final String REPOSITORY_NAME_2 = "repoNameTwo";

  private static final String REPOSITORY_NAME_3 = "repoNameThree";

  private static final String REPOSITORY_GROUP_NAME = "repoGroupName";

  private static final String REPOSITORY_FORMAT = "repoFormat";

  private static final String REPOSITORY_FORMAT_2 = "repoFormatTwo";

  private static final String REPOSITORY_FORMAT_3 = "repoFormatThree";

  private static final String RECIPE_NAME = "recipe_1";

  private static final String RECIPE_NAME_2 = "recipe_2";

  private static final String RECIPE_NAME_3 = "recipe_3";

  private static final boolean PERMIT_BROWSE = true;

  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private ConfigurationStore store;

  @Mock
  private Repository repository;

  @Mock
  private Repository repository2;

  @Mock
  private Repository repository3;

  @Mock
  private Configuration configuration;

  @Mock
  private Configuration configuration2;

  @Mock
  private Configuration configuration3;

  @Mock
  private Repository groupRepository;

  @Mock
  private Format repositoryFormat;

  @Mock
  private Format repositoryFormat2;

  @Mock
  private Format repositoryFormat3;

  @Mock
  private RepositoryPermissionChecker repositoryPermissionChecker;

  private RepositoryManagerRESTAdapterImpl underTest;

  @BeforeEach
  public void setUp() throws Exception {
    BaseUrlHolder.set("http://nexus-url", "");

    when(repositoryManager.get(REPOSITORY_NAME)).thenReturn(repository);
    when(repositoryManager.get(REPOSITORY_GROUP_NAME)).thenReturn(groupRepository);
    when(repositoryManager.browse()).thenReturn(asList(repository, repository2, repository3));

    Recipe recipe = Mockito.mock(Recipe.class);
    Recipe recipe2 = Mockito.mock(Recipe.class);
    Recipe recipe3 = Mockito.mock(Recipe.class);

    Type type = Mockito.mock(Type.class);
    Type type2 = Mockito.mock(Type.class);
    Type type3 = Mockito.mock(Type.class);

    when(recipe.getFormat()).thenReturn(repositoryFormat);
    when(recipe2.getFormat()).thenReturn(repositoryFormat2);
    when(recipe3.getFormat()).thenReturn(repositoryFormat3);

    when(recipe.getType()).thenReturn(type);
    when(recipe2.getType()).thenReturn(type2);
    when(recipe3.getType()).thenReturn(type3);

    when(repositoryFormat.getValue()).thenReturn(REPOSITORY_FORMAT);
    when(repositoryFormat2.getValue()).thenReturn(REPOSITORY_FORMAT);
    when(repositoryFormat3.getValue()).thenReturn(REPOSITORY_FORMAT);

    when(configuration.getRepositoryName()).thenReturn(REPOSITORY_NAME);
    when(configuration.getRecipeName()).thenReturn(RECIPE_NAME);
    when(configuration2.getRepositoryName()).thenReturn(REPOSITORY_NAME_2);
    when(configuration2.getRecipeName()).thenReturn(RECIPE_NAME_2);
    when(configuration3.getRepositoryName()).thenReturn(REPOSITORY_NAME_3);
    when(configuration3.getRecipeName()).thenReturn(RECIPE_NAME_3);

    when(store.list()).thenReturn(asList(configuration, configuration2, configuration3));

    when(repository.getFormat()).thenReturn(repositoryFormat);
    when(repository2.getFormat()).thenReturn(repositoryFormat2);
    when(repository3.getFormat()).thenReturn(repositoryFormat3);

    when(repository.getName()).thenReturn(REPOSITORY_NAME);
    when(repository2.getName()).thenReturn(REPOSITORY_NAME_2);
    when(repository3.getName()).thenReturn(REPOSITORY_NAME_3);
    when(groupRepository.getName()).thenReturn(REPOSITORY_GROUP_NAME);

    when(repositoryFormat.getValue()).thenReturn(REPOSITORY_FORMAT);
    when(repositoryFormat2.getValue()).thenReturn(REPOSITORY_FORMAT_2);
    when(repositoryFormat3.getValue()).thenReturn(REPOSITORY_FORMAT_3);

    when(repositoryManager.findContainingGroups(REPOSITORY_NAME))
        .thenReturn(Collections.singletonList(REPOSITORY_GROUP_NAME));

    Map<String, Recipe> recipes = ImmutableMap.of(RECIPE_NAME, recipe, RECIPE_NAME_2, recipe2, RECIPE_NAME_3, recipe3);
    underTest = new RepositoryManagerRESTAdapterImpl(
        repositoryManager, store, recipes, repositoryPermissionChecker, null);
  }

  @Test
  public void getRepository_allPermissions() throws Exception {
    configurePermissions(repository, PERMIT_BROWSE);
    assertThat(underTest.getRepository(REPOSITORY_NAME), is(repository));
  }

  @Test
  public void getRepository_browseOnly() throws Exception {
    configurePermissions(repository, PERMIT_BROWSE);
    assertThat(underTest.getRepository(REPOSITORY_NAME), is(repository));
  }

  @Test
  public void getRepository_readOnlyReturnsForbidden() throws Exception {
    configurePermissions(repository, !PERMIT_BROWSE);

    WebApplicationException exception = assertThrows(WebApplicationException.class, () -> {
      underTest.getRepository(REPOSITORY_NAME);
    });
    assertThat(exception.getResponse().getStatus(), is(403));
  }

  @Test
  public void getRepository_cannotReadOrBrowse() {
    configurePermissions(repository, !PERMIT_BROWSE);
    
    WebApplicationException exception = assertThrows(WebApplicationException.class, () -> {
      underTest.getRepository(REPOSITORY_NAME);
    });
    assertThat(exception.getResponse().getStatus(), is(403));
  }

  private void configurePermissions(final Repository repository, final boolean permitBrowse) {
    when(repositoryPermissionChecker.userCanReadOrBrowse(repository)).thenReturn(permitBrowse);
  }

  @Test
  public void getRepository_notFound() {
    assertThrows(NotFoundException.class, () -> {
      underTest.getRepository("notFound");
    });
  }

  @Test
  public void getRepository_null() {
    WebApplicationException exception = assertThrows(WebApplicationException.class, () -> {
      underTest.getRepository(null);
    });
    assertThat(exception.getResponse().getStatus(), is(422));
  }

  @Test
  public void getReadableRepository_notFound() {
    assertThrows(NotFoundException.class, () -> {
      underTest.getReadableRepository("notFound");
    });
  }

  @Test
  public void getReadableRepository_null() {
    WebApplicationException exception = assertThrows(WebApplicationException.class, () -> {
      underTest.getReadableRepository(null);
    });
    assertThat(exception.getResponse().getStatus(), is(422));
  }

  @Test
  public void getReadableRepository_cannotReadOrBrowse() {
    configurePermissions(repository, false);
    configurePermissions(groupRepository, false);

    WebApplicationException exception = assertThrows(WebApplicationException.class, () -> {
      underTest.getReadableRepository(repository.getName());
    });
    assertThat(exception.getResponse().getStatus(), is(403));
  }

  @Test
  public void getReadableRepository_canReadOrBrowse() {
    configurePermissions(repository, true);
    configurePermissions(groupRepository, false);

    Repository actual = underTest.getReadableRepository(repository.getName());
    assertThat(actual, is(repository));
  }

  @Test
  public void getReadableRepository_canReadOrBrowse_asGroupMember() {
    configurePermissions(repository, false);
    configurePermissions(groupRepository, true);

    Repository actual = underTest.getReadableRepository(repository.getName());
    assertThat(actual, is(repository));
  }

  @Test
  public void getRepositories() {
    when(repositoryPermissionChecker.userCanBrowseRepositories(configuration, configuration2, configuration3))
        .thenReturn(asList(configuration, configuration2));

    RepositoryXO xo = new RepositoryXO();
    xo.setName(REPOSITORY_NAME);
    xo.setFormat(REPOSITORY_FORMAT);
    xo.setUrl("http://nexus-url/repository/repoName");
    xo.setAttributes(Collections.emptyMap());

    RepositoryXO xo2 = new RepositoryXO();
    xo2.setName(REPOSITORY_NAME_2);
    xo2.setFormat(REPOSITORY_FORMAT_2);
    xo2.setUrl("http://nexus-url/repository/repoNameTwo");
    xo2.setAttributes(Collections.emptyMap());

    assertThat(underTest.getRepositories(), is(asList(xo, xo2)));
  }

  @Test
  public void findContainingGroupsShouldDelegateToRepositoryManager() {
    String repositoryName = "aRepository";
    List<String> repositoryNames = asList("group1", "group2");
    when(repositoryManager.findContainingGroups(repositoryName)).thenReturn(repositoryNames);

    List<String> containingGroups = underTest.findContainingGroups(repositoryName);

    assertThat(containingGroups, is(repositoryNames));
  }
  
  /**
   * Test method demonstrating Java 21 record patterns with RepositoryXO objects.
   * This test verifies that we can correctly extract and validate fields from RepositoryXO
   * using the new pattern matching syntax introduced in Java 21.
   */
  @Test
  public void testRepositoryXOWithRecordPatterns() {
    // Create test repositories with different formats
    RepositoryXO repositoryXO1 = createRepositoryXO(REPOSITORY_NAME, REPOSITORY_FORMAT, "http://nexus-url/repository/repoName");
    RepositoryXO repositoryXO2 = createRepositoryXO(REPOSITORY_NAME_2, REPOSITORY_FORMAT_2, "http://nexus-url/repository/repoNameTwo");
    RepositoryXO repositoryXO3 = createRepositoryXO(REPOSITORY_NAME_3, REPOSITORY_FORMAT_3, "http://nexus-url/repository/repoNameThree");
    
    // Using Java 21 pattern matching with instanceof to extract fields directly
    // Note: While RepositoryXO is not a record class, we're demonstrating the pattern matching syntax
    // that would be used with records. In a real application with record classes, this would extract
    // the components directly.
    if (repositoryXO1 instanceof RepositoryXO xo) {
      // Using pattern variable 'xo' to access fields
      assertThat(xo.getName(), is(REPOSITORY_NAME));
      assertThat(xo.getFormat(), is(REPOSITORY_FORMAT));
      
      // Nested pattern matching with Map entries - demonstrating type patterns with conditional AND
      Map<String, Object> attributes = xo.getAttributes();
      if (attributes instanceof Map<String, Object> map && map.isEmpty()) {
        // Successfully verified that attributes is an empty map
        // This demonstrates nested pattern matching with type checking and a condition
      } else {
        fail("Expected empty attributes map");
      }
    }
    
    // Create a list of repositories to demonstrate pattern matching in loops
    List<RepositoryXO> repositories = List.of(repositoryXO1, repositoryXO2, repositoryXO3);
    
    // Count repositories by format using pattern matching in a loop
    int standardFormatCount = 0;
    int extendedFormatCount = 0;
    int specialFormatCount = 0;
    
    for (Object repo : repositories) {
      // Pattern matching in if statements
      if (repo instanceof RepositoryXO xo && REPOSITORY_FORMAT.equals(xo.getFormat())) {
        standardFormatCount++;
      } else if (repo instanceof RepositoryXO xo && REPOSITORY_FORMAT_2.equals(xo.getFormat())) {
        extendedFormatCount++;
      } else if (repo instanceof RepositoryXO xo && REPOSITORY_FORMAT_3.equals(xo.getFormat())) {
        specialFormatCount++;
      }
    }
    
    assertThat(standardFormatCount, is(1));
    assertThat(extendedFormatCount, is(1));
    assertThat(specialFormatCount, is(1));
    
    // Using switch expression with pattern matching to handle different repository formats
    // This demonstrates the power of switch expressions with patterns in Java 21
    for (RepositoryXO repo : repositories) {
      String formatDescription = switch (repo) {
        case RepositoryXO xo when REPOSITORY_FORMAT.equals(xo.getFormat()) -> {
          // We can have complex code blocks in switch cases
          String desc = "Standard format repository: " + xo.getName();
          yield desc; // Use yield for returning values from blocks
        }
        case RepositoryXO xo when REPOSITORY_FORMAT_2.equals(xo.getFormat()) -> "Extended format repository: " + xo.getName();
        case RepositoryXO xo when REPOSITORY_FORMAT_3.equals(xo.getFormat()) -> "Special format repository: " + xo.getName();
        default -> "Unknown format repository";
      };
      
      // Verify the correct format description based on the repository name
      if (repo.getName().equals(REPOSITORY_NAME)) {
        assertThat(formatDescription, is("Standard format repository: " + REPOSITORY_NAME));
      } else if (repo.getName().equals(REPOSITORY_NAME_2)) {
        assertThat(formatDescription, is("Extended format repository: " + REPOSITORY_NAME_2));
      } else if (repo.getName().equals(REPOSITORY_NAME_3)) {
        assertThat(formatDescription, is("Special format repository: " + REPOSITORY_NAME_3));
      }
    }
  }
  
  /**
   * Helper method to create a RepositoryXO with the specified properties.
   */
  private RepositoryXO createRepositoryXO(String name, String format, String url) {
    RepositoryXO repositoryXO = new RepositoryXO();
    repositoryXO.setName(name);
    repositoryXO.setFormat(format);
    repositoryXO.setUrl(url);
    repositoryXO.setAttributes(Collections.emptyMap());
    return repositoryXO;
  }
}