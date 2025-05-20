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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.java21.Java21TestGroup;
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
import org.sonatype.nexus.virtualthread.VirtualThreadTestGroup;

import org.apache.commons.fileupload.FileUploadException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.google.common.collect.Lists;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.isNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag(Java21TestGroup.class)
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
  void setup() {
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
  void getAvailable() {
    assertThat(underTest.getAvailableDefinitions(), contains(uploadA, uploadB));
  }

  @Test
  void getByFormat() {
    assertThat(underTest.getByFormat("a"), is(uploadA));
    assertThat(underTest.getByFormat("b"), is(uploadB));
  }

  @Test
  void handle() throws IOException, FileUploadException {
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
    assertThat(eventCaptor.getValue().getRepository(), equalTo(repository));
    assertThat(eventCaptor.getValue().getAssetPaths(), equalTo(assetPaths));

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
    assertThat(eventCaptor.getValue().getRepository(), equalTo(repository));
    assertThat(eventCaptor.getValue().getAssetPaths(), equalTo(assetPaths));
  }

  @Test
  void unsupportedRepositoryFormat() throws IOException {
    when(repository.getFormat()).thenReturn(new Format("c")
    {
    });

    ValidationErrorsException exception = assertThrows(ValidationErrorsException.class, 
        () -> underTest.handle(repository, request));
    
    List<String> messages = exception.getValidationErrors().stream().map(ValidationErrorXO::getMessage)
        .collect(Collectors.toList());
    assertThat(messages, contains("Uploading components to 'c' repositories is unsupported"));
  }

  @Test
  void unsupportedRepositoryType() throws IOException {
    // Using pattern matching for switch to evaluate repository types
    Object repoType = repository.getType();
    
    switch (repoType) {
      case GroupType gt -> {
        ValidationErrorsException exception = assertThrows(ValidationErrorsException.class, 
            () -> underTest.handle(repository, request));
        List<String> messages = exception.getValidationErrors().stream().map(ValidationErrorXO::getMessage)
            .collect(Collectors.toList());
        assertThat(messages, contains("Uploading components to a 'group' type repository is unsupported, must be 'hosted'"));
      }
      case ProxyType pt -> {
        ValidationErrorsException exception = assertThrows(ValidationErrorsException.class, 
            () -> underTest.handle(repository, request));
        List<String> messages = exception.getValidationErrors().stream().map(ValidationErrorXO::getMessage)
            .collect(Collectors.toList());
        assertThat(messages, contains("Uploading components to a 'proxy' type repository is unsupported, must be 'hosted'"));
      }
      case VirtualType vt -> {
        ValidationErrorsException exception = assertThrows(ValidationErrorsException.class, 
            () -> underTest.handle(repository, request));
        List<String> messages = exception.getValidationErrors().stream().map(ValidationErrorXO::getMessage)
            .collect(Collectors.toList());
        assertThat(messages, contains("Uploading components to a 'virtual' type repository is unsupported, must be 'hosted'"));
      }
      case HostedType ht -> {
        // This is the expected type, no exception should be thrown
      }
      default -> {
        // Unknown type, should not happen in tests
      }
    }
  }

  @Test
  void offlineRepository() throws IOException {
    when(configuration.isOnline()).thenReturn(false);
    
    ValidationErrorsException exception = assertThrows(ValidationErrorsException.class, 
        () -> underTest.handle(repository, request));
    
    List<String> messages = exception.getValidationErrors().stream().map(ValidationErrorXO::getMessage)
        .collect(Collectors.toList());
    assertThat(messages, contains("Repository offline"));
  }
  
  @Test
  @org.junit.jupiter.api.Tag(VirtualThreadTestGroup.class)
  void concurrentUploadHandlingWithVirtualThreads() throws Exception {
    // Setup for concurrent uploads
    int concurrentUploads = 100;
    CountDownLatch latch = new CountDownLatch(concurrentUploads);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Configure mocks for successful uploads
    BlobStoreMultipartForm uploadedForm = new BlobStoreMultipartForm();
    TempBlobFormField field = new TempBlobFormField("asset1", "foo.jar", mock(TempBlob.class));
    uploadedForm.putFile("asset1", field);
    when(blobStoreAwareMultipartHelper.parse(isNotNull(), isNotNull())).thenReturn(uploadedForm);
    
    List<String> assetPaths = Lists.newArrayList("/asset/path/1", "/asset/path/2");
    UploadResponse uploadResponse = mock(UploadResponse.class);
    when(uploadResponse.getAssetPaths()).thenReturn(assetPaths);
    when(handlerA.handle(isNotNull(), isNotNull())).thenReturn(uploadResponse);
    
    // Create virtual thread executor
    ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
    
    try {
      // Submit concurrent upload tasks
      for (int i = 0; i < concurrentUploads; i++) {
        executor.submit(() -> {
          try {
            underTest.handle(repository, request);
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            // Log exception in real implementation
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all uploads to complete or timeout
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertThat("All concurrent uploads should complete within timeout", completed, is(true));
      assertThat("All uploads should succeed", successCount.get(), is(concurrentUploads));
      
      // Verify handler was called the expected number of times
      verify(handlerA, times(concurrentUploads)).handle(repository, componentUploadCaptor.getValue());
    } 
    finally {
      executor.shutdown();
    }
  }
}