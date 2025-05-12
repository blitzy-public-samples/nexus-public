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
package org.sonatype.nexus.repository.search.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

public class ElasticSearchIndexServiceImplJsonTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  public void testRemoveAttributes() throws JsonProcessingException {
    String json = "{\n"
        + "  \"assets\": [\n"
        + "    {\n"
        + "      \"content_type\": \"text/plain\",\n"
        + "      \"name\": \"/info.txt\",\n"
        + "      \"attributes\": {\n"
        + "        \"cona.n\": {\n"
        + "          \"channel\": \"_\",\n"
        + "          \"baseVersion\": \"1.5.2\"\n"
        + "        },\n"
        + "        \"conan\": {\n"
        + "          \"channel\": \"_\",\n"
        + "          \"revision\": \"74cd46b9525f6d1af311660ef17ca48c\",\n"
        + "          \"packageId\": \"45708ed6e5806ef31e05ce6fc0318b81ec6e0f2c\",\n"
        + "          \"baseVersion\": \"1.5.2\",\n"
        + "          \"packageRevision\": \"27b6bae1eeb5886a6aa47e3deeae1789\"\n"
        + "        },\n"
        + "        \"checksum\": {\n"
        + "          \"md5\": \"68e017afcbbbc5f5fcbaa524ef445aab\"\n"
        + "        },\n"
        + "        \"content\": {\n"
        + "          \"last_modified\": 1681918942046\n"
        + "        }\n"
        + "      },\n"
        + "      \"id\": \"daa66d2e\"\n"
        + "    }\n"
        + "  ],\n"
        + "  \"format\": \"conan\",\n"
        + "  \"attributes\": {\n"
        + "    \"conan\": {\n"
        + "      \"channel\": \"_\",\n"
        + "      \"baseVersion\": \"1.5.2\"\n"
        + "    }\n"
        + "  },\n"
        + "  \"tags\": []\n"
        + "}";

    String newJson = ElasticSearchIndexServiceImpl.filterConanAssetAttributes(json);
    String expected = "{\n"
        + "  \"assets\": [\n"
        + "    {\n"
        + "      \"content_type\": \"text/plain\",\n"
        + "      \"name\": \"/info.txt\",\n"
        + "      \"attributes\": {\n"
        + "        \"conan\": {\n"
        + "          \"packageId\": \"45708ed6e5806ef31e05ce6fc0318b81ec6e0f2c\",\n"
        + "          \"packageRevision\": \"27b6bae1eeb5886a6aa47e3deeae1789\"\n"
        + "        },\n"
        + "        \"checksum\": {\n"
        + "          \"md5\": \"68e017afcbbbc5f5fcbaa524ef445aab\"\n"
        + "        }\n"
        + "      },\n"
        + "      \"id\": \"daa66d2e\"\n"
        + "    }\n"
        + "  ],\n"
        + "  \"format\": \"conan\",\n"
        + "  \"attributes\": {\n"
        + "    \"conan\": {\n"
        + "      \"channel\": \"_\",\n"
        + "      \"baseVersion\": \"1.5.2\"\n"
        + "    }\n"
        + "  },\n"
        + "  \"tags\": []\n"
        + "}";
    
    // Parse JSON for more detailed assertions
    JsonNode actualNode = mapper.readTree(newJson);
    JsonNode expectedNode = mapper.readTree(expected);
    
    // Main assertion comparing the entire JSON structure
    assertThat(actualNode).isEqualTo(expectedNode);
    
    // Additional assertions for specific elements in the JSON structure
    assertThat(actualNode.get("format").asText()).isEqualTo("conan");
    assertThat(actualNode.get("assets").isArray()).isTrue();
    assertThat(actualNode.get("assets").size()).isEqualTo(1);
    
    // Verify asset attributes
    JsonNode assetNode = actualNode.get("assets").get(0);
    assertThat(assetNode.get("content_type").asText()).isEqualTo("text/plain");
    assertThat(assetNode.get("name").asText()).isEqualTo("/info.txt");
    assertThat(assetNode.get("id").asText()).isEqualTo("daa66d2e");
    
    // Verify conan attributes
    JsonNode attributesNode = assetNode.get("attributes");
    assertThat(attributesNode.has("conan")).isTrue();
    assertThat(attributesNode.has("cona.n")).isFalse(); // Should be removed
    assertThat(attributesNode.has("content")).isFalse(); // Should be removed
    
