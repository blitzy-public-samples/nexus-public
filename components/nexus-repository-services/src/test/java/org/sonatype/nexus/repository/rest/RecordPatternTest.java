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
package org.sonatype.nexus.repository.rest;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.rest.api.ComponentXO;
import org.sonatype.nexus.repository.rest.api.RepositoryXO;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.types.ProxyType;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Test class demonstrating Java 21 record pattern matching with repository REST API models.
 * 
 * This class shows how record patterns can be used to simplify working with data models
 * in the REST API layer, particularly for JSON serialization/deserialization scenarios.
 * 
 * Java 21 introduces record patterns, which allow for destructuring record values in
 * pattern matching contexts. This enables more concise and type-safe code when working
 * with nested data structures, which is common in REST API models.
 * 
 * The tests in this class demonstrate various use cases for record patterns:
 * - Simple record pattern matching
 * - Nested record pattern matching
 * - Pattern matching with JSON serialization/deserialization
 * - Pattern matching with existing non-record classes
 * - Pattern matching in switch expressions
 * - Pattern matching with guards for validation
 * - Pattern matching with Optional values
 */
@ExtendWith(MockitoExtension.class)
public class RecordPatternTest
    extends TestSupport
{
  private ObjectMapper objectMapper;

  @BeforeEach
  public void setUp() {
    objectMapper = new ObjectMapper();
  }

  /**
   * Simple record representing a repository component in the REST API.
   * Records provide a concise way to model immutable data with automatic
   * getters, equals, hashCode, and toString implementations.
   */
  record ComponentModel(String id, String name, String version, String format) {}

  /**
   * Record representing a repository in the REST API.
   * This record models the essential properties of a repository without
   * the need for boilerplate getter/setter methods.
   */
  record RepositoryModel(String name, String type, String format, String url) {}

  /**
   * Nested record structure representing a repository with its components.
   * This demonstrates how records can be composed to create more complex
   * data structures while maintaining immutability and type safety.
   */
  record RepositoryWithComponents(RepositoryModel repository, List<ComponentModel> components) {}

  /**
   * Test demonstrating simple record pattern matching with a component model.
   * This test shows the basic syntax for record pattern matching, which allows
   * extracting and binding record components in a single operation.
   */
  @Test
  public void testSimpleRecordPattern() {
    // Create a component model record
    ComponentModel component = new ComponentModel("test-id", "test-component", "1.0.0", "maven2");
    
    // Use pattern matching to extract fields
    if (component instanceof ComponentModel(String id, String name, String version, String format)) {
      // Verify extracted fields
      assertThat(id, is("test-id"));
      assertThat(name, is("test-component"));
      assertThat(version, is("1.0.0"));
      assertThat(format, is("maven2"));
    }
    else {
      // This should never happen if pattern matching is working correctly
      throw new AssertionError("Pattern matching failed");
    }
  }

  /**
   * Test demonstrating nested record pattern matching with repository and components.
   * This test shows how Java 21's nested record patterns can be used to extract data
   * from complex nested structures in a single pattern matching operation.
   */
  @Test
  public void testNestedRecordPattern() {
    // Create repository model
    RepositoryModel repo = new RepositoryModel("maven-central", "proxy", "maven2", "http://localhost:8081/repository/maven-central");
    
    // Create component models
    ComponentModel component1 = new ComponentModel("c1", "component-1", "1.0.0", "maven2");
    ComponentModel component2 = new ComponentModel("c2", "component-2", "2.0.0", "maven2");
    
    // Create nested structure
    RepositoryWithComponents repoWithComponents = 
        new RepositoryWithComponents(repo, List.of(component1, component2));
    
    // Use nested pattern matching to extract repository and first component
    if (repoWithComponents instanceof RepositoryWithComponents(RepositoryModel r, List<ComponentModel> components) 
        && !components.isEmpty() 
        && components.get(0) instanceof ComponentModel(String id, String name, var version, var format)) {
      
      // Verify repository fields
      assertThat(r.name(), is("maven-central"));
      assertThat(r.type(), is("proxy"));
      assertThat(r.format(), is("maven2"));
      
      // Verify first component fields extracted via pattern matching
      assertThat(id, is("c1"));
      assertThat(name, is("component-1"));
      assertThat(version, is("1.0.0"));
      assertThat(format, is("maven2"));
      
      // Verify components list size
      assertThat(components.size(), is(2));
    }
    else {
      // This should never happen if pattern matching is working correctly
      throw new AssertionError("Nested pattern matching failed");
    }
  }

  /**
   * Test demonstrating JSON serialization and deserialization with record patterns.
   * This test shows how record patterns can be used with Jackson's ObjectMapper
   * to simplify working with serialized JSON data.
   */
  @Test
  public void testJsonSerializationWithRecordPatterns() throws IOException {
    // Create repository model
    RepositoryModel repo = new RepositoryModel("maven-central", "proxy", "maven2", "http://localhost:8081/repository/maven-central");
    
    // Serialize to JSON
    String json = objectMapper.writeValueAsString(repo);
    
    // Deserialize from JSON
    RepositoryModel deserializedRepo = objectMapper.readValue(json, RepositoryModel.class);
    
    // Use pattern matching to extract and verify fields
    if (deserializedRepo instanceof RepositoryModel(String name, String type, String format, String url)) {
      assertThat(name, is("maven-central"));
      assertThat(type, is("proxy"));
      assertThat(format, is("maven2"));
      assertThat(url, is("http://localhost:8081/repository/maven-central"));
    }
    else {
      // This should never happen if pattern matching is working correctly
      throw new AssertionError("Pattern matching after deserialization failed");
    }
  }

  /**
   * Test demonstrating pattern matching with complex nested JSON structures.
   * This test shows how Java 21's record patterns can be used to extract and validate
   * data from complex nested JSON structures after deserialization.
   */
  @Test
  public void testComplexJsonWithRecordPatterns() throws IOException {
    // Create repository model
    RepositoryModel repo = new RepositoryModel("maven-central", "proxy", "maven2", "http://localhost:8081/repository/maven-central");
    
    // Create component models
    ComponentModel component1 = new ComponentModel("c1", "component-1", "1.0.0", "maven2");
    ComponentModel component2 = new ComponentModel("c2", "component-2", "2.0.0", "maven2");
    
    // Create nested structure
    RepositoryWithComponents repoWithComponents = 
        new RepositoryWithComponents(repo, List.of(component1, component2));
    
    // Serialize to JSON
    String json = objectMapper.writeValueAsString(repoWithComponents);
    
    // Deserialize from JSON
    RepositoryWithComponents deserializedRepo = objectMapper.readValue(json, RepositoryWithComponents.class);
    
    // Use nested pattern matching to extract and verify fields
    if (deserializedRepo instanceof RepositoryWithComponents(RepositoryModel(String name, String type, var format, var url), 
                                                           List<ComponentModel> components)) {
      // Verify repository fields extracted via pattern matching
      assertThat(name, is("maven-central"));
      assertThat(type, is("proxy"));
      assertThat(format, is("maven2"));
      assertThat(url, is("http://localhost:8081/repository/maven-central"));
      
      // Verify components list
      assertThat(components.size(), is(2));
      
      // Use pattern matching with the first component
      ComponentModel firstComponent = components.get(0);
      if (firstComponent instanceof ComponentModel(String id, var componentName, var version, var componentFormat)) {
        assertThat(id, is("c1"));
        assertThat(componentName, is("component-1"));
        assertThat(version, is("1.0.0"));
        assertThat(componentFormat, is("maven2"));
      }
      else {
        throw new AssertionError("Component pattern matching failed");
      }
    }
    else {
      // This should never happen if pattern matching is working correctly
      throw new AssertionError("Complex nested pattern matching failed");
    }
  }

  /**
   * Test demonstrating pattern matching with existing RepositoryXO model.
   * This test shows how record patterns can be used with existing non-record classes
   * by wrapping them in records.
   */
  @Test
  public void testPatternMatchingWithRepositoryXO() {
    // Create a RepositoryXO instance
    RepositoryXO repositoryXO = new RepositoryXO();
    repositoryXO.setName("maven-central");
    repositoryXO.setFormat("maven2");
    repositoryXO.setType("proxy");
    repositoryXO.setUrl("http://localhost:8081/repository/maven-central");
    repositoryXO.setAttributes(Map.of("proxy", Map.of("remoteUrl", "https://repo1.maven.org/maven2/")));
    
    // Create a record wrapper for the RepositoryXO
    record RepositoryWrapper(RepositoryXO repository, String environment) {}
    
    // Create a wrapper instance
    RepositoryWrapper wrapper = new RepositoryWrapper(repositoryXO, "production");
    
    // Use pattern matching to extract fields
    if (wrapper instanceof RepositoryWrapper(RepositoryXO repo, String env)) {
      // Verify extracted fields
      assertThat(repo, notNullValue());
      assertThat(repo.getName(), is("maven-central"));
      assertThat(repo.getFormat(), is("maven2"));
      assertThat(repo.getType(), is("proxy"));
      assertThat(env, is("production"));
      
      // Access attributes from the extracted repository
      Map<String, Map<String, Object>> attributes = repo.getAttributes();
      assertThat(attributes, notNullValue());
      assertThat(attributes.containsKey("proxy"), is(true));
      
      // Extract and verify the remote URL using pattern matching on the Map structure
      if (attributes.get("proxy") instanceof Map<String, Object> proxyAttrs && 
          proxyAttrs.get("remoteUrl") instanceof String remoteUrl) {
        assertThat(remoteUrl, is("https://repo1.maven.org/maven2/"));
      }
      else {
        throw new AssertionError("Map pattern matching failed");
      }
    }
    else {
      // This should never happen if pattern matching is working correctly
      throw new AssertionError("RepositoryXO pattern matching failed");
    }
  }
  
  /**
   * Test demonstrating pattern matching with Optional values in repository models.
   * This test shows how Java 21's pattern matching can be used with Optional values
   * to handle nullable fields in a type-safe way.
   */
  @Test
  public void testPatternMatchingWithOptionals() {
    // Create a record with Optional fields to represent a repository with optional attributes
    record OptionalRepositoryModel(String name, String type, String format, Optional<String> description) {}
    
    // Create repositories with and without descriptions
    OptionalRepositoryModel repoWithDesc = 
        new OptionalRepositoryModel("maven-central", "proxy", "maven2", Optional.of("Central Maven repository"));
    OptionalRepositoryModel repoWithoutDesc = 
        new OptionalRepositoryModel("maven-snapshots", "hosted", "maven2", Optional.empty());
    
    // Process repository with description using pattern matching
    String descResult = switch (repoWithDesc) {
      // Match when description is present
      case OptionalRepositoryModel(var name, var type, var format, Optional.of(var desc)) ->
          "Repository " + name + " description: " + desc;
      // Match when description is not present
      case OptionalRepositoryModel(var name, var type, var format, Optional.empty()) ->
          "Repository " + name + " has no description";
    };
    
    // Process repository without description using pattern matching
    String noDescResult = switch (repoWithoutDesc) {
      // Match when description is present
      case OptionalRepositoryModel(var name, var type, var format, Optional.of(var desc)) ->
          "Repository " + name + " description: " + desc;
      // Match when description is not present
      case OptionalRepositoryModel(var name, var type, var format, Optional.empty()) ->
          "Repository " + name + " has no description";
    };
    
    // Verify results
    assertThat(descResult, is("Repository maven-central description: Central Maven repository"));
    assertThat(noDescResult, is("Repository maven-snapshots has no description"));
  }

  /**
   * Test demonstrating pattern matching with conditional logic for different repository types.
   * This test shows how Java 21's pattern matching in switch statements can be used to handle
   * different repository types in a concise and type-safe manner.
   */
  @Test
  public void testPatternMatchingWithConditionalLogic() {
    // Create different repository model types
    RepositoryModel proxyRepo = new RepositoryModel("maven-central", "proxy", "maven2", "http://localhost:8081/repository/maven-central");
    RepositoryModel hostedRepo = new RepositoryModel("maven-releases", "hosted", "maven2", "http://localhost:8081/repository/maven-releases");
    RepositoryModel groupRepo = new RepositoryModel("maven-public", "group", "maven2", "http://localhost:8081/repository/maven-public");
    
    // Test proxy repository pattern matching
    String proxyResult = switch (proxyRepo) {
      case RepositoryModel(var name, "proxy", var format, var url) -> 
          "Proxy repository " + name + " for format " + format;
      case RepositoryModel(var name, "hosted", var format, var url) -> 
          "Hosted repository " + name + " for format " + format;
      case RepositoryModel(var name, "group", var format, var url) -> 
          "Group repository " + name + " for format " + format;
      default -> "Unknown repository type";
    };
    
    assertThat(proxyResult, is("Proxy repository maven-central for format maven2"));
    
    // Test hosted repository pattern matching
    String hostedResult = switch (hostedRepo) {
      case RepositoryModel(var name, "proxy", var format, var url) -> 
          "Proxy repository " + name + " for format " + format;
      case RepositoryModel(var name, "hosted", var format, var url) -> 
          "Hosted repository " + name + " for format " + format;
      case RepositoryModel(var name, "group", var format, var url) -> 
          "Group repository " + name + " for format " + format;
      default -> "Unknown repository type";
    };
    
    assertThat(hostedResult, is("Hosted repository maven-releases for format maven2"));
    
    // Test group repository pattern matching
    String groupResult = switch (groupRepo) {
      case RepositoryModel(var name, "proxy", var format, var url) -> 
          "Proxy repository " + name + " for format " + format;
      case RepositoryModel(var name, "hosted", var format, var url) -> 
          "Hosted repository " + name + " for format " + format;
      case RepositoryModel(var name, "group", var format, var url) -> 
          "Group repository " + name + " for format " + format;
      default -> "Unknown repository type";
    };
    
    assertThat(groupResult, is("Group repository maven-public for format maven2"));
  }
  
  /**
   * Test demonstrating pattern matching with guarded patterns for repository validation.
   * This test shows how Java 21's pattern matching with guards can be used to implement
   * validation logic in a concise and readable way.
   */
  @Test
  public void testPatternMatchingWithGuards() {
    // Create valid and invalid repository models
    RepositoryModel validRepo = new RepositoryModel("maven-central", "proxy", "maven2", "http://localhost:8081/repository/maven-central");
    RepositoryModel invalidRepo = new RepositoryModel("", "proxy", "maven2", "http://localhost:8081/repository/maven-central");
    RepositoryModel invalidTypeRepo = new RepositoryModel("maven-central", "invalid-type", "maven2", "http://localhost:8081/repository/maven-central");
    
    // Validate repositories using pattern matching with guards
    boolean validRepoResult = switch (validRepo) {
      // Valid proxy repository - name not empty and type is valid
      case RepositoryModel(String name, String type, var format, var url) 
          when !name.isEmpty() && (type.equals("proxy") || type.equals("hosted") || type.equals("group")) -> true;
      // Invalid repository - fails validation
      default -> false;
    };
    
    boolean invalidRepoResult = switch (invalidRepo) {
      // Valid repository - name not empty and type is valid
      case RepositoryModel(String name, String type, var format, var url) 
          when !name.isEmpty() && (type.equals("proxy") || type.equals("hosted") || type.equals("group")) -> true;
      // Invalid repository - fails validation
      default -> false;
    };
    
    boolean invalidTypeRepoResult = switch (invalidTypeRepo) {
      // Valid repository - name not empty and type is valid
      case RepositoryModel(String name, String type, var format, var url) 
          when !name.isEmpty() && (type.equals("proxy") || type.equals("hosted") || type.equals("group")) -> true;
      // Invalid repository - fails validation
      default -> false;
    };
    
    // Verify validation results
    assertThat(validRepoResult, is(true));
    assertThat(invalidRepoResult, is(false));
    assertThat(invalidTypeRepoResult, is(false));
  }
}