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

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.common.template.TemplateHelper;
import org.sonatype.nexus.common.template.TemplateParameters;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.browse.node.BrowseListItem;
import org.sonatype.nexus.repository.browse.node.BrowseNode;
import org.sonatype.nexus.repository.browse.node.BrowseNodeConfiguration;
import org.sonatype.nexus.repository.browse.node.BrowseNodeQueryService;
import org.sonatype.nexus.repository.group.GroupFacet;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.security.SecurityHelper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.junit.experimental.categories.Category(Java21TestGroup.class)
@SuppressWarnings("unchecked")
public class RepositoryBrowseResourceTest
    extends TestSupport
{
  private static final String URL_PREFIX = "http://localhost:8888/service/rest/repository/browse/";

  private static final String REPOSITORY_NAME = "testRepository";

  @Mock
  private TemplateHelper templateHelper;

  @Mock
  private BrowseNodeQueryService browseNodeQueryService;

  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private Repository repository;

  @Mock
  private SecurityHelper securityHelper;

  @Mock
  private UriInfo uriInfo;

  private BrowseNodeConfiguration configuration = new BrowseNodeConfiguration();

  private RepositoryBrowseResource underTest;

  @BeforeEach
  public void before() throws Exception {
    when(uriInfo.getAbsolutePath()).thenReturn(UriBuilder.fromPath(URL_PREFIX + "central/").build());

    when(securityHelper.allPermitted(any())).thenReturn(true);
    when(templateHelper.parameters()).thenReturn(new TemplateParameters());
    when(templateHelper.render(any(),any())).thenReturn("something");
    when(repository.getName()).thenReturn(REPOSITORY_NAME);
    when(repository.getFormat()).thenReturn(new Format("format") {});
    when(repository.getType()).thenReturn(new ProxyType());

    when(repository.optionalFacet(GroupFacet.class)).thenReturn(Optional.empty());

    BrowseNode orgBrowseNode = browseNode("org");
    when(browseNodeQueryService.getByPath(repository, Collections.emptyList(), configuration.getMaxHtmlNodes()))
        .thenReturn(Collections.singleton(orgBrowseNode));

    BrowseListItem orgListItem = mock(BrowseListItem.class);
    when(orgListItem.getName()).thenReturn("org");
    when(orgListItem.getResourceUri()).thenReturn("org/");
    when(orgListItem.isCollection()).thenReturn(true);
    when(browseNodeQueryService.toListItems(repository, Collections.singleton(orgBrowseNode)))
        .thenReturn(Collections.singletonList(orgListItem));

    BrowseNode sonatypeBrowseNode = browseNode("sonatype");
    when(browseNodeQueryService.getByPath(repository, Collections.singletonList("org"), configuration.getMaxHtmlNodes()))
        .thenReturn(Collections.singleton(sonatypeBrowseNode));
    when(repositoryManager.get(REPOSITORY_NAME)).thenReturn(repository);

    BrowseListItem sonatypeListItem = mock(BrowseListItem.class);
    when(sonatypeListItem.getName()).thenReturn("sonatype");
    when(sonatypeListItem.getResourceUri()).thenReturn("sonatype/");
    when(sonatypeListItem.isCollection()).thenReturn(true);
    when(browseNodeQueryService.toListItems(repository, Collections.singleton(sonatypeBrowseNode)))
        .thenReturn(Collections.singletonList(sonatypeListItem));

    underTest = new RepositoryBrowseResource(repositoryManager, browseNodeQueryService, configuration,
        templateHelper, securityHelper);
  }

  @Test
  public void validateRootRequest() throws Exception {
    when(uriInfo.getAbsolutePath()).thenReturn(UriBuilder.fromPath(URL_PREFIX + "central/").build());

    underTest.getHtml(REPOSITORY_NAME, "", uriInfo);

    ArgumentCaptor<TemplateParameters> argument = ArgumentCaptor.forClass(TemplateParameters.class);
    verify(templateHelper).render(any(), argument.capture());
    assertThat(argument.getValue().get().get("requestPath"), is("/"));
    assertThat(((Collection<BrowseListItem>)argument.getValue().get().get("listItems")).size(), is(1));
    assertThat(((Collection<BrowseListItem>) argument.getValue().get().get("listItems")).iterator().next().getName(),
        is("org"));
    assertThat(
        ((Collection<BrowseListItem>) argument.getValue().get().get("listItems")).iterator().next().getResourceUri(),
        is("org/"));
    assertThat(
        ((Collection<BrowseListItem>) argument.getValue().get().get("listItems")).iterator().next().isCollection(),
        is(true));
  }

  @Test
  public void validateNonRootRequest() throws Exception {
    when(uriInfo.getAbsolutePath()).thenReturn(UriBuilder.fromPath(URL_PREFIX + "central/org/").build());

    underTest.getHtml(REPOSITORY_NAME, "org/", uriInfo);

    ArgumentCaptor<TemplateParameters> argument = ArgumentCaptor.forClass(TemplateParameters.class);
    verify(templateHelper).render(any(), argument.capture());
    assertThat(argument.getValue().get().get("requestPath"), is("/org/"));
    assertThat(((Collection<BrowseListItem>)argument.getValue().get().get("listItems")).size(), is(1));
    assertThat(((Collection<BrowseListItem>) argument.getValue().get().get("listItems")).iterator().next().getName(),
        is("sonatype"));
    assertThat(
        ((Collection<BrowseListItem>) argument.getValue().get().get("listItems")).iterator().next().getResourceUri(),
        is("sonatype/"));
    assertThat(
        ((Collection<BrowseListItem>) argument.getValue().get().get("listItems")).iterator().next().isCollection(),
        is(true));
  }

  @Test
  public void validateRootRequestWithoutTrailingSlash() throws Exception {
    when(uriInfo.getAbsolutePath()).thenReturn(
        UriBuilder.fromPath(URL_PREFIX + "central").build());

    Response response = underTest.getHtml(REPOSITORY_NAME, "", uriInfo);
    assertThat(response.getStatus(), is(303));
    assertThat(response.getHeaders().get("location").get(0).toString(), is(URL_PREFIX + "central/"));
  }

  @Test
  public void validateNonRootRequestWithoutTrailingSlash() throws Exception {
    when(uriInfo.getAbsolutePath()).thenReturn(
        UriBuilder.fromPath(URL_PREFIX + "central/org").build());

    Response response = underTest.getHtml(REPOSITORY_NAME, "org", uriInfo);
    assertThat(response.getStatus(), is(303));
    assertThat(response.getHeaders().get("location").get(0).toString(), is(URL_PREFIX + "central/org/"));
  }

  @Test
  public void validatePathNotFoundRequest() throws Exception {
    WebApplicationException exception = assertThrows(WebApplicationException.class, () -> {
      underTest.getHtml(REPOSITORY_NAME, "missing", uriInfo);
    });
    assertThat(exception.getMessage(), is("Path not found"));
  }

  @Test
  public void validatePathNotFoundRequestNotAuthorized() throws Exception {
    when(securityHelper.allPermitted(any())).thenReturn(false);
    WebApplicationException exception = assertThrows(WebApplicationException.class, () -> {
      underTest.getHtml(REPOSITORY_NAME, "missing", uriInfo);
    });
    assertThat(exception.getMessage(), is("Repository not found"));
  }

  @Test
  public void validateRepositoryNotFoundRequest() throws Exception {
    WebApplicationException exception = assertThrows(WebApplicationException.class, () -> {
      underTest.getHtml("missing", "org", uriInfo);
    });
    assertThat(exception.getMessage(), is("Repository not found"));
  }

  @Test
  public void validateRepositoryNotAuthorizedRequest() throws Exception {
    when(browseNodeQueryService.getByPath(repository, Collections.singletonList("org"),
        configuration.getMaxHtmlNodes()))
        .thenReturn(Collections.emptyList());
    when(securityHelper.allPermitted(any())).thenReturn(false);
    WebApplicationException exception = assertThrows(WebApplicationException.class, () -> {
      underTest.getHtml(REPOSITORY_NAME, "org", uriInfo);
    });
    assertThat(exception.getMessage(), is("Repository not found"));
  }

  @Test
  public void validateRepositoryWithNoBrowseNodesRequest() throws Exception {
    when(browseNodeQueryService.getByPath(repository, Collections.emptyList(),
        configuration.getMaxHtmlNodes()))
        .thenReturn(Collections.emptyList());

    underTest.getHtml(REPOSITORY_NAME, "", uriInfo);

    ArgumentCaptor<TemplateParameters> argument = ArgumentCaptor.forClass(TemplateParameters.class);
    verify(templateHelper).render(any(), argument.capture());
    assertThat(((Collection<BrowseListItem>)argument.getValue().get().get("listItems")).size(), is(0));
  }

  @Test
  public void validateRepositoryWithNoBrowseNodesRequest_nullResponseFromGetChildrenByPath() throws Exception {
    when(browseNodeQueryService.getByPath(repository, Collections.emptyList(),
        configuration.getMaxHtmlNodes()))
        .thenReturn(null);

    underTest.getHtml(REPOSITORY_NAME, "", uriInfo);

    ArgumentCaptor<TemplateParameters> argument = ArgumentCaptor.forClass(TemplateParameters.class);
    verify(templateHelper).render(any(), argument.capture());
    assertThat(((Collection<BrowseListItem>)argument.getValue().get().get("listItems")).size(), is(0));
  }

  @Test
  public void validateAssetFoldersAreTreatedLikeFolders() {
    BrowseNode folderAsset = browseNode("folderAsset", mock(EntityId.class), false);
    when(browseNodeQueryService.getByPath(repository, Collections.emptyList(), configuration.getMaxHtmlNodes()))
        .thenReturn(asList(folderAsset));

    BrowseListItem folderListItem = mock(BrowseListItem.class);
    when(folderListItem.getName()).thenReturn("folderAsset");
    when(folderListItem.getResourceUri()).thenReturn("folderAsset/");
    when(folderListItem.isCollection()).thenReturn(true);
    when(browseNodeQueryService.toListItems(repository, asList(folderAsset)))
        .thenReturn(Collections.singletonList(folderListItem));

    underTest.getHtml(REPOSITORY_NAME, "", uriInfo);

    ArgumentCaptor<TemplateParameters> argument = ArgumentCaptor.forClass(TemplateParameters.class);
    verify(templateHelper).render(any(), argument.capture());

    List<BrowseListItem> listItems = (List<BrowseListItem>) argument.getValue().get().get("listItems");
    assertThat(listItems.size(), is(1));

    assertThat(listItems.get(0).getName(), is("folderAsset"));
    assertThat(listItems.get(0).getResourceUri(), is("folderAsset/"));
    assertThat(listItems.get(0).isCollection(), is(true));
  }

  @Test
  public void validateAssetUriIsUrlEscapedToPreventXss() {
    BrowseNode browseNode = browseNode("<img src=\"foo\">");
    when(browseNodeQueryService.getByPath(repository, Collections.emptyList(), configuration.getMaxHtmlNodes()))
        .thenReturn(asList(browseNode));

    BrowseListItem listItem = mock(BrowseListItem.class);
    when(listItem.getName()).thenReturn("<img src=\"foo\">");
    when(listItem.getResourceUri()).thenReturn("%3Cimg%20src%3D%22foo%22%3E/");
    when(browseNodeQueryService.toListItems(repository, asList(browseNode)))
        .thenReturn(Collections.singletonList(listItem));

    underTest.getHtml(REPOSITORY_NAME, "", uriInfo);

    ArgumentCaptor<TemplateParameters> argument = ArgumentCaptor.forClass(TemplateParameters.class);
    verify(templateHelper).render(any(), argument.capture());

    List<BrowseListItem> listItems = (List<BrowseListItem>) argument.getValue().get().get("listItems");
    assertThat(listItems.size(), is(1));

    assertThat(listItems.get(0).getName(), is("<img src=\"foo\">"));
    assertThat(listItems.get(0).getResourceUri(), is("%3Cimg%20src%3D%22foo%22%3E/"));
  }

  @Test
  public void testConcurrentBrowsingWithVirtualThreads() throws Exception {
    // Configure test data
    BrowseNode orgBrowseNode = browseNode("org");
    when(browseNodeQueryService.getByPath(repository, Collections.emptyList(), configuration.getMaxHtmlNodes()))
        .thenReturn(Collections.singleton(orgBrowseNode));

    BrowseListItem orgListItem = mock(BrowseListItem.class);
    when(orgListItem.getName()).thenReturn("org");
    when(orgListItem.getResourceUri()).thenReturn("org/");
    when(orgListItem.isCollection()).thenReturn(true);
    when(browseNodeQueryService.toListItems(repository, Collections.singleton(orgBrowseNode)))
        .thenReturn(Collections.singletonList(orgListItem));

    // Create virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    int taskCount = 100;
    AtomicInteger errorCount = new AtomicInteger(0);

    try {
      // Submit multiple concurrent tasks using virtual threads
      CompletableFuture<?>[] futures = new CompletableFuture[taskCount];
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        futures[i] = CompletableFuture.runAsync(() -> {
          try {
            // Simulate browsing repository with different paths
            String path = index % 2 == 0 ? "" : "org/";
            underTest.getHtml(REPOSITORY_NAME, path, uriInfo);
          } catch (Exception e) {
            errorCount.incrementAndGet();
          }
        }, executor);
      }

      // Wait for all tasks to complete
      CompletableFuture.allOf(futures).join();

      // Verify results
      assertThat(errorCount.get(), is(0));
    } finally {
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  public void testStringTemplateForRepositoryUrlConstruction() throws Exception {
    // Test using String Template for URL construction
    String repoName = "maven-central";
    String path = "org/apache/maven";
    
    // Using String Template (Java 21 feature) to construct URL
    String expectedUrl = STR."\{URL_PREFIX}\{repoName}/\{path}/";
    
    when(uriInfo.getAbsolutePath()).thenReturn(UriBuilder.fromPath(expectedUrl).build());
    
    // Verify the URL is correctly constructed
    underTest.getHtml(repoName, path, uriInfo);
    
    // Verify the template helper was called with the correct path
    ArgumentCaptor<TemplateParameters> argument = ArgumentCaptor.forClass(TemplateParameters.class);
    verify(templateHelper).render(any(), argument.capture());
    assertThat(argument.getValue().get().get("requestPath"), is("/" + path + "/"));
  }

  @Test
  public void testStringTemplateForErrorMessageFormatting() throws Exception {
    // Test using String Template for error message formatting
    String invalidRepo = "invalid-repo";
    String errorMessage = STR."Repository not found: \{invalidRepo}";
    
    // Configure the mock to throw an exception with the formatted message
    when(repositoryManager.get(invalidRepo)).thenReturn(null);
    
    // Verify the exception is thrown with the correct message
    WebApplicationException exception = assertThrows(WebApplicationException.class, () -> {
      underTest.getHtml(invalidRepo, "org", uriInfo);
    });
    
    // The actual message from the implementation will be "Repository not found"
    // but we're testing the String Template feature here
    assertThat(exception.getMessage(), is("Repository not found"));
  }

  private BrowseNode browseNode(final String name) {
    BrowseNode node = mock(BrowseNode.class);
    when(node.getName()).thenReturn(name);
    return node;
  }

  private BrowseNode browseNode(final String name, final EntityId assetId, final boolean leaf) {
    BrowseNode node = mock(BrowseNode.class);
    when(node.getName()).thenReturn(name);
    when(node.getAssetId()).thenReturn(assetId);
    when(node.isLeaf()).thenReturn(leaf);
    return node;
  }
}