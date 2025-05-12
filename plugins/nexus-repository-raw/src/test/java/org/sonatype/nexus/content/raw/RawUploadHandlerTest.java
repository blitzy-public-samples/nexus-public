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
package org.sonatype.nexus.content.raw;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors; // Java 21 provides newVirtualThreadPerTaskExecutor
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.repository.content.fluent.FluentBlobs;
import org.sonatype.nexus.repository.importtask.ImportFileConfiguration;
import org.sonatype.nexus.repository.raw.RawUploadHandlerTestSupport;
import org.sonatype.nexus.repository.rest.UploadDefinitionExtension;
import org.sonatype.nexus.repository.security.ContentPermissionChecker;
import org.sonatype.nexus.repository.security.VariableResolverAdapter;
import org.sonatype.nexus.repository.upload.AssetUpload;
import org.sonatype.nexus.repository.upload.ComponentUpload;
import org.sonatype.nexus.repository.upload.UploadHandler;
import org.sonatype.nexus.repository.upload.UploadResponse;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.PartPayload;
import org.sonatype.nexus.repository.view.payloads.TempBlob;
import org.sonatype.nexus.repository.view.payloads.TempBlobPayload;

import org.junit.jupiter.api.BeforeEach; // JUnit Jupiter equivalent of JUnit 4's @Before
import org.junit.jupiter.api.Tag; // JUnit Jupiter's way to categorize tests
import org.junit.jupiter.api.Test; // JUnit Jupiter's @Test annotation
import org.junit.jupiter.api.extension.ExtendWith; // JUnit Jupiter's extension mechanism
import org.mockito.ArgumentCaptor; // For capturing method arguments
import org.mockito.Captor; // Mockito annotation for ArgumentCaptor
import org.mockito.Mock; // Mockito annotation for mock objects
import org.mockito.junit.jupiter.MockitoExtension; // Mockito extension for JUnit Jupiter

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull; // JUnit Jupiter assertions
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link RawUploadHandler} that verify the handling of raw content uploads.
 * <p>
 * This test class has been migrated to JUnit Jupiter and updated for Java 21 compatibility.
 * It demonstrates the use of modern testing practices including:
 * <ul>
 *   <li>JUnit Jupiter annotations and lifecycle methods</li>
 *   <li>Mockito extension for streamlined mock creation and verification</li>
 *   <li>Java 21 virtual threads for concurrent testing</li>
 *   <li>Test categorization using tags for selective execution</li>
 * </ul>
 * <p>
 * The tests in this class can be run with the following Maven profiles:
 * <ul>
 *   <li>java21-tests - for all Java 21 specific tests</li>
 *   <li>virtual-threads - for tests that specifically validate virtual thread behavior</li>
 * </ul>
 * <p>
 * When running with virtual threads, the JVM flag -Djdk.tracePinnedThreads=full is recommended
 * to detect any thread pinning issues that might occur during execution.
 */
