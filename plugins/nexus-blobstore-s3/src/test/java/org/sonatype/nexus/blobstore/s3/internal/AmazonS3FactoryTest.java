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

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.sonatype.nexus.blobstore.MockBlobStoreConfiguration;
import org.sonatype.nexus.crypto.secrets.Secret;
import org.sonatype.nexus.crypto.secrets.SecretsFactory;

import com.amazonaws.services.s3.AmazonS3;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.ACCESS_KEY_ID_KEY;
import static org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.SECRET_ACCESS_KEY_KEY;
import static org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.SESSION_TOKEN_KEY;

/**
 * Tests for {@link AmazonS3Factory} that verify S3 client configuration behavior.
 * 
 * @since 3.19
 */
@ExtendWith(MockitoExtension.class)
class AmazonS3FactoryTest
{
  @Mock
  private SecretsFactory secretsFactory;

  @InjectMocks
  private AmazonS3Factory amazonS3Factory = new AmazonS3Factory(-1, null, false, "", null);

  private MockBlobStoreConfiguration config = new MockBlobStoreConfiguration();

  @BeforeEach
  void setUp() {
    Map<String, Object> s3Map = new HashMap<>();
    s3Map.put("bucket", "mybucket");
    Map<String, Map<String, Object>> attributes = new HashMap<>();
    attributes.put("s3", s3Map);
    config.setAttributes(attributes);
  }

  @Test
  void endpointIsSetWhenProvidedInConfig() throws Exception {
    config.getAttributes().get("s3").put("endpoint", "http://localhost/");
    config.getAttributes().get("s3").put("region", "us-west-2");

    AmazonS3 s3 = amazonS3Factory.create(config);
    URI endpoint = (URI) MethodUtils.invokeMethod(s3, true, "getEndpoint");
    assertEquals(new URI("http://localhost/"), endpoint);
  }

  @Test
  void endpointIsSetWhenProvidedInConfigWithDefaultRegion() throws Exception {
    config.getAttributes().get("s3").put("endpoint", "http://localhost/");

    AmazonS3 s3 = amazonS3Factory.create(config);
    URI endpoint = (URI) MethodUtils.invokeMethod(s3, true, "getEndpoint");
    assertEquals(new URI("http://localhost/"), endpoint);
  }

  @Test
  void signingAlgorithmIsSetWhenProvidedInConfig() throws Exception {
    config.getAttributes().get("s3").put("signertype", "AWSS3V4SignerType");
    config.getAttributes().get("s3").put("region", "us-west-2");

    AmazonS3 s3 = amazonS3Factory.create(config);
    assertEquals("AWSS3V4SignerType", getSignerOverride(s3));
  }

  @Test
  void nullSignerDoesNotOverrideConfigValue() throws Exception {
    testSignerOverrideWith(null);
  }

  @Test
  void emptySignerDoesNotOverrideConfigValue() throws Exception {
    testSignerOverrideWith("");
  }

  @Test
  void defaultSignerDoesNotOverrideConfigValue() throws Exception {
    testSignerOverrideWith("DEFAULT");
  }

  private void testSignerOverrideWith(String signer) throws Exception {
    config.getAttributes().get("s3").put("region", "us-west-2");
    config.getAttributes().get("s3").put("signertype", signer);

    AmazonS3 s3 = amazonS3Factory.create(config);
    assertNull(getSignerOverride(s3));
  }

  @Test
  void pathStyleAccessIsSetWhenProvidedInConfig() {
    config.getAttributes().get("s3").put("region", "us-west-2");
    config.getAttributes().get("s3").put("forcepathstyle", "true");

    AmazonS3 s3 = amazonS3Factory.create(config);
    assertEquals("/bucket/key", s3.getUrl("bucket", "key").getPath());
  }

  @Test
  void shouldDecryptTheSecretAccessKeyAndSessionToken() {
    Secret accessKeyMock = mock(Secret.class);
    Secret sessionTokenMock = mock(Secret.class);
    when(secretsFactory.from("_1")).thenReturn(accessKeyMock);
    when(secretsFactory.from("_2")).thenReturn(sessionTokenMock);
    when(accessKeyMock.decrypt()).thenReturn("secretAccessKey".toCharArray());
    when(sessionTokenMock.decrypt()).thenReturn("sessionToken".toCharArray());

    config.getAttributes().get("s3").put(ACCESS_KEY_ID_KEY, "accessKeyId");
    config.getAttributes().get("s3").put(SECRET_ACCESS_KEY_KEY, "_1");
    config.getAttributes().get("s3").put(SESSION_TOKEN_KEY, "_2");
    config.getAttributes().get("s3").put("region", "us-west-2");

    amazonS3Factory.create(config);

    verify(secretsFactory).from("_1");
    verify(secretsFactory).from("_2");
    verify(accessKeyMock).decrypt();
    verify(sessionTokenMock).decrypt();
  }

  /**
   * Helper method to extract the signer override from an AmazonS3 client instance.
   */
  private String getSignerOverride(AmazonS3 s3) throws Exception {
    Object clientConfiguration = MethodUtils.invokeMethod(s3, true, "getClientConfiguration");
    return (String) MethodUtils.invokeMethod(clientConfiguration, true, "getSignerOverride");
  }
}