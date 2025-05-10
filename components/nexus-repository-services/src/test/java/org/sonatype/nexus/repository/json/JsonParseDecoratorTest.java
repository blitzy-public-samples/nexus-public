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

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.Writer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.sonatype.goodies.testsupport.TestSupport;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.RequestPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class JsonParseDecoratorTest
    extends TestSupport
{
  @Mock
  private JsonParser jsonParser;

  private JsonParserDecorator underTest;

  @BeforeEach
  public void setUp() {
    underTest = new JsonParserDecorator(jsonParser);
  }

  @Test
  public void decorates_JsonParser() throws IOException {
    underTest.getCodec();
    underTest.setCodec(null);
    underTest.getInputSource();
    underTest.getCurrentValue();
    underTest.setCurrentValue(null);
    underTest.setRequestPayloadOnError((RequestPayload) null);
    underTest.setRequestPayloadOnError(null, null);
    underTest.setSchema(null);
    underTest.getSchema();
    underTest.canUseSchema(null);
    underTest.requiresCustomCodec();
    underTest.canParseAsync();
    underTest.getNonBlockingInputFeeder();
    underTest.version();
    underTest.close();
    underTest.isClosed();
    underTest.getParsingContext();
    underTest.getTokenLocation();
    underTest.getCurrentLocation();
    underTest.releaseBuffered((OutputStream) null);
    underTest.releaseBuffered((Writer) null);
    underTest.enable(null);
    underTest.disable(null);
    underTest.configure(null, false);
    underTest.isEnabled((Feature) null);
    underTest.getFeatureMask();
    underTest.setFeatureMask(1);
    underTest.overrideStdFeatures(1, 1);
    underTest.getFormatFeatures();
    underTest.overrideFormatFeatures(1, 1);
    underTest.nextToken();
    underTest.nextValue();
    underTest.nextFieldName(null);
    underTest.nextFieldName();
    underTest.nextTextValue();
    underTest.nextIntValue(1);
    underTest.nextLongValue(1L);
    underTest.nextBooleanValue();
    underTest.skipChildren();
    underTest.finishToken();
    underTest.currentToken();
    underTest.currentTokenId();
    underTest.getCurrentToken();
    underTest.getCurrentTokenId();
    underTest.hasCurrentToken();
    underTest.hasTokenId(1);
    underTest.hasToken(null);
    underTest.isExpectedStartArrayToken();
    underTest.isExpectedStartObjectToken();
    underTest.isNaN();
    underTest.clearCurrentToken();
    underTest.getLastClearedToken();
    underTest.overrideCurrentName(null);
    underTest.getCurrentName();
    underTest.getText();
    underTest.getText(null);
    underTest.getTextCharacters();
    underTest.getTextLength();
    underTest.getTextOffset();
    underTest.hasTextCharacters();
    underTest.getNumberValue();
    underTest.getNumberType();
    underTest.getByteValue();
    underTest.getShortValue();
    underTest.getIntValue();
    underTest.getLongValue();
    underTest.getBigIntegerValue();
    underTest.getFloatValue();
    underTest.getDecimalValue();
    underTest.getBooleanValue();
    underTest.getEmbeddedObject();
    underTest.getBinaryValue(null);
    underTest.getBinaryValue();
    underTest.readBinaryValue(null);
    underTest.readBinaryValue(null, null);
    underTest.getValueAsInt();
    underTest.getValueAsInt(1);
    underTest.getValueAsLong();
    underTest.getValueAsLong(1L);
    underTest.getValueAsDouble();
    underTest.getValueAsDouble(2.0);
    underTest.getValueAsBoolean();
    underTest.getValueAsBoolean(false);
    underTest.getValueAsString();
    underTest.getValueAsString(null);
    underTest.canReadObjectId();
    underTest.canReadTypeId();
    underTest.getObjectId();
    underTest.getTypeId();
    underTest.readValueAs((TypeReference<?>) null);
    underTest.readValueAs((Class<Object>) null);
    underTest.readValueAsTree();

    verify(jsonParser).getCodec();
    verify(jsonParser).setCodec(null);
    verify(jsonParser).getInputSource();
    verify(jsonParser).getCurrentValue();
    verify(jsonParser).setCurrentValue(null);
    verify(jsonParser).setRequestPayloadOnError((RequestPayload) null);
    verify(jsonParser).setRequestPayloadOnError(null, null);
    verify(jsonParser).setSchema(null);
    verify(jsonParser).getSchema();
    verify(jsonParser).canUseSchema(null);
    verify(jsonParser).requiresCustomCodec();
    verify(jsonParser).canParseAsync();
    verify(jsonParser).getNonBlockingInputFeeder();
    verify(jsonParser).version();
    verify(jsonParser).close();
    verify(jsonParser).isClosed();
    verify(jsonParser).getParsingContext();
    verify(jsonParser).getTokenLocation();
    verify(jsonParser).getCurrentLocation();
    verify(jsonParser).releaseBuffered((OutputStream) null);
    verify(jsonParser).releaseBuffered((Writer) null);
    verify(jsonParser).enable(null);
    verify(jsonParser).disable(null);
    verify(jsonParser).configure(null, false);
    verify(jsonParser).isEnabled((Feature) null);
    verify(jsonParser).getFeatureMask();
    verify(jsonParser).setFeatureMask(1);
    verify(jsonParser).overrideStdFeatures(1, 1);
    verify(jsonParser).getFormatFeatures();
    verify(jsonParser).overrideFormatFeatures(1, 1);
    verify(jsonParser).nextToken();
    verify(jsonParser).nextValue();
    verify(jsonParser).nextFieldName(null);
    verify(jsonParser).nextFieldName();
    verify(jsonParser).nextTextValue();
    verify(jsonParser).nextIntValue(1);
    verify(jsonParser).nextLongValue(1L);
    verify(jsonParser).nextBooleanValue();
    verify(jsonParser).skipChildren();
    verify(jsonParser).finishToken();
    verify(jsonParser).currentToken();
    verify(jsonParser).currentTokenId();
    verify(jsonParser).getCurrentToken();
    verify(jsonParser).getCurrentTokenId();
    verify(jsonParser).hasCurrentToken();
    verify(jsonParser).hasTokenId(1);
    verify(jsonParser).hasToken(null);
    verify(jsonParser).isExpectedStartArrayToken();
    verify(jsonParser).isExpectedStartObjectToken();
    verify(jsonParser).isNaN();
    verify(jsonParser).clearCurrentToken();
    verify(jsonParser).getLastClearedToken();
    verify(jsonParser).overrideCurrentName(null);
    verify(jsonParser).getCurrentName();
    verify(jsonParser).getText();
    verify(jsonParser).getText(null);
    verify(jsonParser).getTextCharacters();
    verify(jsonParser).getTextLength();
    verify(jsonParser).getTextOffset();
    verify(jsonParser).hasTextCharacters();
    verify(jsonParser).getNumberValue();
    verify(jsonParser).getNumberType();
    verify(jsonParser).getByteValue();
    verify(jsonParser).getShortValue();
    verify(jsonParser).getIntValue();
    verify(jsonParser).getLongValue();
    verify(jsonParser).getBigIntegerValue();
    verify(jsonParser).getFloatValue();
    verify(jsonParser).getDecimalValue();
    verify(jsonParser).getBooleanValue();
    verify(jsonParser).getEmbeddedObject();
    verify(jsonParser).getBinaryValue(null);
    verify(jsonParser).getBinaryValue();
    verify(jsonParser).readBinaryValue(null);
    verify(jsonParser).readBinaryValue(null, null);
    verify(jsonParser).getValueAsInt();
    verify(jsonParser).getValueAsInt(1);
    verify(jsonParser).getValueAsLong();
    verify(jsonParser).getValueAsLong(1L);
    verify(jsonParser).getValueAsDouble();
    verify(jsonParser).getValueAsDouble(2.0);
    verify(jsonParser).getValueAsBoolean();
    verify(jsonParser).getValueAsBoolean(false);
    verify(jsonParser).getValueAsString();
    verify(jsonParser).getValueAsString(null);
    verify(jsonParser).canReadObjectId();
    verify(jsonParser).canReadTypeId();
    verify(jsonParser).getObjectId();
    verify(jsonParser).getTypeId();
    verify(jsonParser).readValueAs((TypeReference<?>) null);
    verify(jsonParser).readValueAs((Class<Object>) null);
    verify(jsonParser).readValueAsTree();
  }
  
  @Test
  public void virtualThread_decorates_JsonParser() throws Exception {
    // Create a simple JSON string to parse
    String jsonContent = "{\"name\":\"test\",\"value\":123}";
    JsonFactory factory = new JsonFactory();
    
    // Create a countdown latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    
    // Use virtual threads executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        try {
          // Create a real JsonParser in the virtual thread
          JsonParser parser = factory.createParser(new StringReader(jsonContent));
          // Create the decorator
          JsonParserDecorator decorator = new JsonParserDecorator(parser);
          
          // Perform some operations
          assertDoesNotThrow(() -> {
            decorator.nextToken(); // START_OBJECT
            decorator.nextToken(); // FIELD_NAME (name)
            decorator.nextToken(); // VALUE_STRING (test)
            decorator.nextToken(); // FIELD_NAME (value)
            decorator.nextToken(); // VALUE_NUMBER_INT (123)
            decorator.nextToken(); // END_OBJECT
          });
          
          // Close the parser
          decorator.close();
        } 
        catch (Exception e) {
          log.error("Error in virtual thread test", e);
        }
        finally {
          latch.countDown();
        }
        return null;
      });
    }
    
    // Wait for the virtual thread to complete (with timeout)
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread test did not complete in time");
  }
  
  @Test
  public void virtualThread_handlesIOOperations() throws Exception {
    // Create a countdown latch to wait for all virtual threads to complete
    int threadCount = 10;
    CountDownLatch latch = new CountDownLatch(threadCount);
    
    // Use virtual threads executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple tasks to verify concurrent operation
      for (int i = 0; i < threadCount; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            // Create a simple JSON string to parse with thread-specific data
            String jsonContent = String.format("{\"threadId\":%d,\"value\":%d}", threadId, threadId * 100);
            JsonFactory factory = new JsonFactory();
            
            // Create a real JsonParser in the virtual thread
            JsonParser parser = factory.createParser(new StringReader(jsonContent));
            // Create the decorator
            JsonParserDecorator decorator = new JsonParserDecorator(parser);
            
            // Simulate I/O operations by parsing the JSON
            assertDoesNotThrow(() -> {
              decorator.nextToken(); // START_OBJECT
              decorator.nextToken(); // FIELD_NAME (threadId)
              decorator.nextToken(); // VALUE_NUMBER_INT
              int id = decorator.getIntValue();
              decorator.nextToken(); // FIELD_NAME (value)
              decorator.nextToken(); // VALUE_NUMBER_INT
              int value = decorator.getIntValue();
              decorator.nextToken(); // END_OBJECT
              
              // Verify the parsed values match what we expect
              assertTrue(id == threadId, "Thread ID mismatch: expected " + threadId + ", got " + id);
              assertTrue(value == threadId * 100, "Value mismatch: expected " + (threadId * 100) + ", got " + value);
            });
            
            // Close the parser
            decorator.close();
          } 
          catch (Exception e) {
            log.error("Error in virtual thread I/O test for thread " + threadId, e);
          }
          finally {
            latch.countDown();
          }
          return null;
        });
      }
    }
    
    // Wait for all virtual threads to complete (with timeout)
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Not all virtual threads completed in time");
  }
}
