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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;

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
import org.sonatype.nexus.virtualthread.Java21TestGroup;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
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
  void setUp() throws Exception {
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
  void getRepositoryWithAllPermissions() throws Exception {
    configurePermissions(repository, PERMIT_BROWSE);
    assertThat(underTest.getRepository(REPOSITORY_NAME), is(repository));
  }

  @Test
  void getRepositoryWithBrowseOnly() throws Exception {
    configurePermissions(repository, PERMIT_BROWSE);
    assertThat(underTest.getRepository(REPOSITORY_NAME), is(repository));
  }

  @Test
  void getRepositoryWithReadOnlyReturnsForbidden() throws Exception {
    configurePermissions(repository, !PERMIT_BROWSE);

    try {
      underTest.getRepository(REPOSITORY_NAME);
      fail("should have thrown exception");
    }
    catch (WebApplicationException e) {
      assertThat(e.getResponse().getStatus(), is(403));
    }
  }

  @Test
  void getRepositoryCannotReadOrBrowse() {
    configurePermissions(repository, !PERMIT_BROWSE);
    try {
      underTest.getRepository(REPOSITORY_NAME);
      fail("should have thrown exception");
    }
    catch (WebApplicationException e) {
      assertThat(e.getResponse().getStatus(), is(403));
    }
  }

  private void configurePermissions(final Repository repository, final boolean permitBrowse) {
    when(repositoryPermissionChecker.userCanReadOrBrowse(repository)).thenReturn(permitBrowse);
  }

  @Test
  void getRepositoryNotFound() {
    try {
      underTest.getRepository("notFound");
      fail("should have thrown exception");
    }
    catch (NotFoundException e) {
      // expected
    }
  }

  @Test
  void getRepositoryNull() {
    try {
      underTest.getRepository(null);
      fail("should have thrown exception");
    }
    catch (WebApplicationException e) {
      assertThat(e.getResponse().getStatus(), is(422));
    }
  }

  @Test
  void getReadableRepositoryNotFound() {
    try {
      underTest.getReadableRepository("notFound");
      fail("should have thrown exception");
    }
    catch (NotFoundException e) {
      // expected
    }
  }

  @Test
  void getReadableRepositoryNull() {
    try {
      underTest.getReadableRepository(null);
      fail("should have thrown exception");
    }
    catch (WebApplicationException e) {
      assertThat(e.getResponse().getStatus(), is(422));
    }
  }

  @Test
  void getReadableRepositoryCannotReadOrBrowse() {
    configurePermissions(repository, false);
    configurePermissions(groupRepository, false);

    try {
      underTest.getReadableRepository(repository.getName());
      fail("should have thrown exception");
    }
    catch (WebApplicationException e) {
      assertThat(e.getResponse().getStatus(), is(403));
    }
  }

  @Test
  void getReadableRepositoryCanReadOrBrowse() {
    configurePermissions(repository, true);
    configurePermissions(groupRepository, false);

    Repository actual = underTest.getReadableRepository(repository.getName());
    assertThat(actual, is(repository));
  }

  @Test
  void getReadableRepositoryCanReadOrBrowseAsGroupMember() {
    configurePermissions(repository, false);
    configurePermissions(groupRepository, true);

    Repository actual = underTest.getReadableRepository(repository.getName());
    assertThat(actual, is(repository));
  }

  @Test
  void getRepositories() {
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
  void findContainingGroupsShouldDelegateToRepositoryManager() {
    String repositoryName = "aRepository";
    List<String> repositoryNames = asList("group1", "group2");
    when(repositoryManager.findContainingGroups(repositoryName)).thenReturn(repositoryNames);

    List<String> containingGroups = underTest.findContainingGroups(repositoryName);

    assertThat(containingGroups, is(repositoryNames));
  }
  
  @Test
  void concurrentRepositoryOperationsWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("repo-test-", 0).factory();
    
    // Create an executor service with virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int numTasks = 100;
    AtomicInteger successCount = new AtomicInteger(0);
    
    try {
      // Configure permissions to allow browsing
      configurePermissions(repository, true);
      
      // Submit multiple concurrent tasks to access the repository
      List<Future<?>> futures = new java.util.ArrayList<>();
      
      for (int i = 0; i < numTasks; i++) {
        futures.add(executor.submit(() -> {
          try {
            Repository repo = underTest.getRepository(REPOSITORY_NAME);
            if (repo != null && repo.equals(repository)) {
              successCount.incrementAndGet();
            }
            return repo;
          } catch (Exception e) {
            // Log and rethrow
            System.err.println("Error in virtual thread: " + e.getMessage());
            throw new RuntimeException(e);
          }
        }));
      }
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get(); // This will throw an exception if the task failed
      }
      
      // Verify all tasks completed successfully
      assertThat(successCount.get(), is(numTasks));
      
    } finally {
      executor.shutdown();
    }
  }
}