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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Test;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.entity.EntityId;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.mock;

/**
 * Tests for Java 21 Record Pattern matching with repository configuration data structures.
 * 
 * @since 3.60
 */
public class RecordPatternTest
    extends TestSupport
{
  /**
   * Simple record representing repository configuration attributes
   */
  record ConfigAttributes(String key, Map<String, Object> values) {}

  /**
   * Record representing a repository configuration
   */
  record RepoConfig(String name, String recipeName, boolean online, EntityId routingRuleId) {}

  /**
   * Nested record representing a complete repository configuration with attributes
   */
  record CompleteRepoConfig(RepoConfig config, List<ConfigAttributes> attributes) {}

  /**
   * Generic record for testing parameterized record patterns
   */
  record ConfigWrapper<T>(T value, String description) {}

  /**
   * Tests basic record pattern matching with instanceof operator.
   */
  @Test
  public void testBasicRecordPatternMatching() {
    // Create a repository configuration record
    EntityId mockEntityId = mock(EntityId.class);
    RepoConfig repoConfig = new RepoConfig("test-repo", "maven2", true, mockEntityId);
    
    // Test pattern matching with instanceof
    if (repoConfig instanceof RepoConfig(String name, String recipe, boolean isOnline, EntityId id)) {
      // Pattern variables are now in scope and can be used
      assertThat(name, is("test-repo"));
      assertThat(recipe, is("maven2"));
      assertThat(isOnline, is(true));
      assertThat(id, is(mockEntityId));
    }
    else {
      // This should never happen
      fail("Record pattern matching failed");
    }
  }

  /**
   * Tests record pattern matching with var for type inference.
   */
  @Test
  public void testRecordPatternMatchingWithVar() {
    EntityId mockEntityId = mock(EntityId.class);
    RepoConfig repoConfig = new RepoConfig("test-repo", "maven2", true, mockEntityId);
    
    // Test pattern matching with var for type inference
    if (repoConfig instanceof RepoConfig(var name, var recipe, var isOnline, var id)) {
      // The compiler infers the correct types for the pattern variables
      assertThat(name, is("test-repo"));
      assertThat(recipe, is("maven2"));
      assertThat(isOnline, is(true));
      assertThat(id, is(mockEntityId));
    }
    else {
      fail("Record pattern matching with var failed");
    }
  }

  /**
   * Tests nested record pattern matching for complex configuration structures.
   */
  @Test
  public void testNestedRecordPatternMatching() {
    EntityId mockEntityId = mock(EntityId.class);
    RepoConfig repoConfig = new RepoConfig("test-repo", "maven2", true, mockEntityId);
    
    ConfigAttributes storageAttrs = new ConfigAttributes("storage", 
        Map.of("blobStoreName", "default", "strictContentTypeValidation", true));
    ConfigAttributes proxyAttrs = new ConfigAttributes("proxy", 
        Map.of("remoteUrl", "https://repo.maven.apache.org/maven2/"));
    
    CompleteRepoConfig completeConfig = new CompleteRepoConfig(repoConfig, List.of(storageAttrs, proxyAttrs));
    
    // Test nested pattern matching
    if (completeConfig instanceof CompleteRepoConfig(RepoConfig(var name, var recipe, var isOnline, var id), var attrsList)) {
      // We can access both the outer and inner record components
      assertThat(name, is("test-repo"));
      assertThat(recipe, is("maven2"));
      assertThat(isOnline, is(true));
      assertThat(id, is(mockEntityId));
      assertThat(attrsList.size(), is(2));
      
      // We can further process the attributes list
      Optional<ConfigAttributes> storageConfig = attrsList.stream()
          .filter(attr -> attr.key().equals("storage"))
          .findFirst();
      
      assertThat(storageConfig.isPresent(), is(true));
      
      // We can use pattern matching on the found attribute
      if (storageConfig.isPresent() && storageConfig.get() instanceof ConfigAttributes(var key, var values)) {
        assertThat(key, is("storage"));
        assertThat(values.get("blobStoreName"), is("default"));
        assertThat((Boolean) values.get("strictContentTypeValidation"), is(true));
      }
      else {
        fail("Storage attributes not found or pattern matching failed");
      }
    }
    else {
      fail("Nested record pattern matching failed");
    }
  }

  /**
   * Tests record pattern matching in switch expressions.
   */
  @Test
  public void testRecordPatternMatchingInSwitch() {
    EntityId mockEntityId = mock(EntityId.class);
    RepoConfig mavenConfig = new RepoConfig("maven-central", "maven2", true, mockEntityId);
    RepoConfig npmConfig = new RepoConfig("npm-registry", "npm", false, mockEntityId);
    
    // Test pattern matching in switch expressions
    for (RepoConfig config : List.of(mavenConfig, npmConfig)) {
      String result = switch (config) {
        case RepoConfig(var name, "maven2", true, var id) -> 
            "Online Maven repository: " + name;
        case RepoConfig(var name, "npm", false, var id) -> 
            "Offline NPM repository: " + name;
        default -> 
            "Unknown repository configuration";
      };
      
      if (config == mavenConfig) {
        assertThat(result, is("Online Maven repository: maven-central"));
      }
      else if (config == npmConfig) {
        assertThat(result, is("Offline NPM repository: npm-registry"));
      }
    }
  }

  /**
   * Tests record pattern matching with parameterized records.
   */
  @Test
  public void testParameterizedRecordPatternMatching() {
    EntityId mockEntityId = mock(EntityId.class);
    RepoConfig repoConfig = new RepoConfig("test-repo", "maven2", true, mockEntityId);
    ConfigWrapper<RepoConfig> wrapper = new ConfigWrapper<>(repoConfig, "Maven repository configuration");
    
    // Test pattern matching with parameterized records
    if (wrapper instanceof ConfigWrapper<RepoConfig>(var config, var description)) {
      assertThat(description, is("Maven repository configuration"));
      assertThat(config, is(notNullValue()));
      
      // We can further pattern match on the extracted config
      if (config instanceof RepoConfig(var name, var recipe, var isOnline, var id)) {
        assertThat(name, is("test-repo"));
        assertThat(recipe, is("maven2"));
        assertThat(isOnline, is(true));
        assertThat(id, is(mockEntityId));
      }
      else {
        fail("Nested pattern matching on parameterized record failed");
      }
    }
    else {
      fail("Parameterized record pattern matching failed");
    }
  }

  /**
   * Tests record pattern matching with conditional expressions (guards).
   */
  @Test
  public void testRecordPatternMatchingWithGuards() {
    EntityId mockEntityId = mock(EntityId.class);
    RepoConfig repoConfig1 = new RepoConfig("maven-central", "maven2", true, mockEntityId);
    RepoConfig repoConfig2 = new RepoConfig("maven-snapshots", "maven2", true, mockEntityId);
    
    for (RepoConfig config : List.of(repoConfig1, repoConfig2)) {
      String result = switch (config) {
        case RepoConfig(var name, var recipe, var isOnline, var id) when name.contains("central") -> 
            "Central repository: " + name;
        case RepoConfig(var name, var recipe, var isOnline, var id) when name.contains("snapshots") -> 
            "Snapshots repository: " + name;
        default -> 
            "Other repository: " + config.name();
      };
      
      if (config == repoConfig1) {
        assertThat(result, is("Central repository: maven-central"));
      }
      else if (config == repoConfig2) {
        assertThat(result, is("Snapshots repository: maven-snapshots"));
      }
    }
  }
}