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

import java.util.HashMap;
import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.test.Java21TestGroup;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests for Java 21 pattern matching for switch statements in repository type handling.
 * 
 * This test validates that pattern matching correctly distinguishes between different repository types
 * (hosted, proxy, group) and their configurations, ensuring that pattern-based dispatch correctly
 * implements the same behavior as previous conditional instanceof checks but with more concise and
 * type-safe code.
 * 
 * The test compares traditional type checking approaches with Java 21 pattern matching features:
 * 1. Pattern matching in if statements (instanceof with binding variable)
 * 2. Pattern matching in switch statements (type patterns)
 * 3. Pattern matching with guard conditions (when clauses)
 * 4. Exhaustive pattern matching with sealed interfaces
 *
 * These patterns are particularly useful for repository managers and format handlers where different
 * repository types require specific processing logic.
 */
@Category(Java21TestGroup.class)
public class RepositoryTypePatternMatchingTest
    extends TestSupport
{
  /**
   * Define a simple repository type hierarchy for testing that mimics the actual repository types.
   * This hierarchy represents a simplified version of the repository types used in
   * Nexus Repository Manager: hosted, proxy, and group repositories.
   */
  sealed interface Repository permits HostedRepository, ProxyRepository, GroupRepository {
    String getName();
    Format getFormat();
  }
  
  /**
   * Format interface representing the repository format (maven, npm, docker, etc.)
   */
  interface Format {
    String getId();
  }
  
  /**
   * Simple Format implementation for testing
   */
  static class SimpleFormat implements Format {
    private final String id;
    
    public SimpleFormat(String id) {
      this.id = id;
    }
    
    @Override
    public String getId() {
      return id;
    }
  }
  
  /**
   * Base abstract class for all repository types, providing common name and format functionality.
   */
  abstract static class AbstractRepository implements Repository {
    private final String name;
    private final Format format;
    
    protected AbstractRepository(String name, Format format) {
      this.name = name;
      this.format = format;
    }
    
    @Override
    public String getName() {
      return name;
    }
    
    @Override
    public Format getFormat() {
      return format;
    }
  }
  
  /**
   * Hosted repository type - stores content directly in the repository.
   */
  static final class HostedRepository extends AbstractRepository {
    private final boolean writePolicy;
    
    public HostedRepository(String name, Format format, boolean writePolicy) {
      super(name, format);
      this.writePolicy = writePolicy;
    }
    
    public boolean isWritable() {
      return writePolicy;
    }
  }
  
  /**
   * Proxy repository type - proxies content from a remote repository.
   */
  static final class ProxyRepository extends AbstractRepository {
    private final String remoteUrl;
    
    public ProxyRepository(String name, Format format, String remoteUrl) {
      super(name, format);
      this.remoteUrl = remoteUrl;
    }
    
    public String getRemoteUrl() {
      return remoteUrl;
    }
  }
  
  /**
   * Group repository type - aggregates content from multiple member repositories.
   */
  static final class GroupRepository extends AbstractRepository {
    private final Repository[] members;
    
    public GroupRepository(String name, Format format, Repository... members) {
      super(name, format);
      this.members = members;
    }
    
    public Repository[] getMembers() {
      return members;
    }
  }
  
  /**
   * Repository configuration classes for testing pattern matching with configuration objects
   */
  interface RepositoryConfiguration {
    String getName();
    String getRecipeId();
  }
  
  static class HostedRepositoryConfiguration implements RepositoryConfiguration {
    private final String name;
    private final String recipeId;
    private final boolean writePolicy;
    
    public HostedRepositoryConfiguration(String name, String recipeId, boolean writePolicy) {
      this.name = name;
      this.recipeId = recipeId;
      this.writePolicy = writePolicy;
    }
    
    @Override
    public String getName() {
      return name;
    }
    
    @Override
    public String getRecipeId() {
      return recipeId;
    }
    
    public boolean isWritable() {
      return writePolicy;
    }
  }
  
  static class ProxyRepositoryConfiguration implements RepositoryConfiguration {
    private final String name;
    private final String recipeId;
    private final String remoteUrl;
    
    public ProxyRepositoryConfiguration(String name, String recipeId, String remoteUrl) {
      this.name = name;
      this.recipeId = recipeId;
      this.remoteUrl = remoteUrl;
    }
    
    @Override
    public String getName() {
      return name;
    }
    
    @Override
    public String getRecipeId() {
      return recipeId;
    }
    
    public String getRemoteUrl() {
      return remoteUrl;
    }
  }
  
  static class GroupRepositoryConfiguration implements RepositoryConfiguration {
    private final String name;
    private final String recipeId;
    private final String[] memberNames;
    
    public GroupRepositoryConfiguration(String name, String recipeId, String... memberNames) {
      this.name = name;
      this.recipeId = recipeId;
      this.memberNames = memberNames;
    }
    
    @Override
    public String getName() {
      return name;
    }
    
    @Override
    public String getRecipeId() {
      return recipeId;
    }
    
    public String[] getMemberNames() {
      return memberNames;
    }
  }
  
  // Test repository instances representing different repository types
  private HostedRepository mavenHosted;
  private ProxyRepository npmProxy;
  private GroupRepository dockerGroup;
  private Map<String, Repository> repositoryMap; // Map of name to repository instance
  
  // Test repository configuration instances
  private HostedRepositoryConfiguration mavenHostedConfig;
  private ProxyRepositoryConfiguration npmProxyConfig;
  private GroupRepositoryConfiguration dockerGroupConfig;
  private Map<String, RepositoryConfiguration> configMap; // Map of name to configuration instance
  
  @Before
  public void setUp() {
    // Create format instances
    Format mavenFormat = new SimpleFormat("maven");
    Format npmFormat = new SimpleFormat("npm");
    Format dockerFormat = new SimpleFormat("docker");
    
    // Create repository instances
    mavenHosted = new HostedRepository("maven-central", mavenFormat, true);
    npmProxy = new ProxyRepository("npm-proxy", npmFormat, "https://registry.npmjs.org");
    dockerGroup = new GroupRepository("docker-group", dockerFormat, 
        new HostedRepository("docker-hosted", dockerFormat, true),
        new ProxyRepository("docker-hub", dockerFormat, "https://registry.hub.docker.com"));
    
    // Create repository map
    repositoryMap = new HashMap<>();
    repositoryMap.put("maven-central", mavenHosted);
    repositoryMap.put("npm-proxy", npmProxy);
    repositoryMap.put("docker-group", dockerGroup);
    
    // Create repository configuration instances
    mavenHostedConfig = new HostedRepositoryConfiguration("maven-central", "maven-hosted", true);
    npmProxyConfig = new ProxyRepositoryConfiguration("npm-proxy", "npm-proxy", "https://registry.npmjs.org");
    dockerGroupConfig = new GroupRepositoryConfiguration("docker-group", "docker-group", "docker-hosted", "docker-hub");
    
    // Create configuration map
    configMap = new HashMap<>();
    configMap.put("maven-central", mavenHostedConfig);
    configMap.put("npm-proxy", npmProxyConfig);
    configMap.put("docker-group", dockerGroupConfig);
  }
  
  /**
   * Test traditional approach using instanceof and casting.
   * 
   * This represents the pre-Java 21 approach where instanceof checks are followed by explicit casting.
   * This pattern is verbose and error-prone, as the cast operation could fail at runtime if the
   * instanceof check is modified without updating the corresponding cast.
   */
  @Test
  public void testTraditionalTypeChecking() {
    for (Repository repository : repositoryMap.values()) {
      String repositoryInfo = getRepositoryInfoTraditional(repository);
      assertRepositoryInfo(repository, repositoryInfo);
    }
  }
  
  /**
   * Test Java 21 pattern matching in if statements.
   * 
   * This demonstrates the Java 21 pattern matching feature in if statements, where the instanceof
   * check and variable binding are combined in a single operation. This eliminates the need for
   * explicit casting and reduces the risk of runtime ClassCastExceptions.
   */
  @Test
  public void testPatternMatchingInIfStatements() {
    for (Repository repository : repositoryMap.values()) {
      String repositoryInfo = getRepositoryInfoWithPatternMatchingIf(repository);
      assertRepositoryInfo(repository, repositoryInfo);
    }
  }
  
  /**
   * Test Java 21 pattern matching in switch statements.
   * 
   * This demonstrates Java 21's pattern matching for switch, which allows for concise and type-safe
   * handling of different repository types. The switch expression directly matches against types and
   * binds variables in a single step, making the code more readable and maintainable.
   */
  @Test
  public void testPatternMatchingInSwitchStatements() {
    for (Repository repository : repositoryMap.values()) {
      String repositoryInfo = getRepositoryInfoWithPatternMatchingSwitch(repository);
      assertRepositoryInfo(repository, repositoryInfo);
    }
  }
  
  /**
   * Test Java 21 pattern matching with guard conditions.
   * 
   * This demonstrates the most powerful form of Java 21 pattern matching, combining type patterns
   * with conditional guards (when clauses). This allows for highly specific matching based on both
   * the type and the content of objects, enabling precise control flow based on complex conditions.
   */
  @Test
  public void testPatternMatchingWithGuards() {
    for (Repository repository : repositoryMap.values()) {
      String repositoryInfo = getRepositoryInfoWithGuards(repository);
      assertRepositoryInfo(repository, repositoryInfo);
    }
  }
  
  /**
   * Test Java 21 pattern matching with repository configuration objects.
   * 
   * This demonstrates pattern matching with non-sealed interfaces, showing how the technique
   * can be applied to configuration objects that don't use the sealed interface pattern.
   */
  @Test
  public void testPatternMatchingWithConfigurations() {
    for (RepositoryConfiguration config : configMap.values()) {
      String configInfo = getConfigInfoWithPatternMatching(config);
      assertConfigInfo(config, configInfo);
    }
  }
  
  /**
   * Test exhaustive pattern matching with sealed interfaces.
   * 
   * This demonstrates how Java 21's pattern matching with sealed interfaces provides
   * compile-time exhaustiveness checking, ensuring that all possible subtypes are handled.
   * This is particularly valuable for repository type handling where missing a type would
   * be a critical error.
   */
  @Test
  public void testExhaustivePatternMatching() {
    for (Repository repository : repositoryMap.values()) {
      String repositoryType = getRepositoryTypeExhaustive(repository);
      
      // Verify that the repository type is correctly identified
      if (repository instanceof HostedRepository) {
        assertThat(repositoryType, is(equalTo("hosted")));
      } else if (repository instanceof ProxyRepository) {
        assertThat(repositoryType, is(equalTo("proxy")));
      } else if (repository instanceof GroupRepository) {
        assertThat(repositoryType, is(equalTo("group")));
      }
    }
  }
  
  /**
   * Traditional approach using instanceof and casting.
   */
  private String getRepositoryInfoTraditional(Repository repository) {
    String formatId = repository.getFormat().getId();
    String name = repository.getName();
    
    if (repository instanceof HostedRepository) {
      HostedRepository hosted = (HostedRepository) repository;
      return String.format("%s hosted repository '%s' (writable: %s)", 
          formatId, name, hosted.isWritable());
    } 
    else if (repository instanceof ProxyRepository) {
      ProxyRepository proxy = (ProxyRepository) repository;
      return String.format("%s proxy repository '%s' (remote: %s)", 
          formatId, name, proxy.getRemoteUrl());
    } 
    else if (repository instanceof GroupRepository) {
      GroupRepository group = (GroupRepository) repository;
      return String.format("%s group repository '%s' (members: %d)", 
          formatId, name, group.getMembers().length);
    } 
    else {
      return String.format("Unknown repository type: %s", name);
    }
  }
  
  /**
   * Java 21 pattern matching in if statements.
   */
  private String getRepositoryInfoWithPatternMatchingIf(Repository repository) {
    String formatId = repository.getFormat().getId();
    String name = repository.getName();
    
    if (repository instanceof HostedRepository hosted) {
      return String.format("%s hosted repository '%s' (writable: %s)", 
          formatId, name, hosted.isWritable());
    } 
    else if (repository instanceof ProxyRepository proxy) {
      return String.format("%s proxy repository '%s' (remote: %s)", 
          formatId, name, proxy.getRemoteUrl());
    } 
    else if (repository instanceof GroupRepository group) {
      return String.format("%s group repository '%s' (members: %d)", 
          formatId, name, group.getMembers().length);
    } 
    else {
      return String.format("Unknown repository type: %s", name);
    }
  }
  
  /**
   * Java 21 pattern matching in switch statements.
   */
  private String getRepositoryInfoWithPatternMatchingSwitch(Repository repository) {
    String formatId = repository.getFormat().getId();
    String name = repository.getName();
    
    return switch (repository) {
      case HostedRepository hosted -> 
          String.format("%s hosted repository '%s' (writable: %s)", 
              formatId, name, hosted.isWritable());
      case ProxyRepository proxy -> 
          String.format("%s proxy repository '%s' (remote: %s)", 
              formatId, name, proxy.getRemoteUrl());
      case GroupRepository group -> 
          String.format("%s group repository '%s' (members: %d)", 
              formatId, name, group.getMembers().length);
    };
  }
  
  /**
   * Java 21 pattern matching with guard conditions.
   */
  private String getRepositoryInfoWithGuards(Repository repository) {
    String formatId = repository.getFormat().getId();
    String name = repository.getName();
    
    return switch (repository) {
      case HostedRepository hosted when hosted.isWritable() -> 
          String.format("%s writable hosted repository '%s'", formatId, name);
      case HostedRepository hosted -> 
          String.format("%s read-only hosted repository '%s'", formatId, name);
      case ProxyRepository proxy when proxy.getRemoteUrl().contains("npmjs") -> 
          String.format("%s npm proxy repository '%s'", formatId, name);
      case ProxyRepository proxy -> 
          String.format("%s proxy repository '%s' (remote: %s)", formatId, name, proxy.getRemoteUrl());
      case GroupRepository group when group.getMembers().length > 1 -> 
          String.format("%s multi-member group repository '%s' (members: %d)", 
              formatId, name, group.getMembers().length);
      case GroupRepository group -> 
          String.format("%s single-member group repository '%s'", formatId, name);
    };
  }
  
  /**
   * Java 21 pattern matching with configuration objects.
   */
  private String getConfigInfoWithPatternMatching(RepositoryConfiguration config) {
    return switch (config) {
      case HostedRepositoryConfiguration hosted -> 
          String.format("Hosted configuration '%s' (recipe: %s, writable: %s)", 
              hosted.getName(), hosted.getRecipeId(), hosted.isWritable());
      case ProxyRepositoryConfiguration proxy -> 
          String.format("Proxy configuration '%s' (recipe: %s, remote: %s)", 
              proxy.getName(), proxy.getRecipeId(), proxy.getRemoteUrl());
      case GroupRepositoryConfiguration group -> 
          String.format("Group configuration '%s' (recipe: %s, members: %d)", 
              group.getName(), group.getRecipeId(), group.getMemberNames().length);
      default -> 
          String.format("Unknown configuration type: %s", config.getName());
    };
  }
  
  /**
   * Exhaustive pattern matching with sealed interfaces.
   * This method demonstrates how Java 21's pattern matching with sealed interfaces
   * provides compile-time exhaustiveness checking, ensuring that all possible subtypes
   * are handled without needing a default case.
   */
  private String getRepositoryTypeExhaustive(Repository repository) {
    return switch (repository) {
      case HostedRepository hosted -> "hosted";
      case ProxyRepository proxy -> "proxy";
      case GroupRepository group -> "group";
      // No default case needed - compiler ensures all cases are covered
    };
  }
  
  /**
   * Helper method to assert that repository info is correct for each repository type.
   */
  private void assertRepositoryInfo(Repository repository, String repositoryInfo) {
    String formatId = repository.getFormat().getId();
    String name = repository.getName();
    
    if (repository instanceof HostedRepository) {
      if (((HostedRepository) repository).isWritable()) {
        assertThat(repositoryInfo, is(equalTo(String.format("%s writable hosted repository '%s'", formatId, name))));
      } else {
        assertThat(repositoryInfo, is(equalTo(String.format("%s hosted repository '%s' (writable: false)", formatId, name))));
      }
    } 
    else if (repository instanceof ProxyRepository) {
      String remoteUrl = ((ProxyRepository) repository).getRemoteUrl();
      if (remoteUrl.contains("npmjs")) {
        assertThat(repositoryInfo, is(equalTo(String.format("%s npm proxy repository '%s'", formatId, name))));
      } else {
        assertThat(repositoryInfo, is(equalTo(String.format("%s proxy repository '%s' (remote: %s)", formatId, name, remoteUrl))));
      }
    } 
    else if (repository instanceof GroupRepository) {
      int memberCount = ((GroupRepository) repository).getMembers().length;
      if (memberCount > 1) {
        assertThat(repositoryInfo, is(equalTo(String.format("%s multi-member group repository '%s' (members: %d)", formatId, name, memberCount))));
      } else {
        assertThat(repositoryInfo, is(equalTo(String.format("%s single-member group repository '%s'", formatId, name))));
      }
    }
  }
  
  /**
   * Helper method to assert that configuration info is correct for each configuration type.
   */
  private void assertConfigInfo(RepositoryConfiguration config, String configInfo) {
    if (config instanceof HostedRepositoryConfiguration) {
      HostedRepositoryConfiguration hosted = (HostedRepositoryConfiguration) config;
      assertThat(configInfo, is(equalTo(String.format("Hosted configuration '%s' (recipe: %s, writable: %s)", 
          hosted.getName(), hosted.getRecipeId(), hosted.isWritable()))));
    } 
    else if (config instanceof ProxyRepositoryConfiguration) {
      ProxyRepositoryConfiguration proxy = (ProxyRepositoryConfiguration) config;
      assertThat(configInfo, is(equalTo(String.format("Proxy configuration '%s' (recipe: %s, remote: %s)", 
          proxy.getName(), proxy.getRecipeId(), proxy.getRemoteUrl()))));
    } 
    else if (config instanceof GroupRepositoryConfiguration) {
      GroupRepositoryConfiguration group = (GroupRepositoryConfiguration) config;
      assertThat(configInfo, is(equalTo(String.format("Group configuration '%s' (recipe: %s, members: %d)", 
          group.getName(), group.getRecipeId(), group.getMemberNames().length))));
    }
  }
}