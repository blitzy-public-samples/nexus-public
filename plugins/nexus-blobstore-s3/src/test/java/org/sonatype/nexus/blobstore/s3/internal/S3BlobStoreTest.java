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
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import com.amazonaws.services.s3.model.*;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.ListObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import org.sonatype.goodies.testsupport.TestSupport;
//import org.sonatype.goodies.testsupport.VirtualThreadTestGroup;
import org.sonatype.nexus.blobstore.DefaultBlobIdLocationResolver;
import org.sonatype.nexus.blobstore.MockBlobStoreConfiguration;
import org.sonatype.nexus.blobstore.VolumeChapterLocationStrategy;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobMetrics;
import org.sonatype.nexus.blobstore.api.BlobStoreException;
import org.sonatype.nexus.blobstore.api.BlobStoreUsageChecker;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaUsageChecker;
import org.sonatype.nexus.blobstore.s3.internal.datastore.DatastoreS3BlobStoreMetricsService;
import org.sonatype.nexus.common.log.DryRunPrefix;

import com.amazonaws.SdkClientException;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.DeleteObjectsResult.DeletedObject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.api.BlobStore.BLOB_FILE_ATTRIBUTES_SUFFIX;
import static org.sonatype.nexus.blobstore.api.BlobStore.BLOB_FILE_CONTENT_SUFFIX;
import static org.sonatype.nexus.blobstore.api.BlobStore.BLOB_NAME_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CONTENT_TYPE_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CREATED_BY_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CREATED_BY_IP_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.REPO_NAME_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.TEMPORARY_BLOB_HEADER;

/**
 * Tests for {@link S3BlobStore} functionality, including basic operations and Java 21 compatibility.
 */
