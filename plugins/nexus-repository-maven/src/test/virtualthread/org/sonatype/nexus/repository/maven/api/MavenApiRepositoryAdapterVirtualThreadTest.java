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
package org.sonatype.nexus.repository.maven.api;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.stream.Collectors;

import org.sonatype.nexus.common.app.BaseUrlHolder;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.manager.internal.RepositoryImpl;
import org.sonatype.nexus.repository.maven.ContentDisposition;
import org.sonatype.nexus.repository.maven.LayoutPolicy;
import org.sonatype.nexus.repository.maven.VersionPolicy;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;
import org.sonatype.nexus.repository.rest.api.model.AbstractApiRepository;
import org.sonatype.nexus.repository.rest.api.model.SimpleApiGroupRepository;
import org.sonatype.nexus.repository.routing.RoutingRuleStore;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.testsuite.testsupport.VirtualThreadTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import static com.google.common.collect.Maps.newHashMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the {@link MavenApiRepositoryAdapter} using Java 21 Virtual Threads to ensure it correctly transforms
 * Nexus Repository instances into their REST API model representations when executed concurrently in a
 * Virtual Thread environment.
 */
public class MavenApiRepositoryAdapterVirtualThreadTest
    extends VirtualThreadTestSupport
{
  private MavenApiRepositoryAdapter underTest;

  @Mock
  private RoutingRuleStore routingRuleStore;

  @BeforeEach
  public void setup() {
    underTest = new MavenApiRepositoryAdapter(routingRuleStore);
    BaseUrlHolder.set("http://nexus-url", "");
  }

  @Test
  public void testAdapt_groupRepository() throws Exception {
    // No maven specific props so simple smoke test
    Repository repository = createRepository(new GroupType());
    Configuration configuration = repository.getConfiguration();
    configuration.attributes("group").set("memberNames", Arrays.asList("a", "b"));
    repository.update(configuration);

    SimpleApiGroupRepository groupRepository = (SimpleApiGroupRepository) underTest.adapt(repository);
    assertRepository(groupRepository, "group", true);
  }

  @Test
  public void testAdapt_hostedRepository() throws Exception {
    Repository repository = createRepository(new HostedType(), LayoutPolicy.STRICT, VersionPolicy.MIXED, ContentDisposition.INLINE);

    MavenHostedApiRepository hostedRepository = (MavenHostedApiRepository) underTest.adapt(repository);
    assertRepository(hostedRepository, "hosted", true);
    assertThat(hostedRepository.getMaven().getLayoutPolicy(), is("STRICT"));
    assertThat(hostedRepository.getMaven().getVersionPolicy(), is("MIXED"));
    assertThat(hostedRepository.getMaven().getContentDisposition(), is("INLINE"));
    // Check fields are populated, actual values validated with SimpleApiRepositoryAdapterTest
    assertThat(hostedRepository.getCleanup(), nullValue());
    assertThat(hostedRepository.getStorage(), notNullValue());
  }

  @Test
  public void testAdapt_proxyRepository() throws Exception {
    Repository repository = createRepository(new ProxyType(), LayoutPolicy.STRICT, VersionPolicy.MIXED, ContentDisposition.INLINE);

    MavenProxyApiRepository proxyRepository = (MavenProxyApiRepository) underTest.adapt(repository);
    assertRepository(proxyRepository, "proxy", true);
    assertThat(proxyRepository.getMaven().getLayoutPolicy(), is("STRICT"));
    assertThat(proxyRepository.getMaven().getVersionPolicy(), is("MIXED"));
    assertThat(proxyRepository.getMaven().getContentDisposition(), is("INLINE"));
    // Check fields are populated, actual values validated with SimpleApiRepositoryAdapterTest
    assertThat(proxyRepository.getCleanup(), nullValue());
    assertThat(proxyRepository.getHttpClient(), notNullValue());
    assertThat(proxyRepository.getNegativeCache(), notNullValue());
    assertThat(proxyRepository.getProxy(), notNullValue());
    assertThat(proxyRepository.getStorage(), notNullValue());
  }

  /**
   * Tests concurrent adaptation of multiple repository types using Virtual Threads.
   * This test validates that the adapter can handle concurrent operations in a Virtual Thread environment.
   */
  @Test
  public void testConcurrentAdaptWithVirtualThreads() throws Exception {
    // Create repositories of different types
    Repository groupRepo = createRepository(new GroupType());
    Configuration groupConfig = groupRepo.getConfiguration();
    groupConfig.attributes("group").set("memberNames", Arrays.asList("a", "b"));
    groupRepo.update(groupConfig);
    
    Repository hostedRepo = createRepository(new HostedType(), LayoutPolicy.STRICT, VersionPolicy.MIXED, 
        ContentDisposition.INLINE);
    
    Repository proxyRepo = createRepository(new ProxyType(), LayoutPolicy.STRICT, VersionPolicy.MIXED, 
        ContentDisposition.INLINE);
    
    List<Repository> repositories = Arrays.asList(groupRepo, hostedRepo, proxyRepo);
    
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Adapt all repositories concurrently using virtual threads
      List<CompletableFuture<AbstractApiRepository>> futures = repositories.stream()
          .map(repo -> CompletableFuture.supplyAsync(() -> underTest.adapt(repo), executor))
          .collect(Collectors.toList());
      
      // Wait for all adaptations to complete
      CompletableFuture<Void> allFutures = CompletableFuture.allOf(
          futures.toArray(new CompletableFuture[0]));
      
      // Get the results
      allFutures.join();
      List<AbstractApiRepository> results = futures.stream()
          .map(CompletableFuture::join)
          .collect(Collectors.toList());
      
      // Verify results
      assertThat(results.size(), is(3));
      
      // Verify each repository was adapted correctly
      AbstractApiRepository adaptedGroupRepo = results.stream()
          .filter(repo -> repo.getType().equals("group"))
          .findFirst()
          .orElse(null);
      assertThat(adaptedGroupRepo, notNullValue());
      assertRepository(adaptedGroupRepo, "group", true);
      
      AbstractApiRepository adaptedHostedRepo = results.stream()
          .filter(repo -> repo.getType().equals("hosted"))
          .findFirst()
          .orElse(null);
      assertThat(adaptedHostedRepo, notNullValue());
      assertRepository(adaptedHostedRepo, "hosted", true);
      assertThat(((MavenHostedApiRepository) adaptedHostedRepo).getMaven().getLayoutPolicy(), is("STRICT"));
      
      AbstractApiRepository adaptedProxyRepo = results.stream()
          .filter(repo -> repo.getType().equals("proxy"))
          .findFirst()
          .orElse(null);
      assertThat(adaptedProxyRepo, notNullValue());
      assertRepository(adaptedProxyRepo, "proxy", true);
      assertThat(((MavenProxyApiRepository) adaptedProxyRepo).getMaven().getVersionPolicy(), is("MIXED"));
    }
  }

  private static void assertRepository(
      final AbstractApiRepository repository,
      final String type,
      final Boolean online)
  {
    assertThat(repository.getFormat(), is("maven2"));
    assertThat(repository.getName(), is("my-repo"));
    assertThat(repository.getOnline(), is(online));
    assertThat(repository.getType(), is(type));
    assertThat(repository.getUrl(), is(BaseUrlHolder.get() + "/repository/my-repo"));
  }

  private static Configuration config(final String repositoryName) {
    Configuration configuration = mock(Configuration.class);
    when(configuration.isOnline()).thenReturn(true);
    when(configuration.getRepositoryName()).thenReturn(repositoryName);
    when(configuration.attributes(not(eq("maven")))).thenReturn(new NestedAttributesMap("dummy", newHashMap()));
    return configuration;
  }

  private static Repository createRepository(final Type type) throws Exception {
    Repository repository = new RepositoryImpl(Mockito.mock(EventManager.class), type, new Maven2Format());
    repository.init(config("my-repo"));
    return repository;
  }

  private static Repository createRepository(
      final Type type,
      final LayoutPolicy layoutPolicy,
      final VersionPolicy versionPolicy,
      final ContentDisposition contentDisposition) throws Exception
  {
    Repository repository = new RepositoryImpl(Mockito.mock(EventManager.class), type, new Maven2Format());

    Configuration configuration = config("my-repo");
    NestedAttributesMap maven = new NestedAttributesMap("maven", newHashMap());
    maven.set("layoutPolicy", layoutPolicy.toString());
    maven.set("versionPolicy", versionPolicy.toString());
    maven.set("contentDisposition", contentDisposition.toString());
    when(configuration.attributes("maven")).thenReturn(maven);
    repository.init(configuration);
    return repository;
  }
}