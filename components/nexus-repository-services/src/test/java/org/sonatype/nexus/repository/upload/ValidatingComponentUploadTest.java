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
package org.sonatype.nexus.repository.upload;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.repository.view.PartPayload;
import org.sonatype.nexus.rest.ValidationErrorXO;
import org.sonatype.nexus.rest.ValidationErrorsException;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.upload.UploadFieldDefinition.Type.STRING;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("Java21TestGroup")
@org.junit.jupiter.api.Tag("VirtualThreadTestGroup")
public class ValidatingComponentUploadTest
    extends TestSupport
{
  @Mock
  private UploadDefinition uploadDefinition;

  @Mock
  private ComponentUpload componentUpload;

  @Mock
  private AssetUpload assetUpload;

  @Mock
  private PartPayload payload;

  @BeforeEach
  public void setup() {
    when(uploadDefinition.getComponentFields()).thenReturn(emptyList());
    when(uploadDefinition.getAssetFields()).thenReturn(emptyList());
    when(payload.getFieldName()).thenReturn("fieldname-foo");
    when(payload.getName()).thenReturn("name-bar");
  }

  @Test
  public void missingAssets() {
    expectExceptionOnValidate(componentUpload, "No assets found in upload");
  }

  @Test
  public void missingAssetField() {
    when(uploadDefinition.getComponentFields()).thenReturn(emptyList());
    when(uploadDefinition.getAssetFields()).thenReturn(
        Collections.singletonList(new UploadFieldDefinition("foo", false, STRING)));

    when(assetUpload.getPayload()).thenReturn(payload);
    when(componentUpload.getAssetUploads()).thenReturn(Collections.singletonList(assetUpload));

    expectExceptionOnValidate(componentUpload, "Missing required asset field 'Foo' on '1'");
  }

  @Test
  public void missingComponentField() {
    when(uploadDefinition.getAssetFields()).thenReturn(emptyList());
    when(uploadDefinition.getComponentFields()).thenReturn(
        Collections.singletonList(new UploadFieldDefinition("bar", false, STRING)));

    when(assetUpload.getPayload()).thenReturn(payload);
    when(componentUpload.getAssetUploads()).thenReturn(Collections.singletonList(assetUpload));

    expectExceptionOnValidate(componentUpload, "Missing required component field 'Bar'");
  }

  @Test
  public void noViolations() {
    when(uploadDefinition.getAssetFields()).thenReturn(
        Collections.singletonList(new UploadFieldDefinition("foo", false, STRING)));
    when(uploadDefinition.getComponentFields()).thenReturn(
        Collections.singletonList(new UploadFieldDefinition("bar", false, STRING)));

    when(assetUpload.getPayload()).thenReturn(payload);
    when(assetUpload.getFields()).thenReturn(singletonMap("foo", "fooValue"));
    when(assetUpload.getField("foo")).thenReturn("fooValue");

    when(componentUpload.getAssetUploads()).thenReturn(Collections.singletonList(assetUpload));
    when(componentUpload.getFields()).thenReturn(singletonMap("bar", "barValue"));
    when(componentUpload.getField("bar")).thenReturn("barValue");

    try {
      ValidatingComponentUpload validated = new ValidatingComponentUpload(uploadDefinition, componentUpload);
      validated.getComponentUpload();
    }
    catch (ValidationErrorsException e) {
      fail(format("Unexpected validation exception thrown '%s'", e));
    }
  }

  @Test
  public void duplicates() {
    when(uploadDefinition.getAssetFields()).thenReturn(Arrays.asList(new UploadFieldDefinition("field1", true, STRING),
        new UploadFieldDefinition("field2", true, STRING)));

    AssetUpload assetUploadOne = new AssetUpload();
    assetUploadOne.getFields().putAll(ImmutableMap.of("field1", "x", "field2", "y"));
    assetUploadOne.setPayload(payload);

    AssetUpload assetUploadTwo = new AssetUpload();
    assetUploadTwo.getFields().putAll(ImmutableMap.of("field1", "x", "field2", "y"));
    assetUploadTwo.setPayload(payload);

    AssetUpload assetUploadThree = new AssetUpload();
    assetUploadThree.getFields().putAll(ImmutableMap.of("field1", "x"));
    assetUploadThree.setPayload(payload);

    ComponentUpload componentUpload = new ComponentUpload();
    componentUpload.getAssetUploads().addAll(Arrays.asList(assetUploadOne, assetUploadTwo, assetUploadThree));

    expectExceptionOnValidate(componentUpload, "The assets 1 and 2 have identical coordinates");
  }

  @Test
  public void unknownField() {
    when(uploadDefinition.getAssetFields()).thenReturn(emptyList());
    when(uploadDefinition.getComponentFields()).thenReturn(emptyList());

    AssetUpload assetUpload = new AssetUpload();
    assetUpload.getFields().put("foo", "foo");
    assetUpload.setPayload(payload);

    ComponentUpload componentUpload = new ComponentUpload();
    componentUpload.getFields().put("bar", "bar");
    componentUpload.getAssetUploads().addAll(Collections.singletonList(assetUpload));

    expectExceptionOnValidate(componentUpload,
        "Unknown component field 'bar'", "Unknown field 'foo' on asset '1'");
  }

  @Test
  @org.junit.jupiter.api.Tag("VirtualThreadTestGroup")
  public void concurrentValidationWithVirtualThreads() throws Exception {
    // Configure test data
    when(uploadDefinition.getAssetFields()).thenReturn(
        Collections.singletonList(new UploadFieldDefinition("foo", false, STRING)));
    when(uploadDefinition.getComponentFields()).thenReturn(
        Collections.singletonList(new UploadFieldDefinition("bar", false, STRING)));

    when(assetUpload.getPayload()).thenReturn(payload);
    when(assetUpload.getFields()).thenReturn(singletonMap("foo", "fooValue"));
    when(assetUpload.getField("foo")).thenReturn("fooValue");

    when(componentUpload.getAssetUploads()).thenReturn(Collections.singletonList(assetUpload));
    when(componentUpload.getFields()).thenReturn(singletonMap("bar", "barValue"));
    when(componentUpload.getField("bar")).thenReturn("barValue");

    // Create virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);

    try {
      // Submit multiple concurrent validation tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            ValidatingComponentUpload validated = new ValidatingComponentUpload(uploadDefinition, componentUpload);
            validated.getComponentUpload();
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);

      // Verify results
      assertThat("No validation errors should occur under concurrent conditions", 
          errorCount.get(), is(0));
    } finally {
      executor.shutdown();
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("VirtualThreadTestGroup")
  public void concurrentValidationWithErrorsUsingVirtualThreads() throws Exception {
    // Configure test data with validation errors
    when(uploadDefinition.getAssetFields()).thenReturn(
        Collections.singletonList(new UploadFieldDefinition("foo", false, STRING)));
    when(uploadDefinition.getComponentFields()).thenReturn(
        Collections.singletonList(new UploadFieldDefinition("bar", false, STRING)));

    when(assetUpload.getPayload()).thenReturn(payload);
    // Missing required field 'foo'
    when(assetUpload.getFields()).thenReturn(Collections.emptyMap());
    when(assetUpload.getField("foo")).thenReturn(null);

    when(componentUpload.getAssetUploads()).thenReturn(Collections.singletonList(assetUpload));
    when(componentUpload.getFields()).thenReturn(singletonMap("bar", "barValue"));
    when(componentUpload.getField("bar")).thenReturn("barValue");

    // Create virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);

    try {
      // Submit multiple concurrent validation tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            ValidatingComponentUpload validated = new ValidatingComponentUpload(uploadDefinition, componentUpload);
            validated.getComponentUpload();
            fail("Expected validation exception was not thrown");
          } catch (ValidationErrorsException e) {
            // Expected exception
            List<String> messages = e.getValidationErrors().stream()
                .map(ValidationErrorXO::getMessage)
                .collect(toList());
            if (messages.contains("Missing required asset field 'Foo' on '1'")) {
              errorCount.incrementAndGet();
            }
          } catch (Exception e) {
            fail("Unexpected exception type: " + e.getClass().getName());
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);

      // Verify results - all tasks should have caught the validation error
      assertThat("All validation tasks should have detected the error", 
          errorCount.get(), is(taskCount));
    } finally {
      executor.shutdown();
    }
  }

  private void expectExceptionOnValidate(final ComponentUpload component, final String... message)
  {
    try {
      ValidatingComponentUpload validated = new ValidatingComponentUpload(uploadDefinition, component);
      validated.getComponentUpload();
      fail("Expected exception to be thrown");
    }
    catch (ValidationErrorsException exception) {
      List<String> messages = exception.getValidationErrors().stream()
          .map(ValidationErrorXO::getMessage)
          .collect(toList());
      assertThat(messages, contains(message));
      
      // Use pattern matching for switch to determine error type
      for (ValidationErrorXO error : exception.getValidationErrors()) {
        switch (error) {
          case ValidationErrorXO e when e.getMessage().contains("No assets found") -> 
              log.info("Asset count validation error detected");
          case ValidationErrorXO e when e.getMessage().contains("Missing required asset field") -> 
              log.info("Asset field validation error detected");
          case ValidationErrorXO e when e.getMessage().contains("Missing required component field") -> 
              log.info("Component field validation error detected");
          case ValidationErrorXO e when e.getMessage().contains("identical coordinates") -> 
              log.info("Duplicate asset validation error detected");
          case ValidationErrorXO e when e.getMessage().contains("Unknown") -> 
              log.info("Unknown field validation error detected");
          default -> log.info("Other validation error: {}", error.getMessage());
        }
      }
    }
  }
}