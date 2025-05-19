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
package org.sonatype.nexus.blobstore.rest;

import org.sonatype.nexus.rest.WebApplicationMessageException;

import static java.lang.StringTemplate.STR;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;

/**
 * Utility class for BlobStore REST resources exception handling.
 * 
 * @since 3.19
 */
public class BlobStoreResourceUtil
{
  /**
   * Throws {@link BAD_REQUEST} exception in case when BlobStore manager could not perform operation (for example,
   * blobstore is in use). Optimized for Virtual Thread context to ensure proper exception propagation.
   *
   * @param message error message
   * @throws WebApplicationMessageException with BAD_REQUEST status and formatted error message
   */
  public static void throwBlobStoreBadRequestException(final String message) throws WebApplicationMessageException {
    // Using String Templates for improved readability and performance
    throw new WebApplicationMessageException(
        BAD_REQUEST,
        STR."\"\{message}\"",
        APPLICATION_JSON);
  }

  /**
   * Returns a {@link NOT_FOUND} WebApplicationMessageException when blobstore is not found.
   * Creates a structured error message with detailed information for better diagnostics.
   *
   * @param blobStoreType The type of the blobstore (e.g.: File, Group, S3, Azure Cloud Storage).
   * @param blobStoreName The name of the blobstore.
   * @return {@link WebApplicationMessageException} with NOT_FOUND status and formatted error message.
   */
  public static WebApplicationMessageException createBlobStoreNotFoundException(
      final String blobStoreType,
      final String blobStoreName)
  {
    // Using String Templates instead of String.format for improved performance and readability
    return new WebApplicationMessageException(
        NOT_FOUND,
        STR."Unable to find \{blobStoreType} '\{blobStoreName}' blobstore",
        APPLICATION_JSON);
  }

  /**
   * Throws a {@link NOT_FOUND} WebApplicationMessageException when blobstore is not found.
   * Optimized for Virtual Thread context to ensure proper exception propagation.
   *
   * @param blobStoreType The type of the blobstore (e.g.: File, Group, S3, Azure Cloud Storage).
   * @param blobStoreName The name of the blobstore.
   * @throws WebApplicationMessageException with NOT_FOUND status and formatted error message.
   */
  public static void throwCreateBlobStoreNotFoundException(
      final String blobStoreType,
      final String blobStoreName)
  {
    throw createBlobStoreNotFoundException(blobStoreType, blobStoreName);
  }
}
