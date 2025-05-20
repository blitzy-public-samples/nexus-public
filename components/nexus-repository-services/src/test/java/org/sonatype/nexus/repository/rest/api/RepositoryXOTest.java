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
package org.sonatype.nexus.repository.rest.api;

import java.util.Map;
import java.util.stream.Stream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.types.ProxyType;

import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RepositoryXOTest
    extends TestSupport
{
  @Mock
  private Repository repository;

  /**
   * Provides test data for parameterized tests.
   */
  static Stream<Arguments> repositoryTestData() {
    return Stream.of(
        arguments(
            "x", format("npm"), "npm", new ProxyType(), "proxy", "u", Map.of("remoteUrl", "url"),
            Map.of("proxy", Map.of("remoteUrl", "url"))
        ),
        arguments("y", format("maven"), "maven", new HostedType(), "hosted", "u", Map.of("remoteUrl", "foo"), Map.of()),
        arguments("z", format("nuget"), "nuget", new GroupType(), "group", "u", Map.of("remoteUrl", "foo"), Map.of())
    );
  }

  @ParameterizedTest(name = "{index}: {0} - {2}/{4}")
  @MethodSource("repositoryTestData")
  void testConvertRepositoryToRepositoryXO(
      String name,
      Format format,
      String expectedFormat,
      Type type,
      String expectedType,
      String url,
      Map<String, Object> attributes,
      Map<String, Map<String, Object>> expectedAttributes) 
  {
    when(repository.getName()).thenReturn(name);
    when(repository.getFormat()).thenReturn(format);
    when(repository.getType()).thenReturn(type);
    when(repository.getUrl()).thenReturn(url);

    Configuration mockConfiguration = configuration(type.getValue(), attributes);
    when(repository.getConfiguration()).thenReturn(mockConfiguration);

    RepositoryXO repositoryXO = RepositoryXO.fromRepository(repository);

    assertThat(repositoryXO.getName(), is(name));
    assertThat(repositoryXO.getFormat(), is(expectedFormat));
    assertThat(repositoryXO.getType(), is(expectedType));
    assertThat(repositoryXO.getUrl(), is(url));
    assertThat(repositoryXO.getAttributes(), is(expectedAttributes));
  }
  
  /**
   * Tests record pattern matching with repository type extraction.
   */
  @Test
  @Category(Java21TestGroup.class)
  void testRepositoryTypePatternMatching() {
    // Create test repositories with different types
    Repository proxyRepo = mock(Repository.class);
    when(proxyRepo.getType()).thenReturn(new ProxyType());
    
    Repository hostedRepo = mock(Repository.class);
    when(hostedRepo.getType()).thenReturn(new HostedType());
    
    Repository groupRepo = mock(Repository.class);
    when(groupRepo.getType()).thenReturn(new GroupType());
    
    // Test pattern matching with instanceof and type patterns
    String proxyTypeResult = getRepositoryTypeUsingPatternMatching(proxyRepo);
    String hostedTypeResult = getRepositoryTypeUsingPatternMatching(hostedRepo);
    String groupTypeResult = getRepositoryTypeUsingPatternMatching(groupRepo);
    
    assertEquals("proxy", proxyTypeResult, "Should identify proxy repository type");
    assertEquals("hosted", hostedTypeResult, "Should identify hosted repository type");
    assertEquals("group", groupTypeResult, "Should identify group repository type");
  }
  
  /**
   * Tests String Template usage in URL construction.
   */
  @Test
  @Category(Java21TestGroup.class)
  void testStringTemplateUrlConstruction() {
    // Test data for URL construction
    String baseUrl = "http://localhost:8081";
    String repoName = "maven-central";
    String format = "maven";
    String path = "org/example/artifact/1.0/artifact-1.0.jar";
    
    // Construct URL using String Template
    String url = constructUrlWithStringTemplate(baseUrl, format, repoName, path);
    String expectedUrl = "http://localhost:8081/repository/maven-central/org/example/artifact/1.0/artifact-1.0.jar";
    
    assertEquals(expectedUrl, url, "URL should be correctly constructed using String Template");
  }
  
  /**
   * Uses pattern matching to determine repository type.
   * This demonstrates Java 21's pattern matching for instanceof feature.
   */
  private String getRepositoryTypeUsingPatternMatching(Repository repository) {
    Type type = repository.getType();
    
    if (type instanceof ProxyType(var value)) {
      return "proxy";
    } else if (type instanceof HostedType(var value)) {
      return "hosted";
    } else if (type instanceof GroupType(var value)) {
      return "group";
    } else {
      return "unknown";
    }
  }
  
  /**
   * Constructs a repository URL using Java 21 String Templates.
   */
  private String constructUrlWithStringTemplate(String baseUrl, String format, String repoName, String path) {
    // Using Java 21 String Template feature
    return STR."{baseUrl}/repository/{repoName}/{path}";
  }

  private static Format format(final String value) {
    Format format = mock(Format.class);
    when(format.getValue()).thenReturn(value);
    return format;
  }

  private Configuration configuration(final String type, final Map<String, Object> value) {
    Configuration configuration = mock(Configuration.class);
    when(configuration.getAttributes()).thenReturn(Map.of(type, value));
    when(configuration.attributes(type)).thenReturn(new NestedAttributesMap(type, value));
    return configuration;
  }
}