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
package org.sonatype.nexus.repository.rest.internal.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

// Import for Java 21 String Templates
import static java.lang.StringTemplate.STR;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.Facet;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Recipe;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.config.internal.ConfigurationData;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.httpclient.RemoteConnectionStatus;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.rest.api.ApiRepositoryAdapter;
import org.sonatype.nexus.repository.rest.api.AuthorizingRepositoryManager;
import org.sonatype.nexus.repository.security.RepositoryPermissionChecker;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.types.ProxyType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.security.BreadActions.READ;

@ExtendWith(MockitoExtension.class)
public class RepositoryInternalResourceTest
    extends TestSupport
{
  @Mock
  private List<Format> formats;

  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private RepositoryPermissionChecker repositoryPermissionChecker;

  @Mock
  private List<Recipe> recipes;

  @Mock
  private AuthorizingRepositoryManager authorizingRepositoryManager;

  @Mock
  private Map<String, ApiRepositoryAdapter> convertersByFormat;

  @Mock
  private ApiRepositoryAdapter defaultAdapter;

  private final ProxyType proxyType = new ProxyType();

  private final GroupType groupType = new GroupType();

  private final HostedType hostedType = new HostedType();

  private RepositoryInternalResource underTest;

  @BeforeEach
  public void setup() {
    underTest = new RepositoryInternalResource(
        formats,
        repositoryManager,
        repositoryPermissionChecker,
        proxyType,
        recipes,
        authorizingRepositoryManager,
        convertersByFormat,
        defaultAdapter);
  }

  @Test
  void getRepositories() {
    Format maven2 = new Format("maven2")
    {
    };
    Format nuget = new Format("nuget")
    {
    };

    Repository mavenGroupRepository =
        mockRepository("maven-public", maven2, groupType, "http://localhost:8081/repository/maven-public/", true,
            Map.of());
    Repository mavenProxyRepository =
        mockRepository("maven-central", maven2, proxyType, "http://localhost:8081/repository/maven-central/", true,
            Map.of(HttpClientFacet.class, mockHttpFacet("Ready to Connect", null)));
    Repository nugetGroupRepository =
        mockRepository("nuget-group", nuget, groupType, "http://localhost:8081/repository/nuget-group/", true,
            Map.of());
    Repository nugetHostedRepository =
        mockRepository("nuget-hosted", nuget, hostedType, "http://localhost:8081/repository/nuget-hosted/", true,
            Map.of());
    Repository nugetProxyRepository =
        mockRepository("nuget.org-proxy", nuget, proxyType, "http://localhost:8081/repository/nuget.org-proxy/", true,
            Map.of(HttpClientFacet.class, mockHttpFacet("Ready to Connect", null)));

    List<Repository> repositories = List.of(
        nugetProxyRepository,
        mavenGroupRepository,
        mavenProxyRepository,
        nugetHostedRepository,
        nugetGroupRepository);

    List<Repository> sortedRepositories = List.of(
        mavenProxyRepository,
        mavenGroupRepository,
        nugetGroupRepository,
        nugetHostedRepository,
        nugetProxyRepository);

    when(repositoryPermissionChecker.userCanBrowseRepositories(repositories)).thenReturn(repositories);
    when(repositoryManager.browse()).thenReturn(repositories);

    List<RepositoryXO> response = underTest.getRepositories(null, false, false, null);

    assertThat(response.get(0).getName(), is(sortedRepositories.get(0).getName()));
    assertThat(response.get(1).getName(), is(sortedRepositories.get(1).getName()));
    assertThat(response.get(2).getName(), is(sortedRepositories.get(2).getName()));
    assertThat(response.get(3).getName(), is(sortedRepositories.get(3).getName()));
    assertThat(response.get(4).getName(), is(sortedRepositories.get(4).getName()));
  }

  @Test
  void getDetails() {
    Format maven2 = new Format("maven2")
    {
    };
    Format nuget = new Format("nuget")
    {
    };
    List<Repository> repositories = List.of(
        mockRepository("maven-central", maven2, proxyType, "http://localhost:8081/repository/maven-central/", true,
            Map.of(HttpClientFacet.class, mockHttpFacet("Ready to Connect", null))),
        mockRepository("maven-public", maven2, groupType, "http://localhost:8081/repository/maven-public/", true,
            Map.of()),
        mockRepository("maven-releases", maven2, hostedType, "http://localhost:8081/repository/maven-releases/", true,
            Map.of()),
        mockRepository("maven-snapshots", maven2, hostedType, "http://localhost:8081/repository/maven-snapshots/",
            false, Map.of()),
        mockRepository("nuget-group", nuget, groupType, "http://localhost:8081/repository/nuget-group/", true,
            Map.of()),
        mockRepository("nuget-hosted", nuget, hostedType, "http://localhost:8081/repository/nuget-hosted/", true,
            Map.of()),
        mockRepository("nuget.org-proxy", nuget, proxyType, "http://localhost:8081/repository/nuget.org-proxy/", true,
            Map.of(HttpClientFacet.class, mockHttpFacet("Remote Auto Blocked and Unavailable",
                "java.net.UnknownHostException: api.example.org: nodename nor servname provided, or not known"))));
    repositories.forEach(
        repo -> when(repositoryPermissionChecker.userHasRepositoryAdminPermission(repo, READ)).thenReturn(true));
    when(repositoryManager.browse()).thenReturn(repositories);

    List<RepositoryDetailXO> details = underTest.getRepositoryDetails();

    assertThat(details.get(0).getName(), is("maven-central"));
    assertThat(details.get(0).getType(), is("proxy"));
    assertThat(details.get(0).getFormat(), is("maven2"));
    assertThat(details.get(0).getUrl(), is("http://localhost:8081/repository/maven-central/"));
    assertThat(details.get(0).getStatus().isOnline(), is(true));
    assertThat(details.get(0).getStatus().getDescription(), is("Ready to Connect"));
    assertThat(details.get(0).getStatus().getReason(), is(nullValue()));

    assertThat(details.get(1).getName(), is("maven-public"));
    assertThat(details.get(1).getType(), is("group"));
    assertThat(details.get(1).getFormat(), is("maven2"));
    assertThat(details.get(1).getUrl(), is("http://localhost:8081/repository/maven-public/"));
    assertThat(details.get(1).getStatus().isOnline(), is(true));
    assertThat(details.get(1).getStatus().getDescription(), is(nullValue()));
    assertThat(details.get(1).getStatus().getReason(), is(nullValue()));

    assertThat(details.get(2).getName(), is("maven-releases"));
    assertThat(details.get(2).getType(), is("hosted"));
    assertThat(details.get(2).getFormat(), is("maven2"));
    assertThat(details.get(2).getUrl(), is("http://localhost:8081/repository/maven-releases/"));
    assertThat(details.get(2).getStatus().isOnline(), is(true));
    assertThat(details.get(2).getStatus().getDescription(), is(nullValue()));
    assertThat(details.get(2).getStatus().getReason(), is(nullValue()));

    assertThat(details.get(3).getName(), is("maven-snapshots"));
    assertThat(details.get(3).getType(), is("hosted"));
    assertThat(details.get(3).getFormat(), is("maven2"));
    assertThat(details.get(3).getUrl(), is("http://localhost:8081/repository/maven-snapshots/"));
    assertThat(details.get(3).getStatus().isOnline(), is(false));
    assertThat(details.get(3).getStatus().getDescription(), is(nullValue()));
    assertThat(details.get(3).getStatus().getReason(), is(nullValue()));

    assertThat(details.get(4).getName(), is("nuget-group"));
    assertThat(details.get(4).getType(), is("group"));
    assertThat(details.get(4).getFormat(), is("nuget"));
    assertThat(details.get(4).getUrl(), is("http://localhost:8081/repository/nuget-group/"));
    assertThat(details.get(4).getStatus().isOnline(), is(true));
    assertThat(details.get(4).getStatus().getDescription(), is(nullValue()));
    assertThat(details.get(4).getStatus().getReason(), is(nullValue()));

    assertThat(details.get(5).getName(), is("nuget-hosted"));
    assertThat(details.get(5).getType(), is("hosted"));
    assertThat(details.get(5).getFormat(), is("nuget"));
    assertThat(details.get(5).getUrl(), is("http://localhost:8081/repository/nuget-hosted/"));
    assertThat(details.get(5).getStatus().isOnline(), is(true));
    assertThat(details.get(5).getStatus().getDescription(), is(nullValue()));
    assertThat(details.get(5).getStatus().getReason(), is(nullValue()));

    assertThat(details.get(6).getName(), is("nuget.org-proxy"));
    assertThat(details.get(6).getType(), is("proxy"));
    assertThat(details.get(6).getFormat(), is("nuget"));
    assertThat(details.get(6).getUrl(), is("http://localhost:8081/repository/nuget.org-proxy/"));
    assertThat(details.get(6).getStatus().isOnline(), is(true));
    assertThat(details.get(6).getStatus().getDescription(), is("Remote Auto Blocked and Unavailable"));
    assertThat(details.get(6).getStatus().getReason(),
        is("java.net.UnknownHostException: api.example.org: nodename nor servname provided, or not known"));
  }

  private Repository mockRepository(
      String name,
      Format format,
      Type type,
      String url,
      boolean online,
      Map<Class<? extends Facet>, Facet> facets)
  {
    Repository repository = mock(Repository.class);
    when(repository.getName()).thenReturn(name);
    when(repository.getFormat()).thenReturn(format);
    when(repository.getType()).thenReturn(type);
    when(repository.getUrl()).thenReturn(url);
    ConfigurationData configuration = new ConfigurationData();
    when(repository.getConfiguration()).thenReturn(configuration);
    facets.forEach((clazz, facet) -> when(repository.facet(clazz)).thenAnswer(invocation -> facet));
    configuration.setOnline(online);
    return repository;
  }

  private Facet mockHttpFacet(String description, String reason) {
    HttpClientFacet facet = mock(HttpClientFacet.class);
    RemoteConnectionStatus status = mock(RemoteConnectionStatus.class);
    when(facet.getStatus()).thenReturn(status);
    when(status.getDescription()).thenReturn(description);
    when(status.getReason()).thenReturn(reason);
    return facet;
  }

  /**
   * Tests that Java 21 String Templates are properly handled in repository responses.
   * This verifies that the repository API can correctly process and display string templates
   * in repository metadata and status information.
   */
  @Test
  void stringTemplateInRepositoryResponses() {
    // Create a repository with a status description that uses a String Template
    Format maven2 = new Format("maven2") {};
    String repoName = "maven-central";
    String repoUrl = "http://localhost:8081/repository/maven-central/";
    
    // Create a status description using a String Template (Java 21 feature)
    String statusTemplate = STR."Repository \{repoName} is available at \{repoUrl}";
    
    Repository mavenProxyRepository = mockRepository(
        repoName, 
        maven2, 
        proxyType, 
        repoUrl, 
        true,
        Map.of(HttpClientFacet.class, mockHttpFacet(statusTemplate, null)));
    
    List<Repository> repositories = List.of(mavenProxyRepository);
    
    when(repositoryPermissionChecker.userCanBrowseRepositories(repositories)).thenReturn(repositories);
    when(repositoryManager.browse()).thenReturn(repositories);
    when(repositoryPermissionChecker.userHasRepositoryAdminPermission(mavenProxyRepository, READ)).thenReturn(true);
    
    // Get repository details which should include our String Template-generated description
    List<RepositoryDetailXO> details = underTest.getRepositoryDetails();
    
    // Verify the String Template was processed correctly
    assertNotNull(details);
    assertEquals(1, details.size());
    assertEquals(repoName, details.get(0).getName());
    assertEquals("Repository maven-central is available at http://localhost:8081/repository/maven-central/", 
        details.get(0).getStatus().getDescription());
  }

  /**
   * Tests repository operations with different thread configurations to validate behavior
   * with both platform and virtual threads (Java 21 feature).
   * This test compares performance and behavior between traditional platform threads
   * and the new lightweight virtual threads introduced in Java 21.
   */
  @Test
  void repositoryOperationsWithDifferentThreadConfigurations() throws Exception {
    // Create test repositories
    Format maven2 = new Format("maven2") {};
    Repository repository = mockRepository(
        "maven-central", 
        maven2, 
        proxyType, 
        "http://localhost:8081/repository/maven-central/", 
        true,
        Map.of());
    
    List<Repository> repositories = List.of(repository);
    when(repositoryManager.browse()).thenReturn(repositories);
    when(repositoryPermissionChecker.userCanBrowseRepositories(repositories)).thenReturn(repositories);
    
    // Configure thread factories for both platform and virtual threads
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Test parameters
    int taskCount = 100;
    int warmupRuns = 3;
    
    // Warm up to avoid JIT compilation effects
    for (int i = 0; i < warmupRuns; i++) {
      executeRepositoryOperations(platformThreadFactory, 10);
      executeRepositoryOperations(virtualThreadFactory, 10);
    }
    
    // Execute with platform threads and measure time
    long platformStart = System.nanoTime();
    executeRepositoryOperations(platformThreadFactory, taskCount);
    long platformDuration = System.nanoTime() - platformStart;
    
    // Execute with virtual threads and measure time
    long virtualStart = System.nanoTime();
    executeRepositoryOperations(virtualThreadFactory, taskCount);
    long virtualDuration = System.nanoTime() - virtualStart;
    
    // Log the results for informational purposes
    logger.info("Platform thread execution time: {} ns", platformDuration);
    logger.info("Virtual thread execution time: {} ns", virtualDuration);
    
    // Virtual threads should generally be more efficient for I/O-bound operations
    // This is not a strict requirement as performance can vary, but we expect
    // virtual threads to perform at least as well as platform threads
    assertThat("Virtual threads should be at least as efficient as platform threads",
        virtualDuration, lessThan(platformDuration * 1.5)); // Allow some margin
  }
  
  /**
   * Helper method to execute repository operations using the specified thread factory.
   * 
   * @param threadFactory The thread factory to use (platform or virtual)
   * @param taskCount The number of concurrent tasks to execute
   * @throws Exception If an error occurs during execution
   */
  private void executeRepositoryOperations(ThreadFactory threadFactory, int taskCount) throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Simulate repository operation by calling getRepositories
            underTest.getRepositories(null, false, false, null);
          } catch (Exception e) {
            errorCount.incrementAndGet();
            logger.error("Error during repository operation", e);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete with a reasonable timeout
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify all tasks completed successfully
      assertEquals(0, errorCount.get(), "All repository operations should complete without errors");
      assertEquals(true, completed, "All tasks should complete within the timeout period");
      
    } finally {
      executor.shutdown();
    }
  }
}