@ExtendWith(MockitoExtension.class)
class RawUploadHandlerTest
    extends RawUploadHandlerTestSupport
{
  @Mock
  RawContentFacet rawFacet;

  @Mock
  FluentBlobs blobs;

  @Mock
  TempBlob tempBlob;
  
  @Captor
  ArgumentCaptor<String> pathCaptor;

  /**
   * Creates a new instance of the RawUploadHandler for testing.
   * <p>
   * This method implements the abstract factory method from the parent class,
   * providing a concrete implementation of the UploadHandler for raw content.
   *
   * @param contentPermissionChecker   the permission checker to use
   * @param variableResolverAdapter    the variable resolver adapter to use
   * @param uploadDefinitionExtensions the upload definition extensions to use
   * @return a new RawUploadHandler instance
   */
  @Override
  protected UploadHandler newRawUploadHandler(final ContentPermissionChecker contentPermissionChecker,
                                              final VariableResolverAdapter variableResolverAdapter,
                                              final Set<UploadDefinitionExtension> uploadDefinitionExtensions)
  {
    return new RawUploadHandler(contentPermissionChecker, variableResolverAdapter, uploadDefinitionExtensions);
  }

  /**
   * Sets up the test environment before each test method execution.
   * <p>
   * This method configures the mock objects and their behavior to create a consistent
   * test environment for all test methods.
   *
   * @throws IOException if an I/O error occurs during setup
   */
  @BeforeEach
  void setup() throws IOException {
    when(repository.facet(RawContentFacet.class)).thenReturn(rawFacet);
    when(rawFacet.blobs()).thenReturn(blobs);
  }

  /**
   * Tests the basic handling of component uploads with multiple assets.
   * <p>
   * This test verifies that:
   * <ul>
   *   <li>Multiple assets can be uploaded as part of a single component</li>
   *   <li>The correct paths are constructed for each asset</li>
   *   <li>The upload handler correctly processes all assets</li>
   * </ul>
   */
  @Test
  void testHandle() throws IOException {
    ComponentUpload component = new ComponentUpload();

    component.getFields().put("directory", "org/apache/maven");

    AssetUpload asset = new AssetUpload();
    asset.getFields().put("filename", "foo.jar");
    asset.setPayload(jarPayload);
    component.getAssetUploads().add(asset);

    asset = new AssetUpload();
    asset.getFields().put("filename", "bar.jar");
    asset.setPayload(sourcesPayload);
    component.getAssetUploads().add(asset);

    when(content.getAttributes()).thenReturn(attributesMap);
    when(rawFacet.put(any(), any())).thenReturn(content);
    UploadResponse uploadResponse = underTest.handle(repository, component);
    assertThat(uploadResponse.getAssetPaths(), contains("/org/apache/maven/foo.jar", "/org/apache/maven/bar.jar"));

    verify(rawFacet, times(2)).put(pathCaptor.capture(), any(PartPayload.class));

    List<String> paths = pathCaptor.getAllValues();

    assertThat(paths, hasSize(2));

    String path = paths.get(0);
    assertNotNull(path);
    assertThat(path, is("/org/apache/maven/foo.jar"));

    path = paths.get(1);
    assertNotNull(path);
    assertThat(path, is("/org/apache/maven/bar.jar"));
  }

  /**
   * Tests handling of file uploads with hard linking enabled.
   * <p>
   * This test verifies that:
   * <ul>
   *   <li>Files can be uploaded with hard linking enabled</li>
   *   <li>The correct blob ingestion method is called</li>
   *   <li>The upload handler correctly processes the file</li>
   * </ul>
   * <p>
   * Hard linking is an optimization that avoids copying file content when possible,
   * which can significantly improve performance for large file uploads.
   */
  @Test
  void testHandleHardLink() throws IOException {
    Path contentPath = Files.createTempDirectory("raw-upload-test").resolve("test.txt");
    String path = contentPath.toString();
    Content content = mock(Content.class);
    when(rawFacet.put(eq(path), any(TempBlobPayload.class))).thenReturn(content);

    when(blobs.ingest(eq(contentPath), any(), any(), eq(true))).thenReturn(tempBlob);

    Content importResponse = underTest.handle(new ImportFileConfiguration(repository, contentPath.toFile(), path, true));

    verify(rawFacet).put(eq(path), any(TempBlobPayload.class));
    assertThat(importResponse, is(content));
  }
  
  /**
   * Tests concurrent uploads using virtual threads to verify thread safety.
   * This test demonstrates the use of Java 21's virtual threads for high concurrency operations.
   */
  /**
   * Tests concurrent uploads using virtual threads to verify thread safety.
   * This test demonstrates the use of Java 21's virtual threads for high concurrency operations.
   * <p>
   * Virtual threads are lightweight threads that are managed by the JVM rather than the OS,
   * allowing for much higher concurrency with minimal resource overhead. This is particularly
   * useful for I/O-bound operations like file uploads.
   * <p>
   * This test runs in the java21-tests and virtual-threads Maven profiles.
   */
  @Test
  @Tag("Java21TestGroup")
  @Tag("VirtualThreadTestGroup")
  void testConcurrentUploadsWithVirtualThreads() throws Exception {
    // Configure test data
    int uploadCount = 100;
    CountDownLatch latch = new CountDownLatch(uploadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create a virtual thread executor - new in Java 21
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Set up common mocks
      when(content.getAttributes()).thenReturn(attributesMap);
      when(rawFacet.put(any(), any())).thenReturn(content);
      
      // Submit concurrent upload tasks
      for (int i = 0; i < uploadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a unique component upload for each thread
            ComponentUpload component = new ComponentUpload();
            component.getFields().put("directory", "concurrent/test");
            
            AssetUpload asset = new AssetUpload();
            asset.getFields().put("filename", "file-" + index + ".txt");
            asset.setPayload(jarPayload);
            component.getAssetUploads().add(asset);
            
            // Perform the upload
            UploadResponse response = underTest.handle(repository, component);
            if (response != null && !response.getAssetPaths().isEmpty()) {
              successCount.incrementAndGet();
            }
          } 
          catch (Exception e) {
            // Log exception but don't fail the test immediately
            System.err.println("Error in virtual thread upload: " + e.getMessage());
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all uploads to complete (with timeout)
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertThat("All concurrent uploads should complete within timeout", completed, is(true));
      assertThat("All uploads should succeed", successCount.get(), is(uploadCount));
    }
    
    // Verify the rawFacet.put was called the expected number of times
    verify(rawFacet, times(uploadCount)).put(any(), any(PartPayload.class));
  }

  /**
   * Implementation of the abstract method from the parent class to test path normalization.
   * <p>
   * This method verifies that paths are correctly normalized according to the repository's rules,
   * ensuring consistent path handling regardless of input format variations.
   *
   * @param directory    the directory path to test
   * @param file         the filename to test
   * @param expectedPath the expected normalized path
   * @throws IOException if an I/O error occurs during the test
   */
  @Override
  protected void testNormalizePath(final String directory, final String file, final String expectedPath)
      throws IOException
  {
    reset(rawFacet);
    ComponentUpload component = new ComponentUpload();

    component.getFields().put("directory", directory);

    AssetUpload asset = new AssetUpload();
    asset.getFields().put("filename", file);
    asset.setPayload(jarPayload);
    component.getAssetUploads().add(asset);

    when(content.getAttributes()).thenReturn(attributesMap);
    when(rawFacet.put(any(), any())).thenReturn(content);
    underTest.handle(repository, component);

    verify(rawFacet).put(pathCaptor.capture(), any(PartPayload.class));

    String path = pathCaptor.getValue();
    assertNotNull(path);
    assertThat(path, is(expectedPath));
  }

  /**
   * Implementation of the abstract method from the parent class to format paths.
   * <p>
   * This method ensures that all paths have a leading slash for consistency.
   *
   * @param path the path to format
   * @return the formatted path with a leading slash
   */
  @Override
  protected String path(final String path) {
    return "/" + path;
  }
}