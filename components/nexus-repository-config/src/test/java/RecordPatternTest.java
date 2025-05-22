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
import java.util.Optional;

import org.junit.Test;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.entity.EntityId;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Tests for Java 21 Record Pattern matching with repository configuration objects.
 * 
 * @since 3.60
 */
public class RecordPatternTest
    extends TestSupport
{
  /**
   * Simple record representing basic repository configuration.
   */
  record RepositoryConfig(String name, String recipeName, boolean online, EntityId routingRuleId) {}

  /**
   * Record with nested attributes for testing nested pattern matching.
   */
  record NestedConfig(String name, AttributesConfig attributes) {}

  /**
   * Record representing configuration attributes.
   */
  record AttributesConfig(Map<String, Object> storage, Map<String, Object> proxy) {}

  /**
   * Record with generic type for testing type inference.
   */
  record GenericConfig<T>(String name, T value) {}

  /**
   * Tests basic record pattern matching with instanceof.
   */
  @Test
  public void testBasicRecordPatternMatching() {
    EntityId mockId = mock(EntityId.class);
    Object config = new RepositoryConfig("maven-central", "maven2-proxy", true, mockId);
    
    // Traditional instanceof approach (pre-Java 21)
    if (config instanceof RepositoryConfig) {
      RepositoryConfig rc = (RepositoryConfig) config;
      assertEquals("maven-central", rc.name());
      assertEquals("maven2-proxy", rc.recipeName());
      assertTrue(rc.online());
      assertThat(rc.routingRuleId(), is(mockId));
    }
    
    // Java 21 record pattern matching with instanceof
    if (config instanceof RepositoryConfig(String name, String recipe, boolean online, EntityId id)) {
      assertEquals("maven-central", name);
      assertEquals("maven2-proxy", recipe);
      assertTrue(online);
      assertThat(id, is(mockId));
    }
  }

  /**
   * Tests nested record pattern matching.
   */
  @Test
  public void testNestedRecordPatternMatching() {
    Map<String, Object> storageAttrs = Map.of("blobStoreName", "default", "strictContentTypeValidation", true);
    Map<String, Object> proxyAttrs = Map.of("remoteUrl", "https://repo.maven.apache.org/maven2/", "contentMaxAge", 1440);
    
    AttributesConfig attrs = new AttributesConfig(storageAttrs, proxyAttrs);
    Object config = new NestedConfig("maven-central", attrs);
    
    // Java 21 nested record pattern matching
    if (config instanceof NestedConfig(String name, AttributesConfig(Map<String, Object> storage, Map<String, Object> proxy))) {
      assertEquals("maven-central", name);
      assertEquals("default", storage.get("blobStoreName"));
      assertEquals(true, storage.get("strictContentTypeValidation"));
      assertEquals("https://repo.maven.apache.org/maven2/", proxy.get("remoteUrl"));
      assertEquals(1440, proxy.get("contentMaxAge"));
    }
  }

  /**
   * Tests record pattern matching with type inference using var.
   */
  @Test
  public void testRecordPatternMatchingWithTypeInference() {
    Object config = new RepositoryConfig("maven-central", "maven2-proxy", true, mock(EntityId.class));
    
    // Java 21 record pattern matching with var for type inference
    if (config instanceof RepositoryConfig(var name, var recipe, var online, var id)) {
      assertEquals("maven-central", name);
      assertEquals("maven2-proxy", recipe);
      assertTrue(online);
      assertThat(id, is(notNullValue()));
    }
  }

  /**
   * Tests record pattern matching in switch expressions.
   */
  @Test
  public void testRecordPatternMatchingInSwitch() {
    EntityId mockId = mock(EntityId.class);
    Object config = new RepositoryConfig("maven-central", "maven2-proxy", true, mockId);
    
    // Java 21 record pattern matching in switch expression
    String result = switch (config) {
      case RepositoryConfig(String name, String recipe, boolean online, EntityId id) ->
          name + ":" + recipe + ":" + (online ? "online" : "offline");
      default -> "unknown";
    };
    
    assertEquals("maven-central:maven2-proxy:online", result);
  }

  /**
   * Tests record pattern matching with conditional guards.
   */
  @Test
  public void testRecordPatternMatchingWithGuards() {
    EntityId mockId = mock(EntityId.class);
    Object config1 = new RepositoryConfig("maven-central", "maven2-proxy", true, mockId);
    Object config2 = new RepositoryConfig("maven-releases", "maven2-hosted", false, mockId);
    
    // Java 21 record pattern matching with guards in switch
    String result1 = switch (config1) {
      case RepositoryConfig(String name, String recipe, boolean online, EntityId id) when online ->
          name + " is online";
      case RepositoryConfig(String name, String recipe, boolean online, EntityId id) ->
          name + " is offline";
      default -> "unknown";
    };
    
    String result2 = switch (config2) {
      case RepositoryConfig(String name, String recipe, boolean online, EntityId id) when online ->
          name + " is online";
      case RepositoryConfig(String name, String recipe, boolean online, EntityId id) ->
          name + " is offline";
      default -> "unknown";
    };
    
    assertEquals("maven-central is online", result1);
    assertEquals("maven-releases is offline", result2);
  }

  /**
   * Tests generic record pattern matching with type inference.
   */
  @Test
  public void testGenericRecordPatternMatching() {
    GenericConfig<Integer> intConfig = new GenericConfig<>("count", 42);
    GenericConfig<String> strConfig = new GenericConfig<>("message", "Hello");
    
    // Java 21 generic record pattern matching with type inference
    processGenericConfig(intConfig);
    processGenericConfig(strConfig);
  }
  
  private <T> void processGenericConfig(Object config) {
    if (config instanceof GenericConfig<T>(String name, T value)) {
      if (value instanceof Integer i) {
        assertEquals("count", name);
        assertEquals(Integer.valueOf(42), i);
      } else if (value instanceof String s) {
        assertEquals("message", name);
        assertEquals("Hello", s);
      }
    }
  }

  /**
   * Tests record pattern matching with Optional.
   */
  @Test
  public void testRecordPatternMatchingWithOptional() {
    Optional<RepositoryConfig> optConfig = Optional.of(
        new RepositoryConfig("maven-central", "maven2-proxy", true, mock(EntityId.class)));
    
    // Java 21 record pattern matching with Optional
    if (optConfig.isPresent() && optConfig.get() instanceof RepositoryConfig(var name, var recipe, var online, var id)) {
      assertEquals("maven-central", name);
      assertEquals("maven2-proxy", recipe);
      assertTrue(online);
      assertThat(id, is(notNullValue()));
    }
  }
}