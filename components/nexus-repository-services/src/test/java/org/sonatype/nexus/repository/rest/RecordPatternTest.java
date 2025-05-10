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
import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for Java 21 record pattern matching with repository REST API models.
 */
@ExtendWith(MockitoExtension.class)
public class RecordPatternTest
    extends TestSupport
{
  private final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * Simple record representing a repository in the REST API.
   */
  public record RepositoryRecord(String name, String format, String type, String url) {}

  /**
   * Record with nested records for repository attributes.
   */
  public record RepositoryWithAttributesRecord(
      String name,
      String format,
      String type,
      String url,
      AttributesRecord attributes) {}

  /**
   * Record for repository attributes.
   */
  public record AttributesRecord(ProxyAttributesRecord proxy) {}

  /**
   * Record for proxy-specific attributes.
   */
  public record ProxyAttributesRecord(String remoteUrl) {}

  /**
   * Test class that demonstrates a REST API model using records.
   */
  public static class RepositoryApiModel {
    private final String name;
    private final String format;
    private final String type;
    private final String url;
    private final Map<String, Object> attributes;

    @JsonCreator
    public RepositoryApiModel(
        @JsonProperty("name") final String name,
        @JsonProperty("format") final String format,
        @JsonProperty("type") final String type,
        @JsonProperty("url") final String url,
        @JsonProperty("attributes") final Map<String, Object> attributes) {
      this.name = name;
      this.format = format;
      this.type = type;
      this.url = url;
      this.attributes = attributes;
    }

    public String getName() {
      return name;
    }

    public String getFormat() {
      return format;
    }

    public String getType() {
      return type;
    }

    public String getUrl() {
      return url;
    }

    public Map<String, Object> getAttributes() {
      return attributes;
    }
  }

  @Test
  void testSimpleRecordPatternMatching() {
    // Create a repository record
    RepositoryRecord repository = new RepositoryRecord(
        "maven-central",
        "maven2",
        "proxy",
        "http://localhost:8081/repository/maven-central");

    // Use pattern matching with the record
    if (repository instanceof RepositoryRecord(String name, String format, String type, String url)) {
      // We can directly use the extracted components
      assertThat(name, is("maven-central"));
      assertThat(format, is("maven2"));
      assertThat(type, is("proxy"));
      assertThat(url, is("http://localhost:8081/repository/maven-central"));
    } else {
      // This should never happen
      throw new AssertionError("Pattern matching failed");
    }
  }

  @Test
  void testNestedRecordPatternMatching() {
    // Create a repository with nested attributes
    ProxyAttributesRecord proxyAttributes = new ProxyAttributesRecord(
        "https://repo.maven.apache.org/maven2/");
    AttributesRecord attributes = new AttributesRecord(proxyAttributes);
    RepositoryWithAttributesRecord repository = new RepositoryWithAttributesRecord(
        "maven-central",
        "maven2",
        "proxy",
        "http://localhost:8081/repository/maven-central",
        attributes);

    // Use nested pattern matching to extract the remote URL directly
    if (repository instanceof RepositoryWithAttributesRecord(String name, String format, String type, 
        String url, AttributesRecord(ProxyAttributesRecord(String remoteUrl)))) {
      // We can directly use all extracted components including the deeply nested remoteUrl
      assertThat(name, is("maven-central"));
      assertThat(format, is("maven2"));
      assertThat(type, is("proxy"));
      assertThat(url, is("http://localhost:8081/repository/maven-central"));
      assertThat(remoteUrl, is("https://repo.maven.apache.org/maven2/"));
    } else {
      // This should never happen
      throw new AssertionError("Nested pattern matching failed");
    }
  }

  @Test
  void testPatternMatchingWithSwitch() {
    // Create a repository record
    RepositoryRecord repository = new RepositoryRecord(
        "maven-central",
        "maven2",
        "proxy",
        "http://localhost:8081/repository/maven-central");

    // Use pattern matching in a switch statement
    String result = switch (repository) {
      case RepositoryRecord(String name, String format, String type, var url) when "proxy".equals(type) ->
          "Proxy repository " + name + " for format " + format;
      case RepositoryRecord(String name, String format, String type, var url) when "hosted".equals(type) ->
          "Hosted repository " + name + " for format " + format;
      case RepositoryRecord(String name, String format, String type, var url) when "group".equals(type) ->
          "Group repository " + name + " for format " + format;
      default -> "Unknown repository type";
    };

    assertThat(result, is("Proxy repository maven-central for format maven2"));
  }

  @Test
  void testJsonSerializationWithRecordPatterns() throws IOException {
    // Create JSON for a repository
    String json = "{\"name\":\"maven-central\",\"format\":\"maven2\",\"type\":\"proxy\","
        + "\"url\":\"http://localhost:8081/repository/maven-central\","
        + "\"attributes\":{\"proxy\":{\"remoteUrl\":\"https://repo.maven.apache.org/maven2/\"}}}";

    // Deserialize to our model class
    RepositoryApiModel model = objectMapper.readValue(json, RepositoryApiModel.class);

    // Verify the model
    assertThat(model, notNullValue());
    assertThat(model.getName(), equalTo("maven-central"));
    assertThat(model.getFormat(), equalTo("maven2"));
    assertThat(model.getType(), equalTo("proxy"));
    
    // Extract and verify the proxy attributes using pattern matching with instanceof
    @SuppressWarnings("unchecked")
    Map<String, Object> proxyAttrs = (Map<String, Object>) model.getAttributes().get("proxy");
    assertThat(proxyAttrs, notNullValue());
    
    // Use pattern matching with Map.Entry to extract the remoteUrl
    for (Map.Entry<String, Object> entry : proxyAttrs.entrySet()) {
      if (entry instanceof Map.Entry<String, Object>(String key, Object value) && "remoteUrl".equals(key)) {
        assertThat(value, equalTo("https://repo.maven.apache.org/maven2/"));
      }
    }
  }

  @Test
  void testConvertingBetweenModelsWithPatternMatching() throws IOException {
    // Create JSON for a repository
    String json = "{\"name\":\"maven-central\",\"format\":\"maven2\",\"type\":\"proxy\","
        + "\"url\":\"http://localhost:8081/repository/maven-central\","
        + "\"attributes\":{\"proxy\":{\"remoteUrl\":\"https://repo.maven.apache.org/maven2/\"}}}";

    // Deserialize to our model class
    RepositoryApiModel model = objectMapper.readValue(json, RepositoryApiModel.class);

    // Convert to our record representation using pattern matching
    RepositoryWithAttributesRecord record = convertToRecord(model);

    // Verify the conversion using nested pattern matching
    if (record instanceof RepositoryWithAttributesRecord(String name, String format, String type, 
        String url, AttributesRecord(ProxyAttributesRecord(String remoteUrl)))) {
      assertThat(name, is("maven-central"));
      assertThat(format, is("maven2"));
      assertThat(type, is("proxy"));
      assertThat(url, is("http://localhost:8081/repository/maven-central"));
      assertThat(remoteUrl, is("https://repo.maven.apache.org/maven2/"));
    } else {
      throw new AssertionError("Conversion to record failed");
    }
  }

  /**
   * Converts a RepositoryApiModel to a RepositoryWithAttributesRecord using pattern matching.
   */
  private RepositoryWithAttributesRecord convertToRecord(RepositoryApiModel model) {
    // Extract proxy attributes if they exist
    ProxyAttributesRecord proxyAttributes = null;
    if (model.getAttributes() != null && model.getAttributes().containsKey("proxy")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> proxyAttrs = (Map<String, Object>) model.getAttributes().get("proxy");
      
      // Use pattern matching to extract the remoteUrl
      for (Map.Entry<String, Object> entry : proxyAttrs.entrySet()) {
        if (entry instanceof Map.Entry<String, Object>(String key, Object value) && "remoteUrl".equals(key)) {
          proxyAttributes = new ProxyAttributesRecord((String) value);
          break;
        }
      }
    }
    
    // Create the attributes record
    AttributesRecord attributes = new AttributesRecord(proxyAttributes);
    
    // Create and return the repository record
    return new RepositoryWithAttributesRecord(
        model.getName(),
        model.getFormat(),
        model.getType(),
        model.getUrl(),
        attributes);
  }
}