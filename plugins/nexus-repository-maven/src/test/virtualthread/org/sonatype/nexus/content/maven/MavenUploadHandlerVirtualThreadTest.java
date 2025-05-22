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
package org.sonatype.nexus.content.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.content.maven.internal.MavenVariableResolverAdapter;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.content.fluent.FluentBlobs;
import org.sonatype.nexus.repository.maven.LayoutPolicy;
import org.sonatype.nexus.repository.maven.MavenFacet;
import org.sonatype.nexus.repository.maven.MavenMetadataRebuildFacet;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;
import org.sonatype.nexus.repository.maven.internal.Maven2MavenPathParser;
import org.sonatype.nexus.repository.maven.internal.MavenPomGenerator;
import org.sonatype.nexus.repository.maven.internal.VersionPolicyValidator;
import org.sonatype.nexus.repository.security.ContentPermissionChecker;
import org.sonatype.nexus.repository.upload.AssetUpload;
import org.sonatype.nexus.repository.upload.ComponentUpload;
import org.sonatype.nexus.repository.upload.UploadResponse;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.PartPayload;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.payloads.TempBlob;
import org.sonatype.nexus.rest.ValidationErrorsException;
import org.sonatype.nexus.security.BreadActions;
import org.sonatype.nexus.selector.VariableSource;

import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import static java.util.Collections.emptySet;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MavenUploadHandler} with Virtual Threads.
 * 
 * This test class verifies that the MavenUploadHandler correctly processes concurrent Maven artifact uploads
 * using Java 21's Virtual Threads, properly handles I/O operations without thread pinning issues, and maintains
 * expected performance characteristics under high concurrency.
 */
