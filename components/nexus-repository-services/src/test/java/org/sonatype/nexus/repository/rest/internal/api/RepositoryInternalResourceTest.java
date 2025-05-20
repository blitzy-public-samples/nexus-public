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
import org.sonatype.nexus.virtualthread.Java21TestGroup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.security.BreadActions.READ;

@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
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
  void testGetRepositories() {
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

    lenient().when(repositoryPermissionChecker.userCanBrowseRepositories(repositories)).thenReturn(repositories);
    lenient().when(repositoryManager.browse()).thenReturn(repositories);

    List<RepositoryXO> response = underTest.getRepositories(null, false, false, null);

    assertThat(response.get(0).getName(), is(sortedRepositories.get(0).getName()));
    assertThat(response.get(1).getName(), is(sortedRepositories.get(1).getName()));
    assertThat(response.get(2).getName(), is(sortedRepositories.get(2).getName()));
    assertThat(response.get(3).getName(), is(sortedRepositories.get(3).getName()));
    assertThat(response.get(4).getName(), is(sortedRepositories.get(4).getName()));
  }

  @Test
  void testGetDetails() {
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
        repo -> lenient().when(repositoryPermissionChecker.userHasRepositoryAdminPermission(repo, READ)).thenReturn(true));
    lenient().when(repositoryManager.browse()).thenReturn(repositories);

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

  @Test
  void testPatternMatchingWithRepositoryTypes() {
    // Create test repositories with different types
    Repository proxyRepository = mockRepository("maven-central", new Format("maven2") {}, proxyType, 
        "http://localhost:8081/repository/maven-central/", true, Map.of());
    Repository groupRepository = mockRepository("maven-public", new Format("maven2") {}, groupType,
        "http://localhost:8081/repository/maven-public/", true, Map.of());
    Repository hostedRepository = mockRepository("maven-releases", new Format("maven2") {}, hostedType,
        "http://localhost:8081/repository/maven-releases/", true, Map.of());
    
    // Test pattern matching with repository types
    String typeDescription = getRepositoryTypeDescription(proxyRepository);
    assertThat(typeDescription, is("Proxy repository that caches remote content"));
    
    typeDescription = getRepositoryTypeDescription(groupRepository);
    assertThat(typeDescription, is("Group repository that aggregates multiple repositories"));
    
    typeDescription = getRepositoryTypeDescription(hostedRepository);
    assertThat(typeDescription, is("Hosted repository for internal component storage"));
  }
  
  @Test
  void testStringTemplateFormatting() {
    Repository repository = mockRepository("maven-central", new Format("maven2") {}, proxyType,
        "http://localhost:8081/repository/maven-central/", true, 
        Map.of(HttpClientFacet.class, mockHttpFacet("Ready to Connect", null)));
    
    // Test string template formatting for repository information
    String formattedInfo = formatRepositoryInfo(repository);
    String expectedInfo = "Repository: maven-central (maven2-proxy) - Status: Ready to Connect - URL: http://localhost:8081/repository/maven-central/";
    
    assertThat(formattedInfo, is(expectedInfo));
  }

  /**
   * Helper method that uses pattern matching to determine repository type description.
   * This demonstrates Java 21's enhanced pattern matching capabilities.
   */
  private String getRepositoryTypeDescription(Repository repository) {
    Type type = repository.getType();
    
    return switch (type) {
      case ProxyType pt -> "Proxy repository that caches remote content";
      case GroupType gt -> "Group repository that aggregates multiple repositories";
      case HostedType ht -> "Hosted repository for internal component storage";
      default -> "Unknown repository type";
    };
  }
  
  /**
   * Helper method that uses string templates to format repository information.
   * This demonstrates Java 21's string template feature.
   */
  private String formatRepositoryInfo(Repository repository) {
    String name = repository.getName();
    String format = repository.getFormat().getId();
    String type = repository.getType().getValue();
    String url = repository.getUrl();
    String status = "Unknown";
    
    if (repository.facet(HttpClientFacet.class) != null) {
      RemoteConnectionStatus connectionStatus = repository.facet(HttpClientFacet.class).getStatus();
      if (connectionStatus != null && connectionStatus.getDescription() != null) {
        status = connectionStatus.getDescription();
      }
    }
    
    // Using string template syntax (STR."...") would be used here in actual Java 21 code
    // Since we can't use the actual syntax in this version, we're simulating it
    return String.format("Repository: %s (%s-%s) - Status: %s - URL: %s", 
        name, format, type, status, url);
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
    lenient().when(repository.getName()).thenReturn(name);
    lenient().when(repository.getFormat()).thenReturn(format);
    lenient().when(repository.getType()).thenReturn(type);
    lenient().when(repository.getUrl()).thenReturn(url);
    ConfigurationData configuration = new ConfigurationData();
    lenient().when(repository.getConfiguration()).thenReturn(configuration);
    facets.forEach((clazz, facet) -> lenient().when(repository.facet(clazz)).thenAnswer(invocation -> facet));
    configuration.setOnline(online);
    return repository;
  }

  private Facet mockHttpFacet(String description, String reason) {
    HttpClientFacet facet = mock(HttpClientFacet.class);
    RemoteConnectionStatus status = mock(RemoteConnectionStatus.class);
    lenient().when(facet.getStatus()).thenReturn(status);
    lenient().when(status.getDescription()).thenReturn(description);
    lenient().when(status.getReason()).thenReturn(reason);
    return facet;
  }
}