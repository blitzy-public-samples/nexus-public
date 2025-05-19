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

import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaResult;

import jakarta.validation.constraints.NotEmpty;

import static java.lang.StringTemplate.STR;

/**
 * Data transfer object for blob store quota results.
 * 
 * @since 3.14
 */
public class BlobStoreQuotaResultXO
{
  private boolean isViolation;

  @NotEmpty
  private String message;

  @NotEmpty
  private String blobStoreName;

  public boolean getIsViolation() {
    return isViolation;
  }

  public void setIsViolation(final boolean isViolation) {
    this.isViolation = isViolation;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(final String message) {
    this.message = message;
  }

  public String getBlobStoreName() {
    return blobStoreName;
  }

  public void setBlobStoreName(String blobStoreName) {
    this.blobStoreName = blobStoreName;
  }

  /**
   * Creates a quota result XO from a BlobStoreQuotaResult.
   * 
   * @param result the quota result to convert
   * @return a new BlobStoreQuotaResultXO with data from the result
   */
  static BlobStoreQuotaResultXO asQuotaXO(final BlobStoreQuotaResult result) {
    BlobStoreQuotaResultXO blobStoreQuotaResultXO = new BlobStoreQuotaResultXO();
    blobStoreQuotaResultXO.setIsViolation(result.isViolation());
    blobStoreQuotaResultXO.setMessage(result.getMessage());
    blobStoreQuotaResultXO.setBlobStoreName(result.getBlobStoreName());
    return blobStoreQuotaResultXO;
  }

  /**
   * Creates a quota result XO for a blob store with no quota.
   * 
   * @param blobStoreName the name of the blob store
   * @return a new BlobStoreQuotaResultXO indicating no quota
   */
  static BlobStoreQuotaResultXO asNoQuotaXO(final String blobStoreName) {
    BlobStoreQuotaResultXO blobStoreQuotaResultXO = new BlobStoreQuotaResultXO();
    blobStoreQuotaResultXO.setIsViolation(false);
    // Using String Templates for improved readability and performance
    blobStoreQuotaResultXO.setMessage(STR."Blob store {blobStoreName} has no quota");
    blobStoreQuotaResultXO.setBlobStoreName(blobStoreName);
    return blobStoreQuotaResultXO;
  }
}