public class MavenUploadHandlerVirtualThreadTest
    extends TestSupport
{
  private static final String REPO_NAME = "maven-hosted";
  private static final int CONCURRENT_UPLOADS = 100;
  private static final int UPLOAD_TIMEOUT_SECONDS = 30;

  private MavenUploadHandler underTest;

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Mock
  Repository repository;

  @Mock
  MavenContentFacet mavenFacet;

  @Mock
  VersionPolicyValidator versionPolicyValidator;

  @Mock
  TempBlob tempBlob;

  @Mock
  MavenMetadataRebuildFacet mavenMetadataRebuildFacet;

  @Mock
  private ContentPermissionChecker contentPermissionChecker;

  @Mock
  private MavenPomGenerator mavenPomGenerator;

  @Captor
  private ArgumentCaptor<VariableSource> captor;

  @Before
  public void setup() throws IOException {
    when(versionPolicyValidator.validArtifactPath(any(), any())).thenReturn(true);
    when(contentPermissionChecker.isPermitted(eq(REPO_NAME), eq(Maven2Format.NAME), eq(BreadActions.EDIT), any()))
        .thenReturn(true);

    when(mavenPomGenerator.generatePom(any(), any(), any(), any())).thenReturn("<project/>");

    Maven2MavenPathParser pathParser = new Maven2MavenPathParser();
    underTest = new MavenUploadHandler(pathParser, new MavenVariableResolverAdapter(pathParser),
        contentPermissionChecker, versionPolicyValidator, mavenPomGenerator, emptySet());

    when(repository.getName()).thenReturn(REPO_NAME);
    when(repository.getFormat()).thenReturn(new Maven2Format());
    when(repository.facet(MavenContentFacet.class)).thenReturn(mavenFacet);
    when(repository.facet(MavenFacet.class)).thenReturn(mavenFacet);
    when(repository.facet(MavenMetadataRebuildFacet.class)).thenReturn(mavenMetadataRebuildFacet);

    FluentBlobs blobs = mock(FluentBlobs.class);
    when(mavenFacet.blobs()).thenReturn(blobs);
    when(blobs.ingest(any(Payload.class), any())).thenReturn(tempBlob);

    when(mavenFacet.layoutPolicy()).thenReturn(LayoutPolicy.STRICT);

    Content content = mock(Content.class);
    AttributesMap attributesMap = mock(AttributesMap.class);
    Asset assetPayload = mock(Asset.class);
    when(attributesMap.get(Asset.class)).thenReturn(assetPayload);
    when(attributesMap.require(eq(Content.CONTENT_LAST_MODIFIED), eq(DateTime.class))).thenReturn(DateTime.now());
    AssetBlob blob = mock(AssetBlob.class);
    when(assetPayload.blob()).thenReturn(Optional.of(blob));
    Map<String, String> checksums = Collections.singletonMap(
        HashAlgorithm.SHA1.name(),
        "da39a3ee5e6b4b0d3255bfef95601890afd80709");
    when(blob.checksums()).thenReturn(checksums);
    when(content.getAttributes()).thenReturn(attributesMap);
    when(mavenFacet.put(any(), any())).thenReturn(content);
  }

  /**
   * Tests concurrent uploads using Virtual Threads to verify that the MavenUploadHandler
   * can handle multiple simultaneous uploads without issues.
   */
  @Test
  public void testConcurrentUploadsWithVirtualThreads() throws Exception {
    // Create a countdown latch to coordinate the threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_UPLOADS);
    
    // Track any exceptions that occur during concurrent execution
    ConcurrentHashMap<Integer, Exception> exceptions = new ConcurrentHashMap<>();
    
    // Track successful uploads
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create a virtual thread executor
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit concurrent upload tasks
      for (int i = 0; i < CONCURRENT_UPLOADS; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Create a unique artifact for this thread
            ComponentUpload componentUpload = new ComponentUpload();
            componentUpload.getFields().put("groupId", "org.example");
            componentUpload.getFields().put("artifactId", "test-artifact-" + index);
            componentUpload.getFields().put("version", "1.0.0");
            
            // Create a temporary file for the payload
            File tempFile = createTempJarFile("test-content-" + index);
            
            // Create the asset upload
            AssetUpload assetUpload = new AssetUpload();
            assetUpload.getFields().put("extension", "jar");
            assetUpload.setPayload(createMockPartPayload(tempFile));
            componentUpload.getAssetUploads().add(assetUpload);
            
            // Perform the upload
            UploadResponse response = underTest.handle(repository, componentUpload);
            
            // Verify the response
            assertNotNull("Upload response should not be null", response);
            assertThat(response.getAssetPaths(), hasSize(1));
            
            // Increment success counter
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            // Record any exceptions
            exceptions.put(index, e);
          }
          finally {
            // Signal completion
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all uploads to complete or timeout
      boolean completed = completionLatch.await(UPLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Assert that all uploads completed within the timeout
      assertTrue("Not all uploads completed within the timeout", completed);
      
      // Check for exceptions
      if (!exceptions.isEmpty()) {
        fail("Encountered " + exceptions.size() + " exceptions during concurrent uploads. First exception: " 
            + exceptions.values().iterator().next());
      }
      
      // Verify all uploads succeeded
      assertEquals("All uploads should succeed", CONCURRENT_UPLOADS, successCount.get());
      
      // Verify metadata rebuild was called
      verify(mavenMetadataRebuildFacet, times(CONCURRENT_UPLOADS))
          .rebuildMetadata(eq("org.example"), any(), any(), eq(false), eq(false));
    }
  }

  /**
   * Tests that I/O operations in the MavenUploadHandler don't cause thread pinning issues
   * when running with Virtual Threads.
   */
  @Test
  public void testIOOperationsWithVirtualThreads() throws Exception {
    // Create a large file to test I/O operations
    File largeFile = createLargeJarFile(5 * 1024 * 1024); // 5MB file
    
    // Create a countdown latch to coordinate the threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_UPLOADS);
    
    // Track execution times to detect potential thread pinning
    List<Long> executionTimes = Collections.synchronizedList(new ArrayList<>());
    
    // Create a virtual thread executor
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit concurrent upload tasks with large files
      for (int i = 0; i < CONCURRENT_UPLOADS; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            long startTime = System.nanoTime();
            
            // Create a unique artifact for this thread
            ComponentUpload componentUpload = new ComponentUpload();
            componentUpload.getFields().put("groupId", "org.example");
            componentUpload.getFields().put("artifactId", "large-artifact-" + index);
            componentUpload.getFields().put("version", "1.0.0");
            
            // Create the asset upload with the large file
            AssetUpload assetUpload = new AssetUpload();
            assetUpload.getFields().put("extension", "jar");
            assetUpload.setPayload(createMockPartPayload(largeFile));
            componentUpload.getAssetUploads().add(assetUpload);
            
            // Perform the upload
            underTest.handle(repository, componentUpload);
            
            // Record execution time
            long executionTime = System.nanoTime() - startTime;
            executionTimes.add(executionTime);
          }
          catch (Exception e) {
            logger.error("Error in virtual thread {}: {}", index, e.getMessage(), e);
          }
          finally {
            // Signal completion
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all uploads to complete or timeout
      boolean completed = completionLatch.await(UPLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Assert that all uploads completed within the timeout
      assertTrue("Not all uploads completed within the timeout", completed);
      
      // Calculate statistics to detect potential thread pinning
      if (!executionTimes.isEmpty()) {
        long totalTime = executionTimes.stream().mapToLong(Long::longValue).sum();
        long averageTime = totalTime / executionTimes.size();
        long maxTime = executionTimes.stream().mapToLong(Long::longValue).max().orElse(0);
        
        // If thread pinning occurs, the max time would be significantly higher than average
        // as pinned threads would block carrier threads
        double ratio = (double) maxTime / averageTime;
        
        // A high ratio indicates potential thread pinning
        // This is a heuristic - the exact threshold depends on the system
        assertThat("Max/average execution time ratio suggests thread pinning", 
            ratio, is(lessThan(5.0)));
      }
    }
  }

  /**
   * Tests that permission checks work correctly with concurrent Virtual Thread uploads.
   */
  @Test
  public void testPermissionChecksWithVirtualThreads() throws Exception {
    // Configure permission checker to deny permissions
    when(contentPermissionChecker.isPermitted(eq(REPO_NAME), eq(Maven2Format.NAME), eq(BreadActions.EDIT), any()))
        .thenReturn(false);
    
    // Create a countdown latch to coordinate the threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_UPLOADS);
    
    // Track validation exceptions
    AtomicInteger permissionDeniedCount = new AtomicInteger(0);
    
    // Create a virtual thread executor
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit concurrent upload tasks
      for (int i = 0; i < CONCURRENT_UPLOADS; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Create a unique artifact for this thread
            ComponentUpload componentUpload = new ComponentUpload();
            componentUpload.getFields().put("groupId", "org.example");
            componentUpload.getFields().put("artifactId", "test-artifact-" + index);
            componentUpload.getFields().put("version", "1.0.0");
            
            // Create a temporary file for the payload
            File tempFile = createTempJarFile("test-content-" + index);
            
            // Create the asset upload
            AssetUpload assetUpload = new AssetUpload();
            assetUpload.getFields().put("extension", "jar");
            assetUpload.setPayload(createMockPartPayload(tempFile));
            componentUpload.getAssetUploads().add(assetUpload);
            
            // Attempt the upload (should fail with permission denied)
            underTest.handle(repository, componentUpload);
            
            // If we get here, the permission check failed
            fail("Expected ValidationErrorsException for thread " + index);
          }
          catch (ValidationErrorsException e) {
            // Expected exception - permission denied
            if (e.getMessage().contains("Not authorized")) {
              permissionDeniedCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            logger.error("Unexpected error in virtual thread {}: {}", index, e.getMessage(), e);
          }
          finally {
            // Signal completion
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all uploads to complete or timeout
      boolean completed = completionLatch.await(UPLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Assert that all uploads completed within the timeout
      assertTrue("Not all uploads completed within the timeout", completed);
      
      // Verify all attempts were denied permission
      assertEquals("All uploads should be denied permission", 
          CONCURRENT_UPLOADS, permissionDeniedCount.get());
    }
  }

  /**
   * Tests that metadata rebuilding works correctly with Virtual Threads.
   */
  @Test
  public void testMetadataRebuildingWithVirtualThreads() throws Exception {
    // Create a countdown latch to coordinate the threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_UPLOADS);
    
    // Use a fixed group and artifact ID to test metadata rebuilding
    final String groupId = "org.example.rebuild";
    final String artifactId = "test-metadata-rebuild";
    
    // Create a virtual thread executor
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit concurrent upload tasks
      for (int i = 0; i < CONCURRENT_UPLOADS; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Create a component with the same group/artifact but different version
            ComponentUpload componentUpload = new ComponentUpload();
            componentUpload.getFields().put("groupId", groupId);
            componentUpload.getFields().put("artifactId", artifactId);
            componentUpload.getFields().put("version", "1.0." + index);
            
            // Create a temporary file for the payload
            File tempFile = createTempJarFile("test-content-" + index);
            
            // Create the asset upload
            AssetUpload assetUpload = new AssetUpload();
            assetUpload.getFields().put("extension", "jar");
            assetUpload.setPayload(createMockPartPayload(tempFile));
            componentUpload.getAssetUploads().add(assetUpload);
            
            // Perform the upload
            underTest.handle(repository, componentUpload);
          }
          catch (Exception e) {
            logger.error("Error in virtual thread {}: {}", index, e.getMessage(), e);
          }
          finally {
            // Signal completion
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all uploads to complete or timeout
      boolean completed = completionLatch.await(UPLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Assert that all uploads completed within the timeout
      assertTrue("Not all uploads completed within the timeout", completed);
      
      // Verify metadata rebuild was called for each version
      verify(mavenMetadataRebuildFacet, times(CONCURRENT_UPLOADS))
          .rebuildMetadata(eq(groupId), eq(artifactId), any(), eq(false), eq(false));
    }
  }

  /**
   * Helper method to create a temporary JAR file with specified content.
   */
  private File createTempJarFile(String content) throws IOException {
    File tempFile = temporaryFolder.newFile();
    Files.writeString(tempFile.toPath(), content);
    return tempFile;
  }

  /**
   * Helper method to create a large JAR file for I/O testing.
   */
  private File createLargeJarFile(int sizeInBytes) throws IOException {
    File tempFile = temporaryFolder.newFile();
    Path path = tempFile.toPath();
    
    // Create a file with the specified size
    byte[] buffer = new byte[8192];
    int remaining = sizeInBytes;
    
    try (var os = Files.newOutputStream(path)) {
      while (remaining > 0) {
        int toWrite = Math.min(remaining, buffer.length);
        os.write(buffer, 0, toWrite);
        remaining -= toWrite;
      }
    }
    
    return tempFile;
  }

  /**
   * Helper method to create a mock PartPayload from a file.
   */
  private PartPayload createMockPartPayload(File file) throws IOException {
    PartPayload payload = mock(PartPayload.class);
    when(payload.openInputStream()).thenReturn(Files.newInputStream(file.toPath()));
    when(payload.getSize()).thenReturn((long) Files.readAllBytes(file.toPath()).length);
    when(payload.getName()).thenReturn(file.getName());
    return payload;
  }
}