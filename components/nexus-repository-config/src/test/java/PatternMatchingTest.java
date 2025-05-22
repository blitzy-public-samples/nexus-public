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

package org.sonatype.nexus.repository.config;

import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.config.ConfigurationConstants;
import org.sonatype.nexus.repository.config.internal.ConfigurationData;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.types.ProxyType;

import com.google.common.collect.Maps;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.when;

/**
 * Tests for Java 21 Pattern Matching with switch expressions on repository configurations.
 * 
 * This test class demonstrates how Java 21's pattern matching for switch expressions can be used
 * to handle different repository configuration types in a type-safe manner. The tests validate that
 * switch expressions with pattern matching correctly identify and handle different configuration types
 * (hosted, proxy, group) and their attributes, ensuring type-safe access to configuration properties
 * without explicit casting.
 * 
 * Key features demonstrated:
 * - Pattern matching with switch expressions on repository configuration types
 * - Type-safe access to configuration properties without explicit casting
 * - Guarded patterns with 'when' clause for additional conditions
 * - Handling of null values directly in switch expressions
 * - Exhaustive pattern matching for all possible input types
 */
public class PatternMatchingTest
    extends TestSupport
{
  @Mock
  private EntityId entityId;

  private ConfigurationData hostedConfig;
  private ConfigurationData proxyConfig;
  private ConfigurationData groupConfig;

  @Before
  public void setup() {
    // Create a hosted repository configuration
    hostedConfig = new ConfigurationData();
    hostedConfig.setId(entityId);
    hostedConfig.setName("maven-hosted");
    hostedConfig.setRecipeName("maven-hosted");
    hostedConfig.setOnline(true);
    
    Map<String, Map<String, Object>> hostedAttributes = Maps.newHashMap();
    Map<String, Object> hostedStorage = Maps.newHashMap();
    hostedStorage.put(ConfigurationConstants.BLOB_STORE_NAME, "default");
    hostedStorage.put(ConfigurationConstants.WRITE_POLICY, "ALLOW");
    hostedAttributes.put(ConfigurationConstants.STORAGE, hostedStorage);
    hostedConfig.setAttributes(hostedAttributes);

    // Create a proxy repository configuration
    proxyConfig = new ConfigurationData();
    proxyConfig.setId(entityId);
    proxyConfig.setName("maven-central");
    proxyConfig.setRecipeName("maven-proxy");
    proxyConfig.setOnline(true);
    
    Map<String, Map<String, Object>> proxyAttributes = Maps.newHashMap();
    Map<String, Object> proxyStorage = Maps.newHashMap();
    proxyStorage.put(ConfigurationConstants.BLOB_STORE_NAME, "default");
    proxyAttributes.put(ConfigurationConstants.STORAGE, proxyStorage);
    
    Map<String, Object> proxyRemote = Maps.newHashMap();
    proxyRemote.put("url", "https://repo1.maven.org/maven2/");
    proxyRemote.put("contentMaxAge", 1440);
    proxyAttributes.put("proxy", proxyRemote);
    proxyConfig.setAttributes(proxyAttributes);

    // Create a group repository configuration
    groupConfig = new ConfigurationData();
    groupConfig.setId(entityId);
    groupConfig.setName("maven-public");
    groupConfig.setRecipeName("maven-group");
    groupConfig.setOnline(true);
    
    Map<String, Map<String, Object>> groupAttributes = Maps.newHashMap();
    Map<String, Object> groupStorage = Maps.newHashMap();
    groupStorage.put(ConfigurationConstants.BLOB_STORE_NAME, "default");
    groupAttributes.put(ConfigurationConstants.STORAGE, groupStorage);
    
    Map<String, Object> groupGroup = Maps.newHashMap();
    groupGroup.put("memberNames", java.util.Arrays.asList("maven-hosted", "maven-central"));
    groupAttributes.put("group", groupGroup);
    groupConfig.setAttributes(groupAttributes);
  }

  /**
   * Test basic pattern matching with switch expressions for repository configurations.
   * 
   * This test validates that pattern matching can correctly identify different repository types
   * and extract type-specific attributes without explicit casting.
   */
  @Test
  public void testBasicPatternMatching() {
    // Test pattern matching for hosted configuration
    String hostedResult = getRepositoryTypeUsingPatternMatching(hostedConfig);
    assertThat(hostedResult, is("Hosted repository with write policy: ALLOW"));
    
    // Test pattern matching for proxy configuration
    String proxyResult = getRepositoryTypeUsingPatternMatching(proxyConfig);
    assertThat(proxyResult, is("Proxy repository with remote URL: https://repo1.maven.org/maven2/"));
    
    // Test pattern matching for group configuration
    String groupResult = getRepositoryTypeUsingPatternMatching(groupConfig);
    assertThat(groupResult, is("Group repository with 2 members"));
  }

  /**
   * Test pattern matching with guarded patterns using when clause.
   * 
   * This test validates that guarded patterns with the 'when' clause can be used to add
   * additional conditions to pattern matching, such as checking if a repository is online.
   */
  @Test
  public void testGuardedPatternMatching() {
    // Test guarded pattern matching for hosted configuration
    String hostedResult = getRepositoryStatusUsingGuardedPatterns(hostedConfig);
    assertThat(hostedResult, is("Online hosted repository"));
    
    // Make the repository offline and test again
    hostedConfig.setOnline(false);
    String offlineHostedResult = getRepositoryStatusUsingGuardedPatterns(hostedConfig);
    assertThat(offlineHostedResult, is("Offline repository"));
    
    // Reset to online for other tests
    hostedConfig.setOnline(true);
  }

  /**
   * Test pattern matching with nested attribute access.
   * 
   * This test validates that pattern matching can be used to safely access nested attributes
   * in specific repository types, ensuring type-safe access to configuration properties.
   */
  @Test
  public void testNestedAttributePatternMatching() {
    // Test nested attribute pattern matching for proxy configuration
    String proxyResult = getProxyContentMaxAgeUsingPatternMatching(proxyConfig);
    assertThat(proxyResult, is("Proxy content max age: 1440 minutes"));
  }

  /**
   * Test exhaustive pattern matching with null handling.
   * 
   * This test validates that pattern matching can handle null values directly in the switch expression
   * and can be used to create exhaustive checks for all possible input types. This is a significant
   * improvement over previous Java versions where null had to be handled outside the switch.
   */
  @Test
  public void testExhaustivePatternMatchingWithNull() {
    // Test with valid configurations
    assertThat(isValidRepositoryConfiguration(hostedConfig), is(true));
    assertThat(isValidRepositoryConfiguration(proxyConfig), is(true));
    assertThat(isValidRepositoryConfiguration(groupConfig), is(true));
    
    // Test with null configuration
    assertThat(isValidRepositoryConfiguration(null), is(false));
    
    // Test with invalid configuration (not a known repository type)
    ConfigurationData unknownConfig = new ConfigurationData();
    unknownConfig.setId(entityId);
    unknownConfig.setName("unknown-repo");
    unknownConfig.setRecipeName("unknown-type");
    unknownConfig.setOnline(true);
    assertThat(isValidRepositoryConfiguration(unknownConfig), is(false));
  }

  /**
   * Uses Java 21 pattern matching with switch expressions to determine repository type and extract type-specific attributes.
   * 
   * This demonstrates how pattern matching can be used to safely access type-specific attributes without explicit casting.
   * The switch expression matches on the Configuration type and extracts relevant attributes based on the repository type.
   */
  private String getRepositoryTypeUsingPatternMatching(Configuration config) {
    return switch (config) {
      case ConfigurationData c when c.getRecipeName().contains(HostedType.NAME) -> {
        String writePolicy = c.attributes(ConfigurationConstants.STORAGE).get(ConfigurationConstants.WRITE_POLICY).toString();
        yield "Hosted repository with write policy: " + writePolicy;
      }
      case ConfigurationData c when c.getRecipeName().contains(ProxyType.NAME) -> {
        String remoteUrl = c.attributes("proxy").get("url").toString();
        yield "Proxy repository with remote URL: " + remoteUrl;
      }
      case ConfigurationData c when c.getRecipeName().contains(GroupType.NAME) -> {
        int memberCount = ((java.util.List<?>) c.attributes("group").get("memberNames")).size();
        yield "Group repository with " + memberCount + " members";
      }
      default -> "Unknown repository type";
    };
  }

  /**
   * Uses Java 21 pattern matching with guarded patterns to check repository status.
   * 
   * This demonstrates how guarded patterns with the 'when' clause can be used to add additional conditions
   * to pattern matching. The order of cases is important - more specific conditions should come first.
   */
  private String getRepositoryStatusUsingGuardedPatterns(Configuration config) {
    return switch (config) {
      case ConfigurationData c when !c.isOnline() -> "Offline repository";
      case ConfigurationData c when c.getRecipeName().contains(HostedType.NAME) -> "Online hosted repository";
      case ConfigurationData c when c.getRecipeName().contains(ProxyType.NAME) -> "Online proxy repository";
      case ConfigurationData c when c.getRecipeName().contains(GroupType.NAME) -> "Online group repository";
      default -> "Unknown repository type";
    };
  }

  /**
   * Uses Java 21 pattern matching to access nested attributes in proxy configurations.
   * 
   * This demonstrates how pattern matching can be used to safely access nested attributes
   * in specific repository types. Note that we still need to cast the contentMaxAge value
   * to Integer, but the pattern matching ensures we only do this for proxy repositories.
   */
  private String getProxyContentMaxAgeUsingPatternMatching(Configuration config) {
    return switch (config) {
      case ConfigurationData c when c.getRecipeName().contains(ProxyType.NAME) -> {
        Integer maxAge = (Integer) c.attributes("proxy").get("contentMaxAge");
        yield "Proxy content max age: " + maxAge + " minutes";
      }
      default -> "Not a proxy repository or missing contentMaxAge attribute";
    };
  }

  /**
   * Uses Java 21 pattern matching with exhaustive checking and null handling.
   * 
   * This demonstrates how pattern matching can handle null values directly in the switch expression,
   * which wasn't possible in earlier Java versions. It also shows how pattern matching can be used
   * to create exhaustive checks for all possible input types.
   */
  private boolean isValidRepositoryConfiguration(Configuration config) {
    return switch (config) {
      case null -> false;
      case ConfigurationData c when c.getRecipeName().contains(HostedType.NAME) -> true;
      case ConfigurationData c when c.getRecipeName().contains(ProxyType.NAME) -> true;
      case ConfigurationData c when c.getRecipeName().contains(GroupType.NAME) -> true;
      default -> false;
    };
  }
}