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
package org.sonatype.nexus.repository.maven;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;
import org.sonatype.nexus.repository.upload.AssetUpload;
import org.sonatype.nexus.repository.upload.ComponentUpload;
import org.sonatype.nexus.repository.upload.UploadDefinition;
import org.sonatype.nexus.repository.upload.UploadFieldDefinition;
import org.sonatype.nexus.repository.upload.UploadFieldDefinition.Type;
import org.sonatype.nexus.repository.upload.UploadRegexMap;
import org.sonatype.nexus.repository.view.PartPayload;
import org.sonatype.nexus.rest.ValidationErrorXO;
import org.sonatype.nexus.rest.ValidationErrorsException;
import org.sonatype.nexus.testsuite.testsupport.group.VirtualThreadTestGroup;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.when;

/**
 * Tests {@link MavenValidatingComponentUpload} with Java 21 Virtual Threads to ensure
 * validation logic remains thread-safe and performs correctly under high concurrency.
 */
@ExtendWith(MockitoExtension.class)
@Category(VirtualThreadTestGroup.class)
public class MavenValidatingComponentUploadVirtualThreadTest
    extends TestSupport
{
  private static final String MAVEN_CLASSIFIER_AND_EXTENSION_EXTRACTOR_REGEX = "-(?:(?:\\.?\\d)+)(?:-(?:SNAPSHOT|\\d+))?(?:-(\\w+))?\\.((?:\\.?\\w)+)$";

  private static final String GROUP_NAME_COORDINATES = "Component coordinates";

  @Mock
  private UploadDefinition uploadDefinition;

  @Mock
  private PartPayload jarPayload;

  private ComponentUpload componentUpload;

  @BeforeEach
  public void setup() {
    when(uploadDefinition.getComponentFields()).thenReturn(asList(
        new UploadFieldDefinition("groupId", "Group ID", null, false, Type.STRING, GROUP_NAME_COORDINATES),
        new UploadFieldDefinition("artifactId", "Artifact ID", null, false, Type.STRING, GROUP_NAME_COORDINATES),
        new UploadFieldDefinition("version", false, Type.STRING, GROUP_NAME_COORDINATES),
        new UploadFieldDefinition("generate-pom", "Generate a POM", null, true, Type.BOOLEAN, GROUP_NAME_COORDINATES),
        new UploadFieldDefinition("packaging", true, Type.STRING, GROUP_NAME_COORDINATES)));
    when(uploadDefinition.getAssetFields()).thenReturn(asList(
        new UploadFieldDefinition("classifier", true, Type.STRING),
        new UploadFieldDefinition("extension", false, Type.STRING)));
    when(uploadDefinition.getFormat()).thenReturn(Maven2Format.NAME);
    when(uploadDefinition.getRegexMap()).thenReturn(new UploadRegexMap(
        MAVEN_CLASSIFIER_AND_EXTENSION_EXTRACTOR_REGEX, "classifier", "extension"));
    when(jarPayload.getFieldName()).thenReturn("foo.jar");
    when(jarPayload.getName()).thenReturn("asset");

    componentUpload = new ComponentUpload();
  }

  @Test
  void validateMissingFieldWithVirtualThreads() throws Exception {
    // Create a component upload with missing required fields
    AssetUpload assetUpload = new AssetUpload();
    assetUpload.getFields().put("extension", "jar");
    assetUpload.setPayload(jarPayload);
    componentUpload.getAssetUploads().add(assetUpload);

    // Execute validation concurrently with virtual threads
    int threadCount = 50;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    List<String> errors = Collections.synchronizedList(new java.util.ArrayList<>());

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            expectExceptionOnValidate(componentUpload,
                "Missing required component field 'Group ID'",
                "Missing required component field 'Artifact ID'",
                "Missing required component field 'Version'");
            successCount.incrementAndGet();
          } catch (AssertionError e) {
            errors.add(e.getMessage());
          } finally {
            latch.countDown();
          }
        });
      }

      latch.await(30, TimeUnit.SECONDS);
    }

    // Verify all validations were successful
    if (!errors.isEmpty()) {
      fail("Validation errors occurred during concurrent execution: " + errors);
    }
    assertEquals(threadCount, successCount.get(), "All validation operations should succeed");
  }

  @Test
  void validateMissingAssetFieldWithVirtualThreads() throws Exception {
    // Create a component upload with missing asset field
    AssetUpload assetUpload = new AssetUpload();
    assetUpload.setPayload(jarPayload);
    componentUpload.getAssetUploads().add(assetUpload);

    componentUpload.getFields().put("groupId", "org.apache.maven");
    componentUpload.getFields().put("artifactId", "tomcat");
    componentUpload.getFields().put("version", "5.0.28");

    // Execute validation concurrently with virtual threads
    int threadCount = 50;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    List<String> errors = Collections.synchronizedList(new java.util.ArrayList<>());

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            expectExceptionOnValidate(componentUpload, "Missing required asset field 'Extension' on '1'");
            successCount.incrementAndGet();
          } catch (AssertionError e) {
            errors.add(e.getMessage());
          } finally {
            latch.countDown();
          }
        });
      }

      latch.await(30, TimeUnit.SECONDS);
    }

    // Verify all validations were successful
    if (!errors.isEmpty()) {
      fail("Validation errors occurred during concurrent execution: " + errors);
    }
    assertEquals(threadCount, successCount.get(), "All validation operations should succeed");
  }

  @Test
  void validateUnknownFieldWithVirtualThreads() throws Exception {
    // Create a component upload with unknown fields
    AssetUpload assetUpload = new AssetUpload();
    assetUpload.setPayload(jarPayload);
    assetUpload.getFields().put("extension", "jar");
    assetUpload.getFields().put("bar", "bar");
    componentUpload.getAssetUploads().add(assetUpload);

    componentUpload.getFields().put("groupId", "org.apache.maven");
    componentUpload.getFields().put("artifactId", "tomcat");
    componentUpload.getFields().put("version", "5.0.28");
    componentUpload.getFields().put("foo", "foo");

    // Execute validation concurrently with virtual threads
    int threadCount = 50;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    List<String> errors = Collections.synchronizedList(new java.util.ArrayList<>());

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            expectExceptionOnValidate(componentUpload,
                "Unknown component field 'foo'", "Unknown field 'bar' on asset '1'");
            successCount.incrementAndGet();
          } catch (AssertionError e) {
            errors.add(e.getMessage());
          } finally {
            latch.countDown();
          }
        });
      }

      latch.await(30, TimeUnit.SECONDS);
    }

    // Verify all validations were successful
    if (!errors.isEmpty()) {
      fail("Validation errors occurred during concurrent execution: " + errors);
    }
    assertEquals(threadCount, successCount.get(), "All validation operations should succeed");
  }

  @Test
  void validateAllowMissingComponentFieldsWhenPomAssetIsPresentWithVirtualThreads() throws Exception {
    // Create a component upload with POM asset
    AssetUpload assetUpload = new AssetUpload();
    assetUpload.setPayload(jarPayload);
    assetUpload.setFields(Collections.singletonMap("extension", "pom"));
    componentUpload.getAssetUploads().add(assetUpload);

    // Execute validation concurrently with virtual threads
    int threadCount = 50;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    List<String> errors = Collections.synchronizedList(new java.util.ArrayList<>());

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            MavenValidatingComponentUpload validated = new MavenValidatingComponentUpload(uploadDefinition, componentUpload);
            assertThat(validated.getComponentUpload(), notNullValue());
            successCount.incrementAndGet();
          } catch (Exception e) {
            errors.add(e.getMessage());
          } finally {
            latch.countDown();
          }
        });
      }

      latch.await(30, TimeUnit.SECONDS);
    }

    // Verify all validations were successful
    if (!errors.isEmpty()) {
      fail("Validation errors occurred during concurrent execution: " + errors);
    }
    assertEquals(threadCount, successCount.get(), "All validation operations should succeed");
  }

  @Test
  void validateDuplicatesWithVirtualThreads() throws Exception {
    // Create a component upload with duplicate assets
    AssetUpload assetUploadOne = new AssetUpload();
    assetUploadOne.getFields().putAll(ImmutableMap.of("extension", "x", "classifier", "y"));
    assetUploadOne.setPayload(jarPayload);

    AssetUpload assetUploadTwo = new AssetUpload();
    assetUploadTwo.getFields().putAll(ImmutableMap.of("extension", "x", "classifier", "y"));
    assetUploadTwo.setPayload(jarPayload);

    AssetUpload assetUploadThree = new AssetUpload();
    assetUploadThree.getFields().putAll(ImmutableMap.of("extension", "x"));
    assetUploadThree.setPayload(jarPayload);

    componentUpload.getFields().putAll(ImmutableMap.of("groupId", "g", "artifactId", "a", "version", "1"));
    componentUpload.getAssetUploads().addAll(asList(assetUploadOne, assetUploadTwo, assetUploadThree));

    // Execute validation concurrently with virtual threads
    int threadCount = 50;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    List<String> errors = Collections.synchronizedList(new java.util.ArrayList<>());

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            expectExceptionOnValidate(componentUpload, "The assets 1 and 2 have identical coordinates");
            successCount.incrementAndGet();
          } catch (AssertionError e) {
            errors.add(e.getMessage());
          } finally {
            latch.countDown();
          }
        });
      }

      latch.await(30, TimeUnit.SECONDS);
    }

    // Verify all validations were successful
    if (!errors.isEmpty()) {
      fail("Validation errors occurred during concurrent execution: " + errors);
    }
    assertEquals(threadCount, successCount.get(), "All validation operations should succeed");
  }

  @Test
  void validateHighConcurrencyWithVirtualThreads() throws Exception {
    // Create a component upload with missing required fields
    AssetUpload assetUpload = new AssetUpload();
    assetUpload.getFields().put("extension", "jar");
    assetUpload.setPayload(jarPayload);
    componentUpload.getAssetUploads().add(assetUpload);

    // Execute validation concurrently with a high number of virtual threads
    int threadCount = 1000; // Test with 1000 concurrent validations
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    List<String> errors = Collections.synchronizedList(new java.util.ArrayList<>());

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            expectExceptionOnValidate(componentUpload,
                "Missing required component field 'Group ID'",
                "Missing required component field 'Artifact ID'",
                "Missing required component field 'Version'");
            successCount.incrementAndGet();
          } catch (AssertionError e) {
            errors.add(e.getMessage());
          } finally {
            latch.countDown();
          }
        });
      }

      latch.await(60, TimeUnit.SECONDS);
    }

    // Verify all validations were successful
    if (!errors.isEmpty()) {
      fail("Validation errors occurred during high concurrency execution: " + errors);
    }
    assertEquals(threadCount, successCount.get(), "All validation operations should succeed under high concurrency");
  }

  private void expectExceptionOnValidate(final ComponentUpload component, final String... message) {
    try {
      MavenValidatingComponentUpload validated = new MavenValidatingComponentUpload(uploadDefinition, component);
      validated.getComponentUpload();
      fail("Expected exception to be thrown");
    }
    catch (ValidationErrorsException exception) {
      List<String> messages = exception.getValidationErrors().stream()
          .map(ValidationErrorXO::getMessage)
          .collect(toList());
      assertThat(messages, contains(message));
    }
  }
}