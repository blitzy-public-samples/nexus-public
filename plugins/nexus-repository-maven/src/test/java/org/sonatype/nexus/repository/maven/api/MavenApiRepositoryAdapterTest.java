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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// Import for String Templates (Java 21 feature)
import static java.lang.StringTemplate.STR;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.BaseUrlHolder;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.common.thread.Java21TestGroup;
import org.sonatype.nexus.common.thread.VirtualThreadTestGroup;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.google.common.collect.Maps.newHashMap;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MavenApiRepositoryAdapter} with Java 21 compatibility.
 * 
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
class MavenApiRepositoryAdapterTest
    extends TestSupport
    implements Java21TestGroup, VirtualThreadTestGroup
{
  private MavenApiRepositoryAdapter underTest;

  @Mock
  private RoutingRuleStore routingRuleStore;

  @BeforeEach
  void setup() {
    underTest = new MavenApiRepositoryAdapter(routingRuleStore);
    BaseUrlHolder.set("http://nexus-url", "");
  }

  @Test
  void testAdapt_groupRepository() throws Exception {
    // No maven specific props so simple smoke test
    Repository repository = createRepository(new GroupType());
    Configuration configuration = repository.getConfiguration();
    configuration.attributes("group").set("memberNames", Arrays.asList("a", "b"));
    repository.update(configuration);

    SimpleApiGroupRepository groupRepository = (SimpleApiGroupRepository) underTest.adapt(repository);
    assertRepository(groupRepository, "group", true);
  }

  @Test
  void testAdapt_hostedRepository() throws Exception {
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
  void testAdapt_proxyRepository() throws Exception {
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
   * Test that validates the pattern matching for switch used in {@link MavenApiRepositoryAdapter#adapt}.
   * This test specifically targets the Java 21 pattern matching for switch feature.
   */
  @Test
  @Tag("Java21")
  void testPatternMatchingForSwitch() throws Exception {
    // Test with HostedType
    Repository hostedRepo = createRepository(new HostedType(), LayoutPolicy.STRICT, VersionPolicy.MIXED, ContentDisposition.INLINE);
    AbstractApiRepository result = underTest.adapt(hostedRepo);
    assertNotNull(result);
    assertEquals(MavenHostedApiRepository.class, result.getClass());
    
    // Test with ProxyType
    Repository proxyRepo = createRepository(new ProxyType(), LayoutPolicy.STRICT, VersionPolicy.MIXED, ContentDisposition.INLINE);
    result = underTest.adapt(proxyRepo);
    assertNotNull(result);
    assertEquals(MavenProxyApiRepository.class, result.getClass());
    
    // Test with GroupType
    Repository groupRepo = createRepository(new GroupType());
    Configuration configuration = groupRepo.getConfiguration();
    configuration.attributes("group").set("memberNames", Arrays.asList("a", "b"));
    groupRepo.update(configuration);
    
    result = underTest.adapt(groupRepo);
    assertNotNull(result);
    assertEquals(SimpleApiGroupRepository.class, result.getClass());
  }

  /**
   * Test that validates the adapter's behavior when executed in a virtual thread.
   * This test specifically targets Java 21's virtual thread capabilities.
   */
  @Test
  @Tag("VirtualThread")
  void testAdaptInVirtualThread() throws Exception {
    Repository repository = createRepository(new HostedType(), LayoutPolicy.STRICT, VersionPolicy.MIXED, ContentDisposition.INLINE);
    
    // Execute the adapter in a virtual thread
    CompletableFuture<AbstractApiRepository> future = CompletableFuture.supplyAsync(
        () -> {
          try {
            return underTest.adapt(repository);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        },
        newVirtualThreadPerTaskExecutor()
    );
    
    // Get the result and verify it
    AbstractApiRepository result = future.get(5, TimeUnit.SECONDS);
    assertNotNull(result);
    assertEquals(MavenHostedApiRepository.class, result.getClass());
    MavenHostedApiRepository hostedRepository = (MavenHostedApiRepository) result;
    assertRepository(hostedRepository, "hosted", true);
    assertThat(hostedRepository.getMaven().getLayoutPolicy(), is("STRICT"));
    assertThat(hostedRepository.getMaven().getVersionPolicy(), is("MIXED"));
    assertThat(hostedRepository.getMaven().getContentDisposition(), is("INLINE"));
  }

  /**
   * Test that validates exception handling with pattern matching.
   * This test specifically targets Java 21's enhanced pattern matching capabilities.
   */
  @Test
  @Tag("Java21")
  void testExceptionHandlingWithPatternMatching() throws Exception {
    // Create a repository with a null configuration to trigger an exception
    Repository repository = mock(Repository.class);
    when(repository.getConfiguration()).thenReturn(null);
    when(repository.getName()).thenReturn("test-repo");
    when(repository.getType()).thenReturn(new HostedType());
    
    // This should throw a NullPointerException
    Exception exception = assertThrows(NullPointerException.class, () -> underTest.adapt(repository));
    
    // Use pattern matching to handle different exception types
    String message = switch (exception) {
      case NullPointerException npe -> "NullPointerException occurred";
      case IllegalArgumentException iae -> "IllegalArgumentException occurred";
      case RuntimeException re -> "RuntimeException occurred";
      default -> "Unknown exception occurred";
    };
    
    assertEquals("NullPointerException occurred", message);
  }

  /**
   * Test that validates record patterns with Maven attributes.
   * This test specifically targets Java 21's record pattern matching feature.
   */
  @Test
  @Tag("Java21")
  void testRecordPatternMatching() throws Exception {
    // Create a repository with Maven attributes
    Repository repository = createRepository(new HostedType(), LayoutPolicy.STRICT, VersionPolicy.MIXED, ContentDisposition.INLINE);
    
    // Adapt the repository
    AbstractApiRepository result = underTest.adapt(repository);
    
    // Use record pattern matching to extract and validate Maven attributes
    if (result instanceof MavenHostedApiRepository(var name, var url, var online, var storage, var cleanup, 
                                                 MavenAttributes(var versionPolicy, var layoutPolicy, var contentDisposition), var component)) {
      // Validate extracted fields using record pattern matching
      assertEquals("my-repo", name);
      assertEquals(true, online);
      assertEquals("MIXED", versionPolicy);
      assertEquals("STRICT", layoutPolicy);
      assertEquals("INLINE", contentDisposition);
    } else {
      // This should not happen
      throw new AssertionError("Expected MavenHostedApiRepository but got " + result.getClass().getSimpleName());
    }
  }

  /**
   * Test that demonstrates the use of String Templates (Java 21 feature).
   * This test validates repository information using string templates for more readable output.
   */
  @Test
  @Tag("Java21")
  void testStringTemplates() throws Exception {
    // Create repositories of different types
    Repository hostedRepo = createRepository(new HostedType(), LayoutPolicy.STRICT, VersionPolicy.MIXED, ContentDisposition.INLINE);
    Repository proxyRepo = createRepository(new ProxyType(), LayoutPolicy.STRICT, VersionPolicy.MIXED, ContentDisposition.INLINE);
    
    // Adapt the repositories
    MavenHostedApiRepository hostedResult = (MavenHostedApiRepository) underTest.adapt(hostedRepo);
    MavenProxyApiRepository proxyResult = (MavenProxyApiRepository) underTest.adapt(proxyRepo);
    
    // Use string templates to create descriptive messages
    String hostedInfo = STR."Repository Type: \{hostedResult.getType()}, Name: \{hostedResult.getName()}, Format: \{hostedResult.getFormat()}, Online: \{hostedResult.getOnline()}";
    String proxyInfo = STR."Repository Type: \{proxyResult.getType()}, Name: \{proxyResult.getName()}, Format: \{proxyResult.getFormat()}, Online: \{proxyResult.getOnline()}";
    
    // Validate the string template output
    assertEquals("Repository Type: hosted, Name: my-repo, Format: maven2, Online: true", hostedInfo);
    assertEquals("Repository Type: proxy, Name: my-repo, Format: maven2, Online: true", proxyInfo);
    
    // Use string templates with Maven attributes
    String mavenAttrs = STR."""
        Maven Repository Configuration:
        - Layout Policy: \{hostedResult.getMaven().getLayoutPolicy()}
        - Version Policy: \{hostedResult.getMaven().getVersionPolicy()}
        - Content Disposition: \{hostedResult.getMaven().getContentDisposition()}
        """;
    
    // Validate multi-line string template
    String expected = """
        Maven Repository Configuration:
        - Layout Policy: STRICT
        - Version Policy: MIXED
        - Content Disposition: INLINE
        """;
    assertEquals(expected, mavenAttrs);
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