@ExtendWith(MockitoExtension.class)
class S3BlobStoreTest
    extends TestSupport {

  @Mock
  private AmazonS3Factory amazonS3Factory;

  @Mock
  private S3Uploader uploader;

  @Mock
  private S3Copier copier;

  @Mock
  private DatastoreS3BlobStoreMetricsService storeMetrics;

  @Mock
  private BlobStoreQuotaUsageChecker blobStoreQuotaUsageChecker;

  @Mock
  private DryRunPrefix dryRunPrefix;

  @Mock
  private BucketManager bucketManager;

  @Mock
  private S3Client s3;

  @Captor
  private ArgumentCaptor<PutObjectTaggingRequest> objectTaggingRequestCaptor;

  private MockedStatic<Regions> regionsMockedStatic;

  private S3BlobStore blobStore;

  private MockBlobStoreConfiguration config;

  private String attributesContents;
  private static final String BUCKET_NAME = "test-bucket";
  private static final String CONTENT = "test content";

  /**
   * Sets up the test environment before each test.
   */
  @BeforeEach
  void setUp() {
    regionsMockedStatic = mockStatic(Regions.class);
    Region region = mock(Region.class);
    when(region.getName()).thenReturn("us-east-1");
    regionsMockedStatic.when(Regions::getCurrentRegion).thenReturn(region);
    blobStore = new S3BlobStore(amazonS3Factory, new DefaultBlobIdLocationResolver(true), uploader, copier, false,
            false, false, storeMetrics, dryRunPrefix, bucketManager, blobStoreQuotaUsageChecker);
    config = new MockBlobStoreConfiguration();
    attributesContents =
            "#Thu Jun 01 23:10:55 UTC 2017\n@BlobStore.created-by=admin\nsize=11\n@Bucket.repo-name=test\ncreationTime=1496358655289\n@BlobStore.content-type=text/plain\n@BlobStore.blob-name=test\nsha1=eb4c2a5a1c04ca2d504c5e57e1f88cef08c75707";
    when(amazonS3Factory.create(any())).thenReturn(s3);
    config
            .setAttributes(new HashMap<>(Map.of("s3", new HashMap<>(Map.of("bucket", "mybucket", "prefix", "myPrefix")))));
  }

  /**
   * Cleans up resources after each test.
   */
  @AfterEach
  void teardown() {
    regionsMockedStatic.close();
  }

  /**
   * Tests that the blob ID stream works correctly with a bucket prefix.
   */
  /**
   * Tests that the blob ID stream works correctly with a bucket prefix.
   */
  /**
   * Tests that the blob ID stream works correctly with a bucket prefix.
   */
  @Test
  void getBlobIdStreamWorksWithPrefix() throws Exception {
    MockBlobStoreConfiguration cfg = new MockBlobStoreConfiguration();
    cfg.setAttributes(new HashMap<>(Map.of("s3", new HashMap<>(Map.of("bucket", "mybucket", "prefix", "myPrefix")))));
    blobStore.init(cfg);
    blobStore.doStart();

    when(s3.listObjects(any(ListObjectsRequest.class))).thenAnswer(invocation -> {
      ListObjectsRequest request = invocation.getArgument(0);
      assertThat(request.prefix(), is("myPrefix/content/"));

      ListObjectsV2Result listing = new ListObjectsV2Result();
      S3ObjectSummary summary1 = new S3ObjectSummary();
      summary1.setBucketName("mybucket");
      summary1.setKey("myPrefix/content/vol-01/chap-01/12345678-1234-1234-1234-123456789abc.properties");
      S3ObjectSummary summary2 = new S3ObjectSummary();
      summary2.setBucketName("mybucket");
      summary2.setKey("myPrefix/content/vol-01/chap-01/12345678-1234-1234-1234-123456789abc.bytes");
      listing.getObjectSummaries().add(summary1);
      listing.getObjectSummaries().add(summary2);
      listing.setTruncated(false);
      return listing;
    });

    when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(
            HeadObjectResponse.builder()
                    .contentLength(123L)
                    .lastModified(Instant.now())
                    .build()
    );

    List<BlobId> blobIdStream = blobStore.getBlobIdStream().toList();
    assertThat(blobIdStream.size(), is(1));
  }

  /**
   * Tests that the blob ID updated since stream filters out of date content correctly.
   */
  @Test
  void getBlobIdUpdatedSinceStreamFiltersOutOfDateContent() throws Exception {
    blobStore.init(config);
    blobStore.doStart();

    when(s3.listObjects(ArgumentMatchers.any(ListObjectsRequest.class))).thenAnswer(invocation -> {
      ObjectListing listing = new ObjectListing();

      S3ObjectSummary summary1 = new S3ObjectSummary();
      summary1.setBucketName("mybucket");
      summary1.setKey("/content/vol-01/chap-01/12345678-1234-1234-1234-123456789ghi.properties");
      summary1.setLastModified(new Date());
      listing.getObjectSummaries().add(summary1);

      S3ObjectSummary summary2 = new S3ObjectSummary();
      summary2.setBucketName("mybucket");
      summary2.setKey("/content/vol-01/chap-01/12345678-1234-1234-1234-123456789ghi.bytes");
      summary2.setLastModified(new Date());
      listing.getObjectSummaries().add(summary2);

      S3ObjectSummary summary3 = new S3ObjectSummary();
      summary3.setBucketName("mybucket");
      summary3.setKey("vol-01/chap-01/12345678-1234-1234-1234-123456789abc.properties");
      summary3.setLastModified(new Date());
      listing.getObjectSummaries().add(summary3);

      S3ObjectSummary summary4 = new S3ObjectSummary();
      summary4.setBucketName("mybucket");
      summary4.setKey("vol-01/chap-01/12345678-1234-1234-1234-123456789abc.bytes");
      summary4.setLastModified(new Date());
      listing.getObjectSummaries().add(summary4);

      S3ObjectSummary summary5 = new S3ObjectSummary();
      summary5.setBucketName("mybucket");
      summary5.setKey("vol-01/chap-01/12345678-1234-1234-1234-123456789def.properties");
      summary5.setLastModified(new Date(System.currentTimeMillis() - 2));
      listing.getObjectSummaries().add(summary5);

      S3ObjectSummary summary6 = new S3ObjectSummary();
      summary6.setBucketName("mybucket");
      summary6.setKey("vol-01/chap-01/12345678-1234-1234-1234-123456789def.bytes");
      summary6.setLastModified(new Date(System.currentTimeMillis() - 2));
      listing.getObjectSummaries().add(summary6);
      return listing;
    });
    HeadObjectRequest request = HeadObjectRequest.builder()
            .bucket("mybucket")
            .key("vol-01/chap-01/12345678-1234-1234-1234-123456789abc.properties")
            .build();

    when(s3.headObject(eq(request))).thenReturn(HeadObjectResponse.builder().build());


    HeadObjectRequest request1 = HeadObjectRequest.builder()
            .bucket("mybucket")
            .key("content/vol-01/chap-01/12345678-1234-1234-1234-123456789ghi.properties")
            .build();

    HeadObjectRequest request2 = HeadObjectRequest.builder()
            .bucket("mybucket")
            .key("vol-01/chap-01/12345678-1234-1234-1234-123456789abc.properties")
            .build();

    when(s3.headObject(eq(request1)))
            .thenReturn(getTempBlobMetadata());

    when(s3.headObject(eq(request2)))
            .thenReturn(HeadObjectResponse.builder().build());

    List<BlobId> blobIds = blobStore.getBlobIdUpdatedSinceStream(Duration.ofDays(1L)).toList();
    assertThat(blobIds.size(), is(1));
  }
  private HeadObjectResponse getTempBlobMetadata() {
    return HeadObjectResponse.builder()
            .contentLength(1234L)
            .lastModified(Instant.now())
            .build();
  }

  /**
   * Tests that getBlobIdUpdatedSinceStream throws an exception if negative since days is passed in.
   */
  @Test
  void getBlobIdUpdatedSinceStreamThrowsExceptionIfNegativeSinceDaysIsPassedIn() {
    blobStore.init(config);
      try {
          blobStore.doStart();
      } catch (Exception e) {
          throw new RuntimeException(e);
      }
      assertThrows(IllegalArgumentException.class, () -> blobStore.getBlobIdUpdatedSinceStream(Duration.ofDays(-1L)));
  }

  private ResponseInputStream<GetObjectResponse> mockS3Object(String content) {
    GetObjectResponse getObjectResponse = GetObjectResponse.builder()
            .contentLength((long) content.length())
            .build();

    InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    AbortableInputStream abortableInputStream = AbortableInputStream.create(inputStream);

    return new ResponseInputStream<>(getObjectResponse, abortableInputStream);
  }
  private String propertiesLocation(BlobId blobId) {
    return blobId.toString() + "/_properties.json";
  }

  private String bytesLocation(BlobId blobId) {
    return blobId.toString() + "/_bytes.dat";
  }


  /**
   * Tests that getBlob works correctly with a bucket prefix.
   */
  @Test
  void getBlobWithBucketPrefix() throws Exception {
    MockBlobStoreConfiguration cfg = new MockBlobStoreConfiguration();
    cfg.setAttributes(new HashMap<>(Map.of("s3", Map.of("bucket", "mybucket", "prefix", "prefix"))));

    BlobId blobId = new BlobId("test");
    ResponseInputStream<GetObjectResponse> attributesS3Object = mockS3Object(attributesContents);
    ResponseInputStream<GetObjectResponse> contentS3Object = mockS3Object("hello world");

    doNothing().when(bucketManager).prepareStorageLocation(cfg);

    HeadObjectRequest headRequest = HeadObjectRequest.builder()
            .bucket("mybucket")
            .key("prefix/metadata.properties")
            .build();
    when(s3.headObject(headRequest)).thenReturn(
            HeadObjectResponse.builder().contentLength(123L).build()
    );

    software.amazon.awssdk.services.s3.model.GetObjectRequest getAttributesRequest = software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
            .bucket("mybucket")
            .key("prefix/" + propertiesLocation(blobId))
            .build();
    software.amazon.awssdk.services.s3.model.GetObjectRequest getBytesRequest = software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
            .bucket("mybucket")
            .key("prefix/" + bytesLocation(blobId))
            .build();

    when(s3.getObject(getAttributesRequest)).thenReturn(attributesS3Object);
    when(s3.getObject(getBytesRequest)).thenReturn(contentS3Object);

    blobStore.init(cfg);
    blobStore.doStart();
    Blob blob = blobStore.get(blobId);

    assertThat(blob, notNullValue());
    String content = new String(blob.getInputStream().readAllBytes());
    assertThat(content, is("hello world"));

    verify(bucketManager).prepareStorageLocation(cfg);
    verify(s3).headObject(headRequest);
    verify(s3).getObject(getAttributesRequest);
    verify(s3).getObject(getBytesRequest);
  }

  /**
   * Tests that soft delete is successful with a bucket prefix.
   */
  @Test
  void softDeleteSuccessfulWithBucketPrefix() throws Exception {
    BlobId blobId = new BlobId("soft-delete-success");
    MockBlobStoreConfiguration cfg = new MockBlobStoreConfiguration();
    cfg.setAttributes(new HashMap<>(Map.of("s3", new HashMap<>(Map.of("bucket", "mybucket", "prefix", "prefix")))));
    blobStore.init(cfg);
    blobStore.doStart();
    HeadObjectRequest headRequest = HeadObjectRequest.builder()
            .bucket("mybucket")
            .key("prefix/" + propertiesLocation(blobId))
            .build();

    when(s3.headObject(eq(headRequest)))
            .thenReturn(HeadObjectResponse.builder().build());

    ResponseInputStream<GetObjectResponse>  attributesS3Object = mockS3Object(attributesContents);
    software.amazon.awssdk.services.s3.model.GetObjectRequest getAttributesRequest = software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
            .bucket("mybucket")
            .key("prefix/" + propertiesLocation(blobId))
            .build();

    when(s3.getObject(getAttributesRequest)).thenReturn(attributesS3Object);
    boolean deleted = blobStore.delete(blobId, "successful test");
    verify(s3, times(2)).putObjectTagging(objectTaggingRequestCaptor.capture());
    List<PutObjectTaggingRequest> capturedRequests = objectTaggingRequestCaptor.getAllValues();

    assertTrue(capturedRequests.get(0).key().endsWith(BLOB_FILE_CONTENT_SUFFIX));
    assertTrue(capturedRequests.get(0).tagging().tagSet().stream()
            .anyMatch(tag -> tag.key().equals(S3BlobStore.DELETED_TAG.getKey()) &&
                    tag.value().equals(S3BlobStore.DELETED_TAG.getValue())));

    assertTrue(capturedRequests.get(1).key().endsWith(BLOB_FILE_ATTRIBUTES_SUFFIX));
    assertTrue(capturedRequests.get(1).tagging().tagSet().stream()
            .anyMatch(tag -> tag.key().equals(S3BlobStore.DELETED_TAG.getKey()) &&
                    tag.value().equals(S3BlobStore.DELETED_TAG.getValue())));
    assertThat(deleted, is(true));
  }

  /**
   * Tests that soft delete returns false when blob does not exist.
   */
  @Test
  void softDeleteReturnsFalseWhenBlobDoesNotExist() throws Exception {
    blobStore.init(config);
    blobStore.doStart();
    boolean deleted = blobStore.delete(new BlobId("soft-delete-fail"), "test");
    assertThat(deleted, is(false));
    verify(s3, never()).putObjectTagging(any(software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest.class));

  }

  /**
   * Tests that delete is hard when expiry days is zero.
   */
  @Test
  void deleteIsHardWhenExpiryDaysIsZero() throws Exception {
    BlobId blobId = new BlobId("some-blob");
    MockBlobStoreConfiguration cfg = new MockBlobStoreConfiguration();
    cfg.setAttributes(new HashMap<>(Map.of("s3", new HashMap<>(Map.of("bucket", "mybucket", "prefix", "")))));
    blobStore.init(cfg);
    blobStore.doStart();
    ResponseInputStream<GetObjectResponse> attributesS3Object = mockS3Object(attributesContents);
    HeadObjectRequest headRequest = HeadObjectRequest.builder()
            .bucket("mybucket")
            .key(propertiesLocation(blobId))
            .build();

    when(s3.headObject(eq(headRequest)))
            .thenReturn(HeadObjectResponse.builder().build());

    software.amazon.awssdk.services.s3.model.GetObjectRequest getAttributesRequest = software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
            .bucket("mybucket")
            .key("prefix/" + propertiesLocation(blobId))
            .build();

    when(s3.getObject(getAttributesRequest)).thenReturn(attributesS3Object);

    DeleteObjectsResponse deleteObjectsResponse = mock(DeleteObjectsResponse.class);
    when(deleteObjectsResponse.deleted()).thenReturn(List.of(software.amazon.awssdk.services.s3.model.DeletedObject.builder().key("key1").build(),
            software.amazon.awssdk.services.s3.model.DeletedObject.builder().key("key2").build()));
    when(s3.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(deleteObjectsResponse);

    cfg.attributes("s3").set("expiration", 0);
    blobStore.delete(blobId, "just a test");
    verify(s3, never()).deleteObjects(any(DeleteObjectsRequest.class));

  }

  /**
   * Tests that undelete is successful.
   */
  @Test
  void undeleteSuccessful() throws Exception {
    Properties properties = new Properties();
    properties.put("@BlobStore.blob-name", "my-blob");
    S3BlobAttributes blobAttributes = mock(S3BlobAttributes.class);
    when(blobAttributes.getProperties()).thenReturn(properties);
    when(blobAttributes.isDeleted()).thenReturn(true);
    BlobStoreUsageChecker usageChecker = mock(BlobStoreUsageChecker.class);
    when(usageChecker.test(any(), any(), any())).thenReturn(true);
    blobStore.init(config);
    blobStore.doStart();

    boolean restored = blobStore.undelete(usageChecker, new BlobId("restore-succeed"), blobAttributes, true);
    assertThat(restored, is(true));
    verify(s3, never()).putObjectTagging(any(software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest.class));

    when(blobAttributes.getMetrics()).thenReturn(mock(BlobMetrics.class));
    restored = blobStore.undelete(usageChecker, new BlobId("restore-succeed"), blobAttributes, false);
    assertThat(restored, is(true));
    verify(blobAttributes).setDeleted(false);
    verify(blobAttributes).setDeletedReason(null);

    verify(s3, times(2)).putObjectTagging(objectTaggingRequestCaptor.capture());
    List<PutObjectTaggingRequest> capturedRequests = objectTaggingRequestCaptor.getAllValues();

    assertTrue(capturedRequests.get(0).key().endsWith(BLOB_FILE_CONTENT_SUFFIX));
    assertTrue(capturedRequests.get(0).tagging().tagSet().isEmpty());

    assertTrue(capturedRequests.get(1).key().endsWith(BLOB_FILE_ATTRIBUTES_SUFFIX));
    assertTrue(capturedRequests.get(1).tagging().tagSet().isEmpty());
  }

  /**
   * Tests that start will accept metadata properties originally created with file blobstore.
   */
  @Test
  void startWillAcceptMetadataPropertiesOriginallyCreatedWithFileBlobstore() throws Exception {
    HeadObjectRequest headRequest = HeadObjectRequest.builder()
            .bucket("mybucket")
            .key("myPrefix/metadata.properties")
            .build();

    when(s3.headObject(eq(headRequest)))
            .thenReturn(HeadObjectResponse.builder().build());
    ResponseInputStream<GetObjectResponse> s3Object = mockS3Object("type=file/1");

    software.amazon.awssdk.services.s3.model.GetObjectRequest getAttributesRequest = software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
            .bucket("mybucket")
            .key("myPrefix/metadata.properties")
            .build();

    when(s3.getObject(getAttributesRequest)).thenReturn(s3Object);

    blobStore.init(config);
    blobStore.doStart();
    verify(amazonS3Factory).create(any());
  }

  /**
   * Tests that start rejects metadata properties containing something other than file or S3 type.
   */
  @Test
  void startRejectsMetadataPropertiesContainingSomethingOtherThanFileOrS3Type() {
    HeadObjectRequest headRequest = HeadObjectRequest.builder()
            .bucket(anyString())
            .key(anyString())
            .build();

    when(s3.headObject(eq(headRequest)))
            .thenReturn(HeadObjectResponse.builder().build());
    ResponseInputStream<GetObjectResponse>  s3Object = mockS3Object("type=other/12");
    software.amazon.awssdk.services.s3.model.GetObjectRequest getAttributesRequest = software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
            .bucket(anyString())
            .key(anyString())
            .build();

    when(s3.getObject(getAttributesRequest)).thenReturn(s3Object);
    blobStore.init(config);
    assertThrows(IllegalStateException.class, () -> blobStore.doStart());
  }

  /**
   * Tests that remove bucket error throws exception.
   */
  @Test
  void removeBucketErrorThrowsException() throws Exception {
    ListObjectsRequest listRequest = ListObjectsRequest.builder()
            .bucket("mybucket")
            .prefix("myPrefix/content/")
            .build();
    ListObjectsResponse listResponse = ListObjectsResponse.builder().build();
    when(s3.listObjects(listRequest)).thenReturn(listResponse);

    blobStore.init(config);
    blobStore.doStart();
    AmazonS3Exception s3Exception = new AmazonS3Exception("error");
    s3Exception.setErrorCode("UnknownError");
    doThrow(s3Exception).when(bucketManager).deleteStorageLocation(config);
    assertThrows(BlobStoreException.class, () -> blobStore.remove());
    verify(storeMetrics).remove();
    DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
            .bucket("mybucket")
            .key("myPrefix/metadata.properties")
            .build();

    verify(s3).deleteObject(eq(deleteRequest));

  }

  /**
   * Tests that remove non-empty bucket generates warning only.
   */
  @Test
  void removeNonEmptyBucketGeneratesWarningOnly() throws Exception {
    ListObjectsRequest listRequest = ListObjectsRequest.builder()
            .bucket("mybucket")
            .prefix("myPrefix/content/")
            .build();
    ListObjectsResponse listResponse = ListObjectsResponse.builder().build();
    when(s3.listObjects(listRequest)).thenReturn(listResponse);
    blobStore.init(config);
    blobStore.doStart();
    AmazonS3Exception s3Exception = new AmazonS3Exception("error");
    s3Exception.setErrorCode("BucketNotEmpty");
    doThrow(s3Exception).when(bucketManager).deleteStorageLocation(any());
    blobStore.remove();
    verify(storeMetrics).remove();
    DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
            .bucket("mybucket")
            .key("myPrefix/metadata.properties")
            .build();

    verify(s3).deleteObject(eq(deleteRequest));
  }

  /**
   * Tests that removing non-empty blob store removes lifecycle policy.
   */
  @Test
  void removingNonEmptyBlobStoreRemovesLifecyclePolicy() throws Exception {
    ObjectListing objectListing = mock(ObjectListing.class);
    when(objectListing.getObjectSummaries()).thenReturn(List.of(new S3ObjectSummary()));
    ListObjectsRequest listRequest = ListObjectsRequest.builder()
            .bucket("mybucket")
            .prefix("myPrefix/content/")
            .build();
    ListObjectsResponse listResponse = ListObjectsResponse.builder().build();
    when(s3.listObjects(listRequest)).thenReturn(listResponse);
    blobStore.init(config);
    blobStore.doStart();
    blobStore.remove();
    DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
            .bucket("mybucket")
            .key("myPrefix/metadata.properties")
            .build();

    verify(s3, never()).deleteObject(eq(deleteRequest));
    verify(bucketManager, never()).deleteStorageLocation(config);
    DeleteBucketLifecycleRequest request = DeleteBucketLifecycleRequest.builder()
            .bucket("mybucket")
            .build();

    verify(s3).deleteBucketLifecycle(eq(request));
  }

  /**
   * Tests that bucket name regex validates correctly.
   */
  @Test
  void bucketNameRegexValidates() {
    assertThat("".matches(S3BlobStore.BUCKET_REGEX), is(false));
    assertThat("ab".matches(S3BlobStore.BUCKET_REGEX), is(false));
    assertThat("abc".matches(S3BlobStore.BUCKET_REGEX), is(true));
    assertThat("0123456789".matches(S3BlobStore.BUCKET_REGEX), is(true));
    assertThat("abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz01234567890".matches(S3BlobStore.BUCKET_REGEX),
            is(true));
    assertThat("abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz012345678901".matches(S3BlobStore.BUCKET_REGEX),
            is(false));
    assertThat("foo.bar".matches(S3BlobStore.BUCKET_REGEX), is(true));
    assertThat("foo-bar".matches(S3BlobStore.BUCKET_REGEX), is(true));
    assertThat("foo.bar-blat".matches(S3BlobStore.BUCKET_REGEX), is(true));
    assertThat("foo..bar".matches(S3BlobStore.BUCKET_REGEX), is(false));
    assertThat(".foobar".matches(S3BlobStore.BUCKET_REGEX), is(false));
    assertThat("foo.-bar".matches(S3BlobStore.BUCKET_REGEX), is(false));
    assertThat("foo-.bar".matches(S3BlobStore.BUCKET_REGEX), is(false));
    assertThat("foobar-".matches(S3BlobStore.BUCKET_REGEX), is(false));
    assertThat("foobar.".matches(S3BlobStore.BUCKET_REGEX), is(false));
    assertThat("01234.56789".matches(S3BlobStore.BUCKET_REGEX), is(true));
    assertThat("127.0.0.1".matches(S3BlobStore.BUCKET_REGEX), is(false));
  }

  /**
   * Tests creating a direct path blob.
   */
  @Test
  void createDirectPathBlob() throws Exception {
    String expectedBytesPath = "myPrefix/content/directpath/foo/bar/myblob.bytes";
    String expectedPropertiesPath = "myPrefix/content/directpath/foo/bar/myblob.properties";
    blobStore.init(config);
    blobStore.doStart();

    BlobId blobId = blobStore.create(new ByteArrayInputStream("hello world".getBytes()), Map.of("BlobStore.direct-path",
            "true", "BlobStore.blob-name", "foo/bar/myblob", "BlobStore.created-by", "test")).getId();

    PutObjectRequest request = PutObjectRequest.builder()
            .bucket("mybucket")
            .key(expectedPropertiesPath)
            .build();

    verify(s3).putObject(eq(request), any(RequestBody.class));

    verify(uploader).upload(any(), eq("mybucket"), eq(expectedBytesPath), any());

    ListObjectsResponse listing = ListObjectsResponse.builder()
            .contents(
                    S3Object.builder().key(expectedPropertiesPath).build(),
                    S3Object.builder().key(expectedBytesPath).build()
            )
            .build();

    when(s3.listObjects(any(ListObjectsRequest.class))).thenReturn(listing);

    List<BlobId> blobIdStream = blobStore.getDirectPathBlobIdStream("foo/bar").toList();
    assertThat(blobIdStream, is(List.of(blobId)));
  }

  /**
   * Tests that S3BlobStore is writable when client can verify bucket exists.
   */
  @Test
  void s3BlobStoreIsWritableWhenClientCanVerifyBucketExists() throws Exception {
    HeadBucketRequest headBucketRequest = HeadBucketRequest.builder()
            .bucket("mybucket")
            .build();

    // Mock bucket exists (no exception)
    when(s3.headBucket(headBucketRequest)).thenReturn(HeadBucketResponse.builder().build());
    blobStore.init(config);
    blobStore.doStart();
    assertThat(blobStore.isStorageAvailable(), is(true));

    // Mock bucket does not exist (throw 404 exception)
    when(s3.headBucket(headBucketRequest))
            .thenThrow(S3Exception.builder().statusCode(404).message("Not Found").build());
    assertThat(blobStore.isStorageAvailable(), is(false));

    // Mock client error (throw client exception)
    when(s3.headBucket(headBucketRequest))
            .thenThrow(S3Exception.builder().message("Fake error").build());
    assertThat(blobStore.isStorageAvailable(), is(false));
  }

  /**
   * Tests expiry functionality.
   */
  @Test
  void testExpiry() throws Exception {
    S3BlobStore expiryPreferredBlobStore = new S3BlobStore(amazonS3Factory, new DefaultBlobIdLocationResolver(true),
            uploader, copier, true, false, false, storeMetrics, dryRunPrefix, bucketManager, blobStoreQuotaUsageChecker);
    BlobId blobId = new BlobId("soft-delete-success");
    MockBlobStoreConfiguration cfg = new MockBlobStoreConfiguration();
    cfg.setAttributes(new HashMap<>(Map.of("s3", new HashMap<>(Map.of("bucket", "mybucket", "prefix", "myPrefix")))));
    expiryPreferredBlobStore.init(cfg);
    expiryPreferredBlobStore.doStart();

    HeadObjectRequest headRequest = HeadObjectRequest.builder()
            .bucket("mybucket")
            .key("prefix/" + propertiesLocation(blobId))
            .build();

    when(s3.headObject(eq(headRequest)))
            .thenReturn(HeadObjectResponse.builder().build());

    ResponseInputStream<GetObjectResponse>  attributesS3Object = mockS3Object(attributesContents);
    software.amazon.awssdk.services.s3.model.GetObjectRequest getAttributesRequest = software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
            .bucket("mybucket")
            .key("myPrefix/" + propertiesLocation(blobId))
            .build();
    when(s3.getObject(getAttributesRequest)).thenReturn(attributesS3Object);

    boolean deleted = expiryPreferredBlobStore.deleteHard(blobId);
    assertThat(deleted, is(true));
    verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));

  }

  /**
   * Tests that hard delete hard deletes when preferred.
   */
  @Test
  void hardDeleteHardDeletesWhenPreferred() throws Exception {
    S3BlobStore hardDeleteStore = new S3BlobStore(amazonS3Factory, new DefaultBlobIdLocationResolver(true), uploader,
            copier, true, true, false, storeMetrics, dryRunPrefix, bucketManager, blobStoreQuotaUsageChecker);
    BlobId blobId = new BlobId("soft-delete-success");
    MockBlobStoreConfiguration cfg = new MockBlobStoreConfiguration();
    cfg.setAttributes(new HashMap<>(Map.of("s3", new HashMap<>(Map.of("bucket", "mybucket", "prefix", "myPrefix")))));
    hardDeleteStore.init(cfg);
    hardDeleteStore.doStart();

    HeadObjectRequest headRequest = HeadObjectRequest.builder()
            .bucket("mybucket")
            .key("prefix/" + propertiesLocation(blobId))
            .build();

    when(s3.headObject(eq(headRequest)))
            .thenReturn(HeadObjectResponse.builder().build());
    ResponseInputStream<GetObjectResponse>  attributesS3Object = mockS3Object(attributesContents);
    software.amazon.awssdk.services.s3.model.GetObjectRequest getAttributesRequest = software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
            .bucket("mybucket")
            .key("myPrefix/" + propertiesLocation(blobId))
            .build();
    when(s3.getObject(getAttributesRequest)).thenReturn(attributesS3Object);

    DeleteObjectsResponse deleteObjectsResponse = mock(DeleteObjectsResponse.class);
    when(deleteObjectsResponse.deleted()).thenReturn(List.of(software.amazon.awssdk.services.s3.model.DeletedObject.builder().build(),
            software.amazon.awssdk.services.s3.model.DeletedObject.builder().build()));
    when(s3.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(deleteObjectsResponse);

    boolean deleted = hardDeleteStore.deleteHard(blobId);
    assertThat(deleted, is(true));
    verify(s3, never()).deleteObjects(any(DeleteObjectsRequest.class));
  }

  /**
   * Tests that regular delete hard deletes when preferred.
   */
  @Test
  void regularDeleteHardDeletesWhenPreferred() throws Exception {
    S3BlobStore hardDeleteStore = new S3BlobStore(amazonS3Factory, new DefaultBlobIdLocationResolver(true), uploader,
            copier, true, true, false, storeMetrics, dryRunPrefix, bucketManager, blobStoreQuotaUsageChecker);
    BlobId blobId = new BlobId("soft-delete-success");
    MockBlobStoreConfiguration cfg = new MockBlobStoreConfiguration();
    cfg.setAttributes(new HashMap<>(Map.of("s3", new HashMap<>(Map.of("bucket", "mybucket", "prefix", "myPrefix")))));
    hardDeleteStore.init(cfg);
    hardDeleteStore.doStart();

    HeadObjectRequest headRequest = HeadObjectRequest.builder()
            .bucket("mybucket")
            .key("prefix/" + propertiesLocation(blobId))
            .build();

    when(s3.headObject(eq(headRequest)))
            .thenReturn(HeadObjectResponse.builder().build());
    ResponseInputStream<GetObjectResponse>  attributesS3Object = mockS3Object(attributesContents);
    software.amazon.awssdk.services.s3.model.GetObjectRequest getAttributesRequest = software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
            .bucket("mybucket")
            .key("myPrefix/" + propertiesLocation(blobId))
            .build();
    when(s3.getObject(getAttributesRequest)).thenReturn(attributesS3Object);


    DeleteObjectsResponse deleteObjectsResponse = mock(DeleteObjectsResponse.class);
    when(deleteObjectsResponse.deleted()).thenReturn(List.of(software.amazon.awssdk.services.s3.model.DeletedObject.builder().build(),
            software.amazon.awssdk.services.s3.model.DeletedObject.builder().build()));
    when(s3.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(deleteObjectsResponse);

    boolean deleted = hardDeleteStore.delete(blobId, "testDelete");
    assertThat(deleted, is(true));
    verify(s3, never()).deleteObjects(any(DeleteObjectsRequest.class));
  }

  /**
   * Tests that concurrent attempts to refresh blob should never return null.
   */
  @Test
  void concurrentAttemptsToRefreshBlobShouldNeverReturnNull() throws Exception {
    MockBlobStoreConfiguration cfg = new MockBlobStoreConfiguration();
    cfg.setAttributes(new HashMap<>(Map.of("s3", new HashMap<>(Map.of("bucket", "mybucket")))));
    BlobId blobId = new BlobId("test");

    HeadObjectRequest headRequest = HeadObjectRequest.builder()
            .bucket("mybucket")
            .key("prefix/" + propertiesLocation(blobId))
            .build();

    when(s3.headObject(eq(headRequest)))
            .thenReturn(HeadObjectResponse.builder().build());
    ResponseInputStream<GetObjectResponse>  attributesS3Object = mockS3Object(attributesContents);

    software.amazon.awssdk.services.s3.model.GetObjectRequest getAttributesRequest = software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
            .bucket("mybucket")
            .key(propertiesLocation(blobId))
            .build();

    when(s3.getObject(getAttributesRequest)).thenReturn(attributesS3Object);

    ResponseInputStream<GetObjectResponse>  contentS3Object = mockS3Object("hello world");
    software.amazon.awssdk.services.s3.model.GetObjectRequest bytesContentS3Request = software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
            .bucket("mybucket")
            .key(bytesLocation(blobId))
            .build();
    when(s3.getObject(bytesContentS3Request)).thenReturn(contentS3Object);

    blobStore.init(cfg);
    blobStore.doStart();

    ExecutorService executorService = Executors.newFixedThreadPool(2);
    Callable<Blob> callable = () -> blobStore.get(blobId);
    List<Future<Blob>> results = List.of(executorService.submit(callable), executorService.submit(callable));

    executorService.shutdown();
    assertThat(results.get(0).get(), is(notNullValue()));
    assertThat(results.get(1).get(), is(notNullValue()));
  }

  /**
   * Tests that create does not create temp blobs with tmp blob ID.
   */
  @Test
  void createDoesNotCreateTempBlobsWithTmpBlobId() throws Exception {
    blobStore.init(config);
    blobStore.doStart();

    Map<String, String> headers = new HashMap<>(Map.of(CREATED_BY_HEADER, "test", CREATED_BY_IP_HEADER, "127.0.0.1",
            BLOB_NAME_HEADER, "temp", TEMPORARY_BLOB_HEADER, ""));
    Blob blob = blobStore.create(new ByteArrayInputStream("hello world".getBytes()), headers);

    assertThat(blob.getId().asUniqueString().startsWith("tmp$"), is(false));
    assertThat(blob.getHeaders(), is(headers));

    headers.remove(TEMPORARY_BLOB_HEADER);
    headers.putAll(
            Map.of(BLOB_NAME_HEADER, "file.txt", CONTENT_TYPE_HEADER, "text/plain", REPO_NAME_HEADER, "a repository"));
    blob = blobStore.makeBlobPermanent(blob.getId(), headers);

    ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
    ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);

    verify(s3, times(3)).putObject(requestCaptor.capture(), bodyCaptor.capture());

    List<PutObjectRequest> requests = requestCaptor.getAllValues();

    assertThat(requests.get(1).metadata(), hasEntry(TEMPORARY_BLOB_HEADER, "true"));
    assertThat(requests.get(2).metadata().keySet(), not(hasItem(TEMPORARY_BLOB_HEADER)));

    assertThat(blob.getHeaders(), is(headers));

  }

  /**
   * Tests that makeBlobPermanent throws exception if temp blob header is passed in.
   */
  @Test
  void makeBlobPermanentThrowsExceptionIfTempBlobHeaderIsPassedIn() throws Exception {
    blobStore.init(config);
    blobStore.doStart();

    Map<String, String> headers =
            Map.of(CREATED_BY_HEADER, "test", CREATED_BY_IP_HEADER, "127.0.0.1", BLOB_NAME_HEADER, "temp",
                    TEMPORARY_BLOB_HEADER, "");
    Blob blob = blobStore.create(new ByteArrayInputStream("hello world".getBytes()), headers);

    assertThrows(IllegalArgumentException.class, () -> blobStore.makeBlobPermanent(blob.getId(), headers)); // NOSONAR
  }

  /**
   * Tests that deleteIfTemp deletes blob when temp blob header is present.
   */
  @Test
  void deleteIfTempDeletesBlobWhenTempBlobHeaderIsPresent() throws Exception {
    blobStore.init(config);
    blobStore.doStart();

    Map<String, String> headers =
            Map.of(CREATED_BY_HEADER, "test", CREATED_BY_IP_HEADER, "127.0.0.1", BLOB_NAME_HEADER, "temp",
                    TEMPORARY_BLOB_HEADER, "");
    Blob blob = blobStore.create(new ByteArrayInputStream("hello world".getBytes()), headers);

    assertThat(blob, is(notNullValue()));

    DeleteObjectsResponse deleteObjectsResponse = mock(DeleteObjectsResponse.class);
    when(deleteObjectsResponse.deleted()).thenReturn(List.of(software.amazon.awssdk.services.s3.model.DeletedObject.builder().build(),
            software.amazon.awssdk.services.s3.model.DeletedObject.builder().build()));
    when(s3.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(deleteObjectsResponse);

    boolean deleted = blobStore.deleteIfTemp(blob.getId());
    assertThat(deleted, is(true));

    Blob retrievedBlob = blobStore.get(blob.getId());
    assertThat(retrievedBlob, is(nullValue()));
    verify(s3, never()).deleteObjects(any(DeleteObjectsRequest.class));
  }

  /**
   * Tests that deleteIfTemp does not delete blob when temp blob header is absent.
   */
  @Test
  void deleteIfTempDoesNotDeleteBlobWhenTempBlobHeaderIsAbsent() throws Exception {
    blobStore.init(config);
    blobStore.doStart();

    Map<String, String> headers =
            Map.of(CREATED_BY_HEADER, "test", CREATED_BY_IP_HEADER, "127.0.0.1", BLOB_NAME_HEADER, "file.txt",
                    CONTENT_TYPE_HEADER, "text/plain", REPO_NAME_HEADER, "a repository");
    Blob blob = blobStore.create(new ByteArrayInputStream("hello world".getBytes()), headers);

    assertThat(blob, is(notNullValue()));

    boolean deleted = blobStore.deleteIfTemp(blob.getId());
    assertThat(deleted, is(false));
    Blob retrievedBlob = blobStore.get(blob.getId());
    assertThat(retrievedBlob, is(notNullValue()));
    verify(s3, never()).deleteObjects(any(DeleteObjectsRequest.class));
  }

  /**
   * Tests concurrent operations with virtual threads.
   */
  @Test
  @org.junit.jupiter.api.Tag("VirtualThreadTestGroup")
  void concurrentOperationsWithVirtualThreads() throws Exception {
    MockBlobStoreConfiguration cfg = new MockBlobStoreConfiguration();
    cfg.setAttributes(new HashMap<>(Map.of("s3", new HashMap<>(Map.of("bucket", "mybucket")))));
    BlobId blobId = new BlobId("test");
    HeadObjectRequest headRequest = HeadObjectRequest.builder()
            .bucket("mybucket")
            .key("prefix/" + propertiesLocation(blobId))
            .build();

    when(s3.headObject(eq(headRequest)))
            .thenReturn(HeadObjectResponse.builder().build());
    ResponseInputStream<GetObjectResponse>  attributesS3Object = mockS3Object(attributesContents);

    software.amazon.awssdk.services.s3.model.GetObjectRequest getAttributesRequest = software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
            .bucket("mybucket")
            .key(propertiesLocation(blobId))
            .build();

    when(s3.getObject(getAttributesRequest)).thenReturn(attributesS3Object);

    ResponseInputStream<GetObjectResponse>  contentS3Object = mockS3Object("hello world");
    software.amazon.awssdk.services.s3.model.GetObjectRequest bytesContentS3Request = software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
            .bucket("mybucket")
            .key(bytesLocation(blobId))
            .build();
    when(s3.getObject(bytesContentS3Request)).thenReturn(contentS3Object);

    blobStore.init(cfg);
    blobStore.doStart();

    // Use virtual threads for concurrent operations
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);

    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            Blob blob = blobStore.get(blobId);
            if (blob == null) {
              errorCount.incrementAndGet();
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      latch.await(30, java.util.concurrent.TimeUnit.SECONDS);

      // Verify results
      assertThat(errorCount.get(), is(0));
    } finally {
      executor.shutdown();
    }
  }
}