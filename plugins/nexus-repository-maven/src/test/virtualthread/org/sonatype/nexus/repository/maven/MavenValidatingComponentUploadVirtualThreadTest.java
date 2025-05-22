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
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
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

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.when;

/**
 * Tests {@link MavenValidatingComponentUpload} with Java 21 Virtual Threads to ensure
 * validation logic remains thread-safe under high concurrency.
 */
@ExtendWith(MockitoExtension.class)
@org.junit.Category(VirtualThreadTestGroup.class)
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

  /**
   * Tests validation of missing required fields with concurrent virtual threads.
   */
  @Test
  void testValidateMissingFieldConcurrently() throws Exception {
    AssetUpload assetUpload = new AssetUpload();
    assetUpload.getFields().put("extension", "jar");
    assetUpload.setPayload(jarPayload);
    componentUpload.getAssetUploads().add(assetUpload);

    int threadCount = 100;
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);

    try {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            expectExceptionOnValidate(componentUpload,
                "Missing required component field 'Group ID'",
                "Missing required component field 'Artifact ID'",
                "Missing required component field 'Version'");
            successCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }

      latch.await(30, TimeUnit.SECONDS);
      assertEquals(threadCount, successCount.get(), "All validation tasks should complete successfully");
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests validation of missing asset fields with concurrent virtual threads.
   */
  @Test
  void testValidateMissingAssetFieldConcurrently() throws Exception {
    AssetUpload assetUpload = new AssetUpload();
    assetUpload.setPayload(jarPayload);
    componentUpload.getAssetUploads().add(assetUpload);

    componentUpload.getFields().put("groupId", "org.apache.maven");
    componentUpload.getFields().put("artifactId", "tomcat");
    componentUpload.getFields().put("version", "5.0.28");

    int threadCount = 100;
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);

    try {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            expectExceptionOnValidate(componentUpload, "Missing required asset field 'Extension' on '1'");
            successCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }

      latch.await(30, TimeUnit.SECONDS);
      assertEquals(threadCount, successCount.get(), "All validation tasks should complete successfully");
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests validation of unknown fields with concurrent virtual threads.
   */
  @Test
  void testValidateUnknownFieldConcurrently() throws Exception {
    AssetUpload assetUpload = new AssetUpload();
    assetUpload.setPayload(jarPayload);
    assetUpload.getFields().put("extension", "jar");
    assetUpload.getFields().put("bar", "bar");
    componentUpload.getAssetUploads().add(assetUpload);

    componentUpload.getFields().put("groupId", "org.apache.maven");
    componentUpload.getFields().put("artifactId", "tomcat");
    componentUpload.getFields().put("version", "5.0.28");
    componentUpload.getFields().put("foo", "foo");

    int threadCount = 100;
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);

    try {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            expectExceptionOnValidate(componentUpload,
                "Unknown component field 'foo'", "Unknown field 'bar' on asset '1'");
            successCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }

      latch.await(30, TimeUnit.SECONDS);
      assertEquals(threadCount, successCount.get(), "All validation tasks should complete successfully");
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests validation of POM assets with concurrent virtual threads.
   */
  @Test
  void testValidateAllowMissingComponentFieldsWhenPomAssetIsPresentConcurrently() throws Exception {
    AssetUpload assetUpload = new AssetUpload();
    assetUpload.setPayload(jarPayload);
    assetUpload.setFields(Collections.singletonMap("extension", "pom"));
    componentUpload.getAssetUploads().add(assetUpload);

    int threadCount = 100;
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);

    try {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            MavenValidatingComponentUpload validated = new MavenValidatingComponentUpload(uploadDefinition, componentUpload);
            assertThat(validated.getComponentUpload(), notNullValue());
            successCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }

      latch.await(30, TimeUnit.SECONDS);
      assertEquals(threadCount, successCount.get(), "All validation tasks should complete successfully");
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests validation of duplicate assets with concurrent virtual threads.
   */
  @Test
  void testValidateDuplicatesConcurrently() throws Exception {
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

    int threadCount = 100;
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);

    try {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            expectExceptionOnValidate(componentUpload, "The assets 1 and 2 have identical coordinates");
            successCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }

      latch.await(30, TimeUnit.SECONDS);
      assertEquals(threadCount, successCount.get(), "All validation tasks should complete successfully");
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests high concurrency validation with many virtual threads.
   */
  @Test
  void testHighConcurrencyValidation() throws Exception {
    // Create a mix of valid and invalid component uploads
    List<ComponentUpload> uploads = createMixedComponentUploads(1000);
    
    int threadCount = 1000;
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);

    try {
      for (int i = 0; i < threadCount; i++) {
        final int index = i % uploads.size();
        executor.submit(() -> {
          try {
            ComponentUpload upload = uploads.get(index);
            try {
              MavenValidatingComponentUpload validated = new MavenValidatingComponentUpload(uploadDefinition, upload);
              validated.getComponentUpload();
              // If we get here, it should be a valid upload
              if (index % 4 == 0) { // Only the first type is valid
                successCount.incrementAndGet();
              } else {
                fail("Expected exception for invalid upload");
              }
            } catch (ValidationErrorsException e) {
              // Expected for invalid uploads
              if (index % 4 != 0) { // All other types should fail
                successCount.incrementAndGet();
              } else {
                fail("Unexpected exception for valid upload");
              }
            }
          } finally {
            latch.countDown();
          }
        });
      }

      latch.await(60, TimeUnit.SECONDS);
      assertEquals(threadCount, successCount.get(), "All validation tasks should complete successfully");
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Creates a mix of valid and invalid component uploads for testing.
   */
  private List<ComponentUpload> createMixedComponentUploads(int count) {
    List<ComponentUpload> uploads = new java.util.ArrayList<>(count);
    
    for (int i = 0; i < count; i++) {
      ComponentUpload upload = new ComponentUpload();
      int type = i % 4;
      
      switch (type) {
        case 0: // Valid POM upload
          AssetUpload pomAsset = new AssetUpload();
          pomAsset.setPayload(jarPayload);
          pomAsset.setFields(Collections.singletonMap("extension", "pom"));
          upload.getAssetUploads().add(pomAsset);
          break;
          
        case 1: // Missing required fields
          AssetUpload assetWithExt = new AssetUpload();
          assetWithExt.getFields().put("extension", "jar");
          assetWithExt.setPayload(jarPayload);
          upload.getAssetUploads().add(assetWithExt);
          break;
          
        case 2: // Missing asset field
          upload.getFields().put("groupId", "org.apache.maven");
          upload.getFields().put("artifactId", "tomcat");
          upload.getFields().put("version", "5.0.28");
          AssetUpload assetNoExt = new AssetUpload();
          assetNoExt.setPayload(jarPayload);
          upload.getAssetUploads().add(assetNoExt);
          break;
          
        case 3: // Duplicate assets
          upload.getFields().putAll(ImmutableMap.of("groupId", "g", "artifactId", "a", "version", "1"));
          AssetUpload asset1 = new AssetUpload();
          asset1.getFields().putAll(ImmutableMap.of("extension", "x", "classifier", "y"));
          asset1.setPayload(jarPayload);
          AssetUpload asset2 = new AssetUpload();
          asset2.getFields().putAll(ImmutableMap.of("extension", "x", "classifier", "y"));
          asset2.setPayload(jarPayload);
          upload.getAssetUploads().addAll(asList(asset1, asset2));
          break;
      }
      
      uploads.add(upload);
    }
    
    return uploads;
  }

  /**
   * Helper method to verify that validation throws the expected exception with the expected messages.
   */
  private void expectExceptionOnValidate(final ComponentUpload component, final String... message) {
    ValidationErrorsException exception = assertThrows(ValidationErrorsException.class, () -> {
      MavenValidatingComponentUpload validated = new MavenValidatingComponentUpload(uploadDefinition, component);
      validated.getComponentUpload();
    }, "Expected exception to be thrown");
    
    List<String> messages = exception.getValidationErrors().stream()
        .map(ValidationErrorXO::getMessage)
        .collect(toList());
    assertThat(messages, contains(message));
  }
}