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
package org.sonatype.nexus.repository.upload.internal;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.repository.types.VirtualType;
import org.sonatype.nexus.repository.upload.ComponentUpload;
import org.sonatype.nexus.repository.upload.UploadManager.UIUploadEvent;
import org.sonatype.nexus.repository.upload.UploadProcessor;
import org.sonatype.nexus.repository.upload.UploadDefinition;
import org.sonatype.nexus.repository.upload.UploadHandler;
import org.sonatype.nexus.repository.upload.UploadResponse;
import org.sonatype.nexus.repository.upload.ValidatingComponentUpload;
import org.sonatype.nexus.repository.upload.internal.BlobStoreMultipartForm.TempBlobFormField;
import org.sonatype.nexus.repository.view.payloads.TempBlob;
import org.sonatype.nexus.rest.ValidationErrorXO;
import org.sonatype.nexus.rest.ValidationErrorsException;

import org.apache.commons.fileupload.FileUploadException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.google.common.collect.Lists;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.isNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class UploadManagerImplTest
    extends TestSupport
{
  private UploadManagerImpl underTest;

  @Mock
  UploadHandler handlerA;

  @Mock
  UploadDefinition uploadA;

  @Mock
  UploadHandler handlerB;

  @Mock
  UploadDefinition uploadB;

  @Mock
  Configuration configuration;

  @Mock
  Repository repository;

  @Mock
  UploadComponentMultipartHelper blobStoreAwareMultipartHelper;

  @Mock
  HttpServletRequest request;

  @Mock
  ValidatingComponentUpload validatingComponentUpload;

  @Mock
  UploadProcessor uploadComponentProcessor;

  @Mock
  EventManager eventManager;

  @Captor
  ArgumentCaptor<ComponentUpload> componentUploadCaptor;

  @BeforeEach
  public void setup() {
    when(handlerA.supportsApiUpload()).thenReturn(true);
    when(handlerB.supportsApiUpload()).thenReturn(true);
    when(handlerA.getDefinition()).thenReturn(uploadA);
    when(handlerB.getDefinition()).thenReturn(uploadB);
    when(handlerA.getValidatingComponentUpload(componentUploadCaptor.capture())).thenReturn(validatingComponentUpload);
    when(handlerB.getValidatingComponentUpload(componentUploadCaptor.capture())).thenReturn(validatingComponentUpload);
    when(validatingComponentUpload.getComponentUpload()).thenAnswer(i -> componentUploadCaptor.getValue());

    when(repository.getFormat()).thenReturn(new Format("a")
    {
    });
    when(repository.getType()).thenReturn(new HostedType());
    when(repository.getConfiguration()).thenReturn(configuration);
    when(configuration.isOnline()).thenReturn(true);

    Map<String, UploadHandler> handlers = new HashMap<>();
    handlers.put("a", handlerA);
    handlers.put("b", handlerB);

    underTest = new UploadManagerImpl(handlers, blobStoreAwareMultipartHelper, uploadComponentProcessor, eventManager,
        Collections.emptySet());
  }

  @Test
  void testGetAvailable() {
    List<UploadDefinition> definitions = underTest.getAvailableDefinitions();
    assertEquals(2, definitions.size());
    assertTrue(definitions.contains(uploadA));
    assertTrue(definitions.contains(uploadB));
  }

  @Test
  void testGetByFormat() {
    assertEquals(uploadA, underTest.getByFormat("a"));
    assertEquals(uploadB, underTest.getByFormat("b"));
  }

  @Test
  void testHandle() throws IOException, FileUploadException {
    BlobStoreMultipartForm uploadedForm = new BlobStoreMultipartForm();
    TempBlobFormField field = new TempBlobFormField("asset1", "foo.jar", mock(TempBlob.class));
    uploadedForm.putFile("asset1", field);
    when(blobStoreAwareMultipartHelper.parse(isNotNull(), isNotNull())).thenReturn(uploadedForm);

    List<String> assetPaths = Lists.newArrayList("/asset/path/1", "/asset/path/2");
    UploadResponse uploadResponse = mock(UploadResponse.class);
    when(uploadResponse.getAssetPaths()).thenReturn(assetPaths);
    when(handlerA.handle(isNotNull(), isNotNull())).thenReturn(uploadResponse);

    underTest.handle(repository, request);

    verify(handlerA, times(1)).handle(repository, componentUploadCaptor.getValue());
    verify(handlerB, never()).handle(isNotNull(), isNotNull());
    ArgumentCaptor<UIUploadEvent> eventCaptor = ArgumentCaptor.forClass(UIUploadEvent.class);
    verify(eventManager, times(1)).post(eventCaptor.capture());
    assertEquals(repository, eventCaptor.getValue().getRepository());
    assertEquals(assetPaths, eventCaptor.getValue().getAssetPaths());

    // Try the other, to be sure!
    reset(handlerA, handlerB, eventManager);
    when(handlerB.getDefinition()).thenReturn(uploadB);
    when(handlerB.getValidatingComponentUpload(isNotNull())).thenReturn(validatingComponentUpload);
    when(handlerB.handle(isNotNull(), isNotNull())).thenReturn(uploadResponse);

    when(repository.getFormat()).thenReturn(new Format("b")
    {
    });

    underTest.handle(repository, request);

    verify(handlerB, times(1)).handle(repository, componentUploadCaptor.getValue());
    verify(handlerA, never()).handle(isNotNull(), isNotNull());
    eventCaptor = ArgumentCaptor.forClass(UIUploadEvent.class);
    verify(eventManager, times(1)).post(eventCaptor.capture());
    assertEquals(repository, eventCaptor.getValue().getRepository());
    assertEquals(assetPaths, eventCaptor.getValue().getAssetPaths());
  }

  @Test
  void testHandle_unsupportedRepositoryFormat() {
    when(repository.getFormat()).thenReturn(new Format("c")
    {
    });

    ValidationErrorsException exception = assertThrows(ValidationErrorsException.class, 
        () -> underTest.handle(repository, request));
    
    List<String> messages = exception.getValidationErrors().stream().map(ValidationErrorXO::getMessage)
        .collect(Collectors.toList());
    assertEquals(1, messages.size());
    assertEquals("Uploading components to 'c' repositories is unsupported", messages.get(0));
  }

  @Test
  void testHandle_unsupportedRepositoryGroupType() {
    when(repository.getType()).thenReturn(new GroupType());
    
    ValidationErrorsException exception = assertThrows(ValidationErrorsException.class, 
        () -> underTest.handle(repository, request));
    
    List<String> messages = exception.getValidationErrors().stream().map(ValidationErrorXO::getMessage)
        .collect(Collectors.toList());
    assertEquals(1, messages.size());
    assertEquals("Uploading components to a 'group' type repository is unsupported, must be 'hosted'", messages.get(0));
  }

  @Test
  void testHandle_unsupportedRepositoryProxyType() {
    when(repository.getType()).thenReturn(new ProxyType());
    
    ValidationErrorsException exception = assertThrows(ValidationErrorsException.class, 
        () -> underTest.handle(repository, request));
    
    List<String> messages = exception.getValidationErrors().stream().map(ValidationErrorXO::getMessage)
        .collect(Collectors.toList());
    assertEquals(1, messages.size());
    assertEquals("Uploading components to a 'proxy' type repository is unsupported, must be 'hosted'", messages.get(0));
  }

  @Test
  void testHandle_unsupportedRepositoryVirtualType() {
    when(repository.getType()).thenReturn(new VirtualType());
    
    ValidationErrorsException exception = assertThrows(ValidationErrorsException.class, 
        () -> underTest.handle(repository, request));
    
    List<String> messages = exception.getValidationErrors().stream().map(ValidationErrorXO::getMessage)
        .collect(Collectors.toList());
    assertEquals(1, messages.size());
    assertEquals("Uploading components to a 'virtual' type repository is unsupported, must be 'hosted'", messages.get(0));
  }

  @Test
  void testHandle_offlineRepository() {
    when(configuration.isOnline()).thenReturn(false);
    
    ValidationErrorsException exception = assertThrows(ValidationErrorsException.class, 
        () -> underTest.handle(repository, request));
    
    List<String> messages = exception.getValidationErrors().stream().map(ValidationErrorXO::getMessage)
        .collect(Collectors.toList());
    assertEquals(1, messages.size());
    assertEquals("Repository offline", messages.get(0));
  }
  
  @Test
  void testConcurrentMultipartProcessingWithVirtualThreads() throws Exception {
    // Setup virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 50;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Setup common test data
    BlobStoreMultipartForm uploadedForm = new BlobStoreMultipartForm();
    TempBlobFormField field = new TempBlobFormField("asset1", "foo.jar", mock(TempBlob.class));
    uploadedForm.putFile("asset1", field);
    when(blobStoreAwareMultipartHelper.parse(isNotNull(), isNotNull())).thenReturn(uploadedForm);
    
    List<String> assetPaths = Lists.newArrayList("/asset/path/1", "/asset/path/2");
    UploadResponse uploadResponse = mock(UploadResponse.class);
    when(uploadResponse.getAssetPaths()).thenReturn(assetPaths);
    when(handlerA.handle(isNotNull(), isNotNull())).thenReturn(uploadResponse);
    
    try {
      // Submit multiple concurrent upload tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            underTest.handle(repository, request);
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            log.error("Error in concurrent upload", e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "All tasks should complete within timeout");
      
      // Verify all uploads were successful
      assertEquals(taskCount, successCount.get(), "All uploads should succeed");
      
      // Verify handler was called the expected number of times
      verify(handlerA, times(taskCount)).handle(isNotNull(), isNotNull());
    } 
    finally {
      executor.shutdown();
    }
  }
  
  @Test
  void testThreadPinningWithLargeUploads() throws Exception {
    // Setup virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Setup test data for large upload simulation
    BlobStoreMultipartForm uploadedForm = new BlobStoreMultipartForm();
    TempBlobFormField field = new TempBlobFormField("largeAsset", "large-file.bin", mock(TempBlob.class));
    uploadedForm.putFile("largeAsset", field);
    when(blobStoreAwareMultipartHelper.parse(isNotNull(), isNotNull())).thenReturn(uploadedForm);
    
    // Simulate a large upload by making the handler take some time to process
    List<String> assetPaths = Lists.newArrayList("/asset/path/large");
    UploadResponse uploadResponse = mock(UploadResponse.class);
    when(uploadResponse.getAssetPaths()).thenReturn(assetPaths);
    when(handlerA.handle(isNotNull(), isNotNull())).thenAnswer(invocation -> {
      // Simulate processing time for a large file
      Thread.sleep(100);
      return uploadResponse;
    });
    
    int taskCount = 10;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent large upload tasks
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            underTest.handle(repository, request);
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            log.error("Error in large upload", e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "All large upload tasks should complete within timeout");
      
      // Verify all uploads were successful
      assertEquals(taskCount, successCount.get(), "All large uploads should succeed");
      
      // Verify handler was called the expected number of times
      verify(handlerA, times(taskCount)).handle(isNotNull(), isNotNull());
    } 
    finally {
      executor.shutdown();
    }
  }
}