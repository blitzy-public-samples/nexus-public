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

import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.config.internal.ConfigurationData;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;

/**
 * Tests for Java 21 Pattern Matching with switch expressions on repository configurations.
 * 
 * @since 3.60
 */
public class PatternMatchingTest
    extends TestSupport
{
  @Mock
  private EntityId mockEntityId;

  private ConfigurationData hostedConfig;
  private ConfigurationData proxyConfig;
  private ConfigurationData groupConfig;

  @Before
  public void setup() {
    // Create a hosted repository configuration
    hostedConfig = new ConfigurationData();
    hostedConfig.setName("maven-hosted");
    hostedConfig.setRecipeName("maven-hosted");
    hostedConfig.setOnline(true);
    hostedConfig.setRoutingRuleId(mockEntityId);
    hostedConfig.setAttributes(Map.of(
        "storage", Map.of("blobStoreName", "default", "strictContentTypeValidation", true),
        "maven", Map.of("versionPolicy", "RELEASE", "layoutPolicy", "STRICT")
    ));

    // Create a proxy repository configuration
    proxyConfig = new ConfigurationData();
    proxyConfig.setName("maven-central");
    proxyConfig.setRecipeName("maven-proxy");
    proxyConfig.setOnline(true);
    proxyConfig.setRoutingRuleId(mockEntityId);
    proxyConfig.setAttributes(Map.of(
        "proxy", Map.of("remoteUrl", "https://repo1.maven.org/maven2/", "contentMaxAge", 1440),
        "negativeCache", Map.of("enabled", true, "timeToLive", 1440),
        "httpClient", Map.of("blocked", false, "autoBlock", true)
    ));

    // Create a group repository configuration
    groupConfig = new ConfigurationData();
    groupConfig.setName("maven-public");
    groupConfig.setRecipeName("maven-group");
    groupConfig.setOnline(true);
    groupConfig.setRoutingRuleId(mockEntityId);
    groupConfig.setAttributes(Map.of(
        "group", Map.of("memberNames", java.util.List.of("maven-hosted", "maven-central"))
    ));
  }

  /**
   * Test pattern matching with switch expressions to identify repository types.
   */
  @Test
  public void testPatternMatchingForRepositoryTypes() {
    // Test pattern matching for hosted repository
    String hostedType = getRepositoryType(hostedConfig);
    assertThat(hostedType, is("hosted"));

    // Test pattern matching for proxy repository
    String proxyType = getRepositoryType(proxyConfig);
    assertThat(proxyType, is("proxy"));

    // Test pattern matching for group repository
    String groupType = getRepositoryType(groupConfig);
    assertThat(groupType, is("group"));
  }

  /**
   * Test pattern matching with switch expressions to extract repository-specific attributes.
   */
  @Test
  public void testPatternMatchingForRepositoryAttributes() {
    // Test pattern matching for hosted repository attributes
    String hostedInfo = getRepositoryInfo(hostedConfig);
    assertThat(hostedInfo, is("maven-hosted: RELEASE version policy"));

    // Test pattern matching for proxy repository attributes
    String proxyInfo = getRepositoryInfo(proxyConfig);
    assertThat(proxyInfo, is("maven-central: proxying https://repo1.maven.org/maven2/"));

    // Test pattern matching for group repository attributes
    String groupInfo = getRepositoryInfo(groupConfig);
    assertThat(groupInfo, is("maven-public: 2 members"));
  }

  /**
   * Test pattern matching with switch expressions and when guards.
   */
  @Test
  public void testPatternMatchingWithGuards() {
    // Test pattern matching with guards for repository status
    String hostedStatus = getRepositoryStatus(hostedConfig);
    assertThat(hostedStatus, is("maven-hosted is online"));

    // Set repository offline and test again
    hostedConfig.setOnline(false);
    hostedStatus = getRepositoryStatus(hostedConfig);
    assertThat(hostedStatus, is("maven-hosted is offline"));
  }

  /**
   * Test nested pattern matching for configuration attributes.
   */
  @Test
  public void testNestedPatternMatching() {
    // Test nested pattern matching for proxy configuration
    String proxyHttpClientInfo = getHttpClientInfo(proxyConfig);
    assertThat(proxyHttpClientInfo, is("Auto-blocking enabled"));

    // Test with a configuration that doesn't have httpClient attributes
    String hostedHttpClientInfo = getHttpClientInfo(hostedConfig);
    assertThat(hostedHttpClientInfo, is("No HTTP client configuration"));
  }

  /**
   * Uses pattern matching with switch to determine the repository type.
   * Demonstrates using pattern matching to identify different configuration types
   * without explicit casting.
   */
  private String getRepositoryType(Configuration config) {
    return switch (config) {
      case Configuration c when c.getRecipeName().contains("-hosted") -> "hosted";
      case Configuration c when c.getRecipeName().contains("-proxy") -> "proxy";
      case Configuration c when c.getRecipeName().contains("-group") -> "group";
      default -> "unknown";
    };
  }

  /**
   * Uses pattern matching with switch to extract repository-specific information.
   * Demonstrates accessing type-specific attributes without explicit casting.
   */
  private String getRepositoryInfo(Configuration config) {
    return switch (config) {
      case Configuration c when c.getRecipeName().contains("-hosted") -> {
        // Access hosted-specific attributes without casting
        String versionPolicy = (String) c.attributes("maven").get("versionPolicy");
        yield c.getRepositoryName() + ": " + versionPolicy + " version policy";
      }
      case Configuration c when c.getRecipeName().contains("-proxy") -> {
        // Access proxy-specific attributes without casting
        String remoteUrl = (String) c.attributes("proxy").get("remoteUrl");
        yield c.getRepositoryName() + ": proxying " + remoteUrl;
      }
      case Configuration c when c.getRecipeName().contains("-group") -> {
        // Access group-specific attributes without casting
        java.util.List<?> members = (java.util.List<?>) c.attributes("group").get("memberNames");
        yield c.getRepositoryName() + ": " + members.size() + " members";
      }
      default -> "unknown repository";
    };
  }

  /**
   * Uses pattern matching with switch and when guards to check repository status.
   * Demonstrates combining pattern matching with additional conditions.
   */
  private String getRepositoryStatus(Configuration config) {
    return switch (config) {
      case Configuration c when c.isOnline() -> c.getRepositoryName() + " is online";
      case Configuration c -> c.getRepositoryName() + " is offline";
    };
  }

  /**
   * Uses nested pattern matching to extract HTTP client configuration.
   * Demonstrates pattern matching for nested attributes and null handling.
   */
  private String getHttpClientInfo(Configuration config) {
    return switch (config) {
      case Configuration c when c.getAttributes() != null && c.getAttributes().containsKey("httpClient") -> {
        Map<String, Object> httpClient = c.getAttributes().get("httpClient");
        boolean autoBlock = (Boolean) httpClient.get("autoBlock");
        yield autoBlock ? "Auto-blocking enabled" : "Auto-blocking disabled";
      }
      case Configuration c -> "No HTTP client configuration";
      case null -> "Configuration is null";
    };
  }
}