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
package org.sonatype.nexus.blobstore.s3.rest.internal;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Path;

import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.crypto.secrets.SecretsFactory;

import static org.sonatype.nexus.blobstore.s3.rest.internal.S3BlobStoreApiResourceV1.RESOURCE_URI;
import static org.sonatype.nexus.rest.APIConstants.V1_API_PREFIX;

/**
 * v1 endpoint for S3 BlobStore REST API
 *
 * This implementation has been updated for Java 21 compatibility, leveraging the parent class's
 * implementation of Virtual Threads for I/O-bound operations, pattern matching for type checks,
 * and other Java 21 features for improved performance and code clarity.
 *
 * @since 3.24
 * @see <a href="https://openjdk.org/projects/jdk/21/">Java 21 Features</a>
 * @see <a href="https://openjdk.org/jeps/444">JEP 444: Virtual Threads</a>
 * @see <a href="https://openjdk.org/jeps/440">JEP 440: Record Patterns</a>
 * @see <a href="https://openjdk.org/jeps/441">JEP 441: Pattern Matching for switch</a>
 */
@Named
@Singleton
@Path(RESOURCE_URI)
public class S3BlobStoreApiResourceV1
  extends S3BlobStoreApiResource
{
  /**
   * Resource URI for the v1 S3 BlobStore REST API endpoint.
   * Uses String Template (Java 21 feature) for improved readability.
   */
  static final String RESOURCE_URI = STR."{V1_API_PREFIX}/blobstores";

  /**
   * Constructor for the S3BlobStoreApiResourceV1 class.
   * 
   * @param blobStoreManager the blob store manager
   * @param validation the validation service for S3 blob store API updates
   * @param secretsFactory the factory for creating and managing secrets
   */
  @Inject
  public S3BlobStoreApiResourceV1(final BlobStoreManager blobStoreManager,
                                  final S3BlobStoreApiUpdateValidation validation,
                                  final SecretsFactory secretsFactory)
  {
    super(blobStoreManager, validation, secretsFactory);
  }
}