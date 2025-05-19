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

import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaSupport;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;

// Uncomment if you need to use String Templates in production code
// import static java.lang.StringTemplate.STR;

import static org.sonatype.nexus.blobstore.quota.BlobStoreQuotaSupport.LIMIT_KEY;
import static org.sonatype.nexus.blobstore.quota.BlobStoreQuotaSupport.ROOT_KEY;
import static org.sonatype.nexus.blobstore.quota.BlobStoreQuotaSupport.TYPE_KEY;

/**
 * Base class for BlobStore API models.
 * Updated for Java 21 compatibility with record patterns and Jackson 2.16.1.
 * 
 * @since 3.19
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize(as = BlobStoreApiModel.class)
public abstract class BlobStoreApiModel
{
  @Schema(description = "Settings to control the soft quota")
  @JsonProperty("softQuota")
  private BlobStoreApiSoftQuota softQuota;

  public BlobStoreApiModel() {
  }

  public BlobStoreApiModel(BlobStoreConfiguration configuration) {
    softQuota = createSoftQuota(configuration);
  }

  public BlobStoreApiSoftQuota getSoftQuota() {
    return softQuota;
  }

  public void setSoftQuota(final BlobStoreApiSoftQuota softQuota) {
    this.softQuota = softQuota;
  }

  public BlobStoreConfiguration toBlobStoreConfiguration(final BlobStoreConfiguration configuration) {
    setSoftQuotaAttributes(configuration);
    return configuration;
  }

  private void setSoftQuotaAttributes(BlobStoreConfiguration configuration) {
    if (softQuota == null) {
      return;
    }

    // Using Java 21 Record Pattern to extract values from the softQuota record
    if (softQuota instanceof BlobStoreApiSoftQuota(var type, var limit)) {
      configuration.attributes(ROOT_KEY).set(TYPE_KEY, type);
      if (limit == null) {
        configuration.attributes(ROOT_KEY).set(LIMIT_KEY, -1L);
      }
      else {
        configuration.attributes(ROOT_KEY).set(LIMIT_KEY, limit);
      }
    }
  }

  private BlobStoreApiSoftQuota createSoftQuota(BlobStoreConfiguration configuration) {
    if (configuration.attributes(BlobStoreQuotaSupport.ROOT_KEY).isEmpty()) {
      return null;
    }

    // Using Java 21 String Templates for better readability in log messages if needed
    String type = BlobStoreQuotaSupport.getType(configuration);
    Long limit = null;
    
    try {
      limit = BlobStoreQuotaSupport.getLimit(configuration);
      
      // Example of using String Templates for logging (if this class had a logger)
      // String logMessage = STR."Creating soft quota with type: \{type} and limit: \{limit} bytes";
      // log.debug(logMessage);
      
    } catch (IllegalArgumentException e) {
      // Example of using String Templates for error messages
      // String errorMessage = STR."Failed to get limit for quota type \{type} in configuration \{configuration.getName()}";
      // log.warn(errorMessage, e);
      // Limit not found in configuration, will use null
    }
    
    // Create a new record instance directly with the extracted values
    return new BlobStoreApiSoftQuota(type, limit);
  }
}