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
package org.sonatype.nexus.blobstore.s3.internal;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.PerformanceLogger;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.DeleteObjectsRequest;
import com.amazonaws.services.s3.model.DeleteObjectsRequest.KeyVersion;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link S3RawObjectAccess} that verify S3 object operations.
 * 
 * These tests validate the core functionality for listing, retrieving, storing, and deleting
 * objects in an S3 bucket through the S3RawObjectAccess interface.
 */
@ExtendWith(MockitoExtension.class)
class S3RawObjectAccessTest
    extends TestSupport
{
  @Mock
  private AmazonS3Factory amazonS3Factory;

  @Mock
  private S3Uploader uploader;

  @Mock
  private AmazonS3 s3;

  @Mock
  private PerformanceLogger performanceLogger;
  
  @Captor
  private ArgumentCaptor<DeleteObjectsRequest> deleteRequestCaptor;

  private S3RawObjectAccess underTest;

  @BeforeEach
  void setup() {
    underTest = new S3RawObjectAccess("mybucket", "prefix/", s3, performanceLogger, uploader);
    when(amazonS3Factory.create(any())).thenReturn(s3);
    when(performanceLogger.maybeWrapForPerformanceLogging(any())).then(returnsFirstArg());
  }

  @Test
  void listRawObjectsReturnsExpectedObjects() {
    // Prepare test data
    List<S3ObjectSummary> summaries = new ArrayList<>();

    S3ObjectSummary summary1 = new S3ObjectSummary();
    summary1.setKey("/path/to/object1");
    S3ObjectSummary summary2 = new S3ObjectSummary();
    summary2.setKey("/path/to/object2");

    summaries.add(summary1);
    summaries.add(summary2);

    // Mock S3 responses
    ObjectListing response = mock(ObjectListing.class);
    when(s3.listObjects(any(ListObjectsRequest.class))).thenReturn(response);
    when(response.getObjectSummaries()).thenReturn(summaries);

    // Execute and verify
    List<String> objects = underTest.listRawObjects(Paths.get("path", "to")).collect(Collectors.toList());
    assertEquals(2, objects.size());
    assertEquals("object1", objects.get(0));
    assertEquals("object2", objects.get(1));
  }

  @Test
  void listRawObjectsReturnsEmptyListWhenNoObjectsExist() {
    // Prepare empty response
    List<S3ObjectSummary> summaries = new ArrayList<>();
    ObjectListing response = mock(ObjectListing.class);
    when(s3.listObjects(any(ListObjectsRequest.class))).thenReturn(response);
    when(response.getObjectSummaries()).thenReturn(summaries);

    // Execute and verify
    List<String> objects = underTest.listRawObjects(Paths.get("path", "to")).collect(Collectors.toList());
    assertTrue(objects.isEmpty());
  }

  @Test
  void listRawObjectsHandlesRootDirectoryCorrectly() {
    // Prepare test data
    List<S3ObjectSummary> summaries = new ArrayList<>();

    S3ObjectSummary summary1 = new S3ObjectSummary();
    summary1.setKey("object1");
    S3ObjectSummary summary2 = new S3ObjectSummary();
    summary2.setKey("object2");

    summaries.add(summary1);
    summaries.add(summary2);

    // Mock S3 responses
    ObjectListing response = mock(ObjectListing.class);
    when(s3.listObjects(any(ListObjectsRequest.class))).thenReturn(response);
    when(response.getObjectSummaries()).thenReturn(summaries);

    // Execute and verify
    List<String> objects = underTest.listRawObjects(null).collect(Collectors.toList());
    assertEquals(2, objects.size());
    assertEquals("object1", objects.get(0));
    assertEquals("object2", objects.get(1));
  }

  @Test
  void getRawObjectReturnsExpectedContent() throws Exception {
    // Prepare test data
    S3Object s3Object = mock(S3Object.class);
    when(s3.getObject(anyString(), anyString())).thenReturn(s3Object);

    when(s3Object.getObjectContent())
        .thenReturn(new S3ObjectInputStream(new ByteArrayInputStream("hello!".getBytes()), null));

    // Execute and verify
    InputStream in = underTest.getRawObject(Paths.get("path", "to", "object1"));
    assertNotNull(in);
    assertEquals("hello!", IOUtils.toString(in, StandardCharsets.UTF_8.name()));
  }

  @Test
  void getRawObjectReturnsNullWhenObjectNotFound() {
    // Prepare 404 exception
    AmazonServiceException e = new AmazonServiceException("Not Found");
    e.setStatusCode(404);

    when(s3.getObject(anyString(), anyString())).thenThrow(e);

    // Execute and verify
    InputStream in = underTest.getRawObject(Paths.get("path", "to", "object1"));
    assertNull(in);
  }

  @Test
  void putRawObjectUploadsToCorrectPath() {
    // Prepare test data
    InputStream in = new ByteArrayInputStream("hello!".getBytes());
    
    // Execute
    underTest.putRawObject(Paths.get("path", "to", "object1"), in);
    
    // Verify
    verify(uploader).upload(s3, "mybucket", "prefix/path/to/object1", in);
  }

  @Test
  void deleteRawObjectsInPathRemovesAllObjectsInPath() {
    // Prepare test data
    List<S3ObjectSummary> summaries = new ArrayList<>();

    S3ObjectSummary summary1 = new S3ObjectSummary();
    summary1.setKey("object1");
    S3ObjectSummary summary2 = new S3ObjectSummary();
    summary2.setKey("object2");

    summaries.add(summary1);
    summaries.add(summary2);

    // Mock S3 responses
    ObjectListing response = mock(ObjectListing.class);
    when(s3.listObjects(any(ListObjectsRequest.class))).thenReturn(response);
    when(response.getObjectSummaries()).thenReturn(summaries);

    // Execute
    underTest.deleteRawObjectsInPath(Paths.get("path", "to", "folder"));

    // Verify
    verify(s3).deleteObjects(deleteRequestCaptor.capture());
    DeleteObjectsRequest deleteObjectsRequest = deleteRequestCaptor.getValue();
    List<KeyVersion> keys = deleteObjectsRequest.getKeys();
    assertEquals("object1", keys.get(0).getKey());
    assertEquals("object2", keys.get(1).getKey());
  }
}
