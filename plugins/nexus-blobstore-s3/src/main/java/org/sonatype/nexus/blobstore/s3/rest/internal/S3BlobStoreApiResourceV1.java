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
 * This class has been updated for Java 21 compatibility, leveraging Jakarta EE 10 APIs
 * and supporting Virtual Threads for improved I/O performance in the parent class.
 *
 * @since 3.24
 */
/*
 * OSGi Bundle Metadata for Java 21 compatibility:
 * 
 * Bundle-RequiredExecutionEnvironment: JavaSE-21
 * Import-Package: jakarta.inject;version="[2.0,3)",
 *               jakarta.ws.rs;version="[3.0,4)",
 *               org.sonatype.nexus.blobstore.api;version="[3.0,4)",
 *               org.sonatype.nexus.crypto.secrets;version="[3.0,4)"
 */
@Named
@Singleton
@Path(RESOURCE_URI)
public class S3BlobStoreApiResourceV1
  extends S3BlobStoreApiResource
{
  static final String RESOURCE_URI = V1_API_PREFIX + "/blobstores";

  /**
   * Constructor for S3BlobStoreApiResourceV1.
   * 
   * @param blobStoreManager the blob store manager
   * @param validation the validation service for S3 blob store API updates
   * @param secretsFactory the factory for handling secrets
   */
  @Inject
  public S3BlobStoreApiResourceV1(final BlobStoreManager blobStoreManager,
                                  final S3BlobStoreApiUpdateValidation validation,
                                  final SecretsFactory secretsFactory)
  {
    super(blobStoreManager, validation, secretsFactory);
  }
}
