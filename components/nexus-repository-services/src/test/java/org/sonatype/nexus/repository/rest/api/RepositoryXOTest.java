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

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.common.test.Java21TestGroup;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.types.ProxyType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;

import static java.lang.StringTemplate.STR;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Category(Java21TestGroup.class)
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
        Arguments.of(
            "x", format("npm"), "npm", new ProxyType(), "proxy", "u", Map.of("remoteUrl", "url"),
            Map.of("proxy", Map.of("remoteUrl", "url"))
        ),
        Arguments.of("y", format("maven"), "maven", new HostedType(), "hosted", "u", Map.of("remoteUrl", "foo"), Map.of()),
        Arguments.of("z", format("nuget"), "nuget", new GroupType(), "group", "u", Map.of("remoteUrl", "foo"), Map.of())
    );
  }

  @ParameterizedTest
  @MethodSource("repositoryTestData")
  public void testConvertRepositoryToRepositoryXO(
      final String name,
      final Format format,
      final String expectedFormat,
      final Type type,
      final String expectedType,
      final String url,
      final Map<String, Object> attributes,
      final Map<String, Map<String, Object>> expectedAttributes) 
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
   * Tests pattern matching with repository type extraction.
   * This test demonstrates Java 21's pattern matching capabilities for repository type handling.
   */
  @Test
  @Category(Java21TestGroup.class)
  public void testRepositoryTypePatternMatching() {
    // Setup repositories of different types
    Repository proxyRepo = mock(Repository.class);
    when(proxyRepo.getType()).thenReturn(new ProxyType());
    when(proxyRepo.getName()).thenReturn("proxy-repo");
    
    Repository hostedRepo = mock(Repository.class);
    when(hostedRepo.getType()).thenReturn(new HostedType());
    when(hostedRepo.getName()).thenReturn("hosted-repo");
    
    Repository groupRepo = mock(Repository.class);
    when(groupRepo.getType()).thenReturn(new GroupType());
    when(groupRepo.getName()).thenReturn("group-repo");
    
    // Test pattern matching with repository types
    assertEquals("proxy", getRepositoryTypeUsingPatternMatching(proxyRepo));
    assertEquals("hosted", getRepositoryTypeUsingPatternMatching(hostedRepo));
    assertEquals("group", getRepositoryTypeUsingPatternMatching(groupRepo));
  }
  
  /**
   * Tests String Template usage in URL construction.
   * This test demonstrates Java 21's String Template feature for building repository URLs.
   */
  @Test
  @Category(Java21TestGroup.class)
  public void testStringTemplateUrlConstruction() {
    // Setup test data
    String host = "localhost";
    int port = 8081;
    String repoName = "maven-central";
    String format = "maven";
    String path = "org/example/artifact/1.0/artifact-1.0.jar";
    
    // Construct URL using String Template
    String expectedUrl = "http://localhost:8081/repository/maven-central/org/example/artifact/1.0/artifact-1.0.jar";
    String actualUrl = constructRepositoryUrlWithStringTemplate(host, port, repoName, path);
    
    assertEquals(expectedUrl, actualUrl);
    
    // Test with different repository formats
    String npmUrl = constructRepositoryUrlWithFormatAndStringTemplate(host, port, "npm-proxy", "npm", "@scope/package");
    String expectedNpmUrl = "http://localhost:8081/repository/npm-proxy/@scope/package";
    assertEquals(expectedNpmUrl, npmUrl);
  }
  
  /**
   * Uses pattern matching to extract repository type.
   * Demonstrates Java 21 pattern matching with switch expressions.
   */
  private String getRepositoryTypeUsingPatternMatching(Repository repository) {
    Type type = repository.getType();
    
    return switch (type) {
      case ProxyType proxyType -> "proxy";
      case HostedType hostedType -> "hosted";
      case GroupType groupType -> "group";
      default -> "unknown";
    };
  }
  
  /**
   * Constructs a repository URL using Java 21 String Templates.
   */
  private String constructRepositoryUrlWithStringTemplate(String host, int port, String repoName, String path) {
    return STR."http://\{host}:\{port}/repository/\{repoName}/\{path}";
  }
  
  /**
   * Constructs a repository URL with format using Java 21 String Templates.
   */
  private String constructRepositoryUrlWithFormatAndStringTemplate(String host, int port, String repoName, String format, String path) {
    return STR."http://\{host}:\{port}/repository/\{repoName}/\{path}";
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