    // Verify specific conan attributes
    JsonNode conanNode = attributesNode.get("conan");
    assertThat(conanNode.has("packageId")).isTrue();
    assertThat(conanNode.has("packageRevision")).isTrue();
    assertThat(conanNode.has("channel")).isFalse(); // Should be removed
    assertThat(conanNode.has("revision")).isFalse(); // Should be removed
    assertThat(conanNode.has("baseVersion")).isFalse(); // Should be removed
  }

  @Test
  public void testRemoveAttributesNoAssetAttributes() throws JsonProcessingException {
    String json = "{\n"
        + "  \"format\": \"conan\",\n"
        + "  \"attributes\": {\n"
        + "    \"conan\": {\n"
        + "      \"channel\": \"_\",\n"
        + "      \"baseVersion\": \"1.5.2\"\n"
        + "    }\n"
        + "  },\n"
        + "  \"tags\": []\n"
        + "}";

    String newJson = ElasticSearchIndexServiceImpl.filterConanAssetAttributes(json);
    
    // Parse JSON for assertions
    JsonNode actualNode = mapper.readTree(newJson);
    JsonNode expectedNode = mapper.readTree(json);
    
    // Main assertion comparing the entire JSON structure
    assertThat(actualNode).isEqualTo(expectedNode);
    
    // Additional assertions for specific elements
    assertThat(actualNode.get("format").asText()).isEqualTo("conan");
    assertThat(actualNode.has("assets")).isFalse();
    
    // Verify attributes structure is preserved
    JsonNode attributesNode = actualNode.get("attributes");
    assertThat(attributesNode.has("conan")).isTrue();
    
    // Verify conan attributes
    JsonNode conanNode = attributesNode.get("conan");
    assertThat(conanNode.get("channel").asText()).isEqualTo("_");
    assertThat(conanNode.get("baseVersion").asText()).isEqualTo("1.5.2");
  }

  @Test
  public void testRemoveAttributesNotConan() throws JsonProcessingException {
    String json = "{\n"
        + "  \"assets\": [\n"
        + "    {\n"
        + "      \"content_type\": \"text/plain\",\n"
        + "      \"name\": \"/info.txt\",\n"
        + "      \"attributes\": {\n"
        + "        \"cona.n\": {\n"
        + "          \"channel\": \"_\",\n"
        + "          \"baseVersion\": \"1.5.2\"\n"
        + "        },\n"
        + "        \"conan\": {\n"
        + "          \"channel\": \"_\",\n"
        + "          \"revision\": \"74cd46b9525f6d1af311660ef17ca48c\",\n"
        + "          \"packageId\": \"45708ed6e5806ef31e05ce6fc0318b81ec6e0f2c\",\n"
        + "          \"baseVersion\": \"1.5.2\",\n"
        + "          \"packageRevision\": \"27b6bae1eeb5886a6aa47e3deeae1789\"\n"
        + "        },\n"
        + "        \"checksum\": {\n"
        + "          \"md5\": \"68e017afcbbbc5f5fcbaa524ef445aab\"\n"
        + "        },\n"
        + "        \"content\": {\n"
        + "          \"last_modified\": 1681918942046\n"
        + "        }\n"
        + "      },\n"
        + "      \"id\": \"daa66d2e\"\n"
        + "    }\n"
        + "  ],\n"
        + "  \"format\": \"maven\",\n"
        + "  \"attributes\": {\n"
        + "    \"conan\": {\n"
        + "      \"channel\": \"_\",\n"
        + "      \"baseVersion\": \"1.5.2\"\n"
        + "    }\n"
        + "  },\n"
        + "  \"tags\": []\n"
        + "}";

    String newJson = ElasticSearchIndexServiceImpl.filterConanAssetAttributes(json);
    
    // Parse JSON for assertions
    JsonNode actualNode = mapper.readTree(newJson);
    JsonNode expectedNode = mapper.readTree(json);
    
    // Main assertion comparing the entire JSON structure
    assertThat(actualNode).isEqualTo(expectedNode);
    
    // Additional assertions for specific elements
    assertThat(actualNode.get("format").asText()).isEqualTo("maven");
    assertThat(actualNode.get("assets").isArray()).isTrue();
    assertThat(actualNode.get("assets").size()).isEqualTo(1);
    
    // Verify that for non-conan format, attributes are not modified
    JsonNode assetNode = actualNode.get("assets").get(0);
    JsonNode attributesNode = assetNode.get("attributes");
    
    // All original attributes should still be present
    assertThat(attributesNode.has("cona.n")).isTrue();
    assertThat(attributesNode.has("conan")).isTrue();
    assertThat(attributesNode.has("checksum")).isTrue();
    assertThat(attributesNode.has("content")).isTrue();
    
    // Verify conan attributes are unchanged
    JsonNode conanNode = attributesNode.get("conan");
    assertThat(conanNode.has("channel")).isTrue();
    assertThat(conanNode.has("revision")).isTrue();
    assertThat(conanNode.has("packageId")).isTrue();
    assertThat(conanNode.has("baseVersion")).isTrue();
    assertThat(conanNode.has("packageRevision")).isTrue();
  }
}