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
package org.sonatype.nexus.pattern;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.config.ConfigurationFacet;
import org.sonatype.nexus.repository.group.GroupFacet;
import org.sonatype.nexus.repository.proxy.ProxyFacet;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.repository.types.Type;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link Repository} type pattern matching using Java 21 features.
 */
@Category(Java21TestGroup.class)
public class RepositoryTypePatternMatchingTest
    extends TestSupport
{
  @Mock
  private Repository hostedRepository;

  @Mock
  private Repository proxyRepository;

  @Mock
  private Repository groupRepository;

  @Mock
  private ConfigurationFacet configurationFacet;

  @Mock
  private Configuration hostedConfiguration;

  @Mock
  private Configuration proxyConfiguration;

  @Mock
  private Configuration groupConfiguration;

  @Mock
  private HostedType hostedType;

  @Mock
  private ProxyType proxyType;

  @Mock
  private GroupType groupType;

  @Mock
  private ProxyFacet proxyFacet;

  @Mock
  private GroupFacet groupFacet;

  @Before
  public void setUp() {
    // Setup hosted repository
    when(hostedRepository.getType()).thenReturn(hostedType);
    when(hostedRepository.facet(ConfigurationFacet.class)).thenReturn(configurationFacet);
    when(configurationFacet.getConfiguration()).thenReturn(hostedConfiguration);
    when(hostedConfiguration.getRepositoryType()).thenReturn(HostedType.NAME);
    when(hostedType.getValue()).thenReturn(HostedType.NAME);

    // Setup proxy repository
    when(proxyRepository.getType()).thenReturn(proxyType);
    when(proxyRepository.facet(ConfigurationFacet.class)).thenReturn(configurationFacet);
    when(proxyRepository.facet(ProxyFacet.class)).thenReturn(proxyFacet);
    when(configurationFacet.getConfiguration()).thenReturn(proxyConfiguration);
    when(proxyConfiguration.getRepositoryType()).thenReturn(ProxyType.NAME);
    when(proxyType.getValue()).thenReturn(ProxyType.NAME);

    // Setup group repository
    when(groupRepository.getType()).thenReturn(groupType);
    when(groupRepository.facet(ConfigurationFacet.class)).thenReturn(configurationFacet);
    when(groupRepository.facet(GroupFacet.class)).thenReturn(groupFacet);
    when(configurationFacet.getConfiguration()).thenReturn(groupConfiguration);
    when(groupConfiguration.getRepositoryType()).thenReturn(GroupType.NAME);
    when(groupType.getValue()).thenReturn(GroupType.NAME);
  }

  /**
   * Test basic pattern matching for repository types using instanceof with pattern variables.
   */
  @Test
  public void testBasicRepositoryTypePatternMatching() {
    // Test hosted repository type pattern matching
    if (hostedRepository.getType() instanceof HostedType hostedType) {
      assertThat(hostedType.getValue(), is(HostedType.NAME));
    }
    else {
      fail("Expected HostedType pattern to match");
    }

    // Test proxy repository type pattern matching
    if (proxyRepository.getType() instanceof ProxyType proxyType) {
      assertThat(proxyType.getValue(), is(ProxyType.NAME));
    }
    else {
      fail("Expected ProxyType pattern to match");
    }

    // Test group repository type pattern matching
    if (groupRepository.getType() instanceof GroupType groupType) {
      assertThat(groupType.getValue(), is(GroupType.NAME));
    }
    else {
      fail("Expected GroupType pattern to match");
    }
  }

  /**
   * Test pattern matching for repository types using switch expressions.
   */
  @Test
  public void testRepositoryTypePatternMatchingWithSwitch() {
    // Test hosted repository type pattern matching with switch
    String hostedTypeResult = switch (hostedRepository.getType()) {
      case HostedType hosted -> "hosted";
      case ProxyType proxy -> "proxy";
      case GroupType group -> "group";
      default -> "unknown";
    };
    assertThat(hostedTypeResult, is("hosted"));

    // Test proxy repository type pattern matching with switch
    String proxyTypeResult = switch (proxyRepository.getType()) {
      case HostedType hosted -> "hosted";
      case ProxyType proxy -> "proxy";
      case GroupType group -> "group";
      default -> "unknown";
    };
    assertThat(proxyTypeResult, is("proxy"));

    // Test group repository type pattern matching with switch
    String groupTypeResult = switch (groupRepository.getType()) {
      case HostedType hosted -> "hosted";
      case ProxyType proxy -> "proxy";
      case GroupType group -> "group";
      default -> "unknown";
    };
    assertThat(groupTypeResult, is("group"));
  }

  /**
   * Test pattern matching for repository types with configuration using switch expressions.
   */
  @Test
  public void testRepositoryConfigurationPatternMatchingWithSwitch() {
    // Test hosted repository configuration pattern matching with switch
    String hostedConfigResult = switch (hostedConfiguration.getRepositoryType()) {
      case String s when s.equals(HostedType.NAME) -> "hosted";
      case String s when s.equals(ProxyType.NAME) -> "proxy";
      case String s when s.equals(GroupType.NAME) -> "group";
      default -> "unknown";
    };
    assertThat(hostedConfigResult, is("hosted"));

    // Test proxy repository configuration pattern matching with switch
    String proxyConfigResult = switch (proxyConfiguration.getRepositoryType()) {
      case String s when s.equals(HostedType.NAME) -> "hosted";
      case String s when s.equals(ProxyType.NAME) -> "proxy";
      case String s when s.equals(GroupType.NAME) -> "group";
      default -> "unknown";
    };
    assertThat(proxyConfigResult, is("proxy"));

    // Test group repository configuration pattern matching with switch
    String groupConfigResult = switch (groupConfiguration.getRepositoryType()) {
      case String s when s.equals(HostedType.NAME) -> "hosted";
      case String s when s.equals(ProxyType.NAME) -> "proxy";
      case String s when s.equals(GroupType.NAME) -> "group";
      default -> "unknown";
    };
    assertThat(groupConfigResult, is("group"));
  }

  /**
   * Test pattern matching for repository facets using switch expressions.
   */
  @Test
  public void testRepositoryFacetPatternMatchingWithSwitch() {
    // Test repository facet availability using pattern matching
    String proxyFacetResult = hasProxyFacet(proxyRepository) ? "has proxy facet" : "no proxy facet";
    assertThat(proxyFacetResult, is("has proxy facet"));

    String groupFacetResult = hasGroupFacet(groupRepository) ? "has group facet" : "no group facet";
    assertThat(groupFacetResult, is("has group facet"));

    String hostedProxyFacetResult = hasProxyFacet(hostedRepository) ? "has proxy facet" : "no proxy facet";
    assertThat(hostedProxyFacetResult, is("no proxy facet"));
  }

  /**
   * Test exhaustive pattern matching for repository types.
   */
  @Test
  public void testExhaustiveRepositoryTypePatternMatching() {
    // Test exhaustive pattern matching for all repository types
    for (Repository repository : new Repository[] {hostedRepository, proxyRepository, groupRepository}) {
      String typeResult = getRepositoryTypeDescription(repository);
      
      // Verify the result matches the expected repository type
      Type type = repository.getType();
      if (type instanceof HostedType) {
        assertThat(typeResult, is("Hosted repository for storing components locally"));
      }
      else if (type instanceof ProxyType) {
        assertThat(typeResult, is("Proxy repository for proxying remote repositories"));
      }
      else if (type instanceof GroupType) {
        assertThat(typeResult, is("Group repository for aggregating other repositories"));
      }
      else {
        fail("Unexpected repository type: " + type);
      }
    }
  }

  /**
   * Helper method that uses pattern matching to check if a repository has a proxy facet.
   */
  private boolean hasProxyFacet(Repository repository) {
    try {
      return switch (repository) {
        case Repository r when r.facet(ProxyFacet.class) != null -> true;
        default -> false;
      };
    }
    catch (Exception e) {
      return false;
    }
  }

  /**
   * Helper method that uses pattern matching to check if a repository has a group facet.
   */
  private boolean hasGroupFacet(Repository repository) {
    try {
      return switch (repository) {
        case Repository r when r.facet(GroupFacet.class) != null -> true;
        default -> false;
      };
    }
    catch (Exception e) {
      return false;
    }
  }

  /**
   * Helper method that uses pattern matching to get a description of the repository type.
   */
  private String getRepositoryTypeDescription(Repository repository) {
    return switch (repository.getType()) {
      case HostedType hosted -> "Hosted repository for storing components locally";
      case ProxyType proxy -> "Proxy repository for proxying remote repositories";
      case GroupType group -> "Group repository for aggregating other repositories";
      default -> "Unknown repository type";
    };
  }
}