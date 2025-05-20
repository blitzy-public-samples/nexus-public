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
package org.sonatype.nexus.repository.json;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.virtualthread.Java21TestGroup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.experimental.categories.Category;

import static com.fasterxml.jackson.databind.SerializationFeature.FLUSH_AFTER_WRITE_VALUE;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Category(Java21TestGroup.class)
public class StreamingObjectMapperTest
    extends TestSupport
{
  private StreamingObjectMapper underTest = new StreamingObjectMapper();

  @Test
  public void writeExactlyWhatWasRead() throws IOException {
    String json = "{}";
    ByteArrayInputStream input = new ByteArrayInputStream(json.getBytes());
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    underTest.readAndWrite(input, output);

    assertThat(json, equalTo(new String(output.toByteArray())));

    json = "{\"_id\":\"simple\",\"name\":\"simple\",\"description\":\"simplestuff\"}";
    input = new ByteArrayInputStream(json.getBytes());
    output = new ByteArrayOutputStream();
    underTest.readAndWrite(input, output);

    assertThat(json, equalTo(new String(output.toByteArray())));
  }

  @Test
  public void writeMinimizedJsonOfWhatWasRead() throws IOException {
    String prettyJson = "{\n\"_id\": \"simple\",\n\"name\": \"simple\",\n\"description\": \"simplestuff\"}";

    ByteArrayInputStream input = new ByteArrayInputStream(prettyJson.getBytes());
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    underTest.readAndWrite(input, output);

    // Using Java 21 String Templates for formatting validation
    String minifiedJson = STR."{prettyJson.replaceAll("\\n", "").replace(" ", "")}";
    assertEquals(minifiedJson, new String(output.toByteArray()));
  }

  @Test
  public void configureSerializationFeature() {
    assertTrue(underTest.isEnabled(FLUSH_AFTER_WRITE_VALUE));

    underTest.configure(FLUSH_AFTER_WRITE_VALUE, false);

    assertFalse(underTest.isEnabled(FLUSH_AFTER_WRITE_VALUE));
  }
  
  @Test
  public void validateJsonFormattingWithRecordPatternMatching() throws JsonProcessingException {
    // Define a record to represent JSON structure
    record JsonData(String _id, String name, String description) {}
    
    // Create test data
    JsonData testData = new JsonData("test-id", "test-name", "test-description");
    
    // Convert to JSON
    ObjectMapper mapper = new ObjectMapper();
    String json = mapper.writeValueAsString(testData);
    
    // Read JSON back using StreamingObjectMapper
    ByteArrayInputStream input = new ByteArrayInputStream(json.getBytes());
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    
    try {
      underTest.readAndWrite(input, output);
      String result = new String(output.toByteArray());
      
      // Parse back to object for validation using record pattern matching
      JsonData parsedData = mapper.readValue(result, JsonData.class);
      
      // Using Java 21 record pattern matching for validation
      if (parsedData instanceof JsonData(String id, String name, String description)) {
        assertEquals("test-id", id);
        assertEquals("test-name", name);
        assertEquals("test-description", description);
      } else {
        throw new AssertionError("Record pattern matching failed");
      }
    } catch (IOException e) {
      throw new AssertionError("Failed to process JSON", e);
    }
  }
}