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

import io.swagger.v3.oas.annotations.media.Schema;

import static java.lang.StringTemplate.STR;
import static org.sonatype.nexus.blobstore.quota.BlobStoreQuotaSupport.LIMIT_KEY;
import static org.sonatype.nexus.blobstore.quota.BlobStoreQuotaSupport.ROOT_KEY;
import static org.sonatype.nexus.blobstore.quota.BlobStoreQuotaSupport.TYPE_KEY;

/**
 * @since 3.19
 */
public abstract class BlobStoreApiModel
{
  @Schema(description = "Settings to control the soft quota")
  private BlobStoreApiSoftQuota softQuota;

  public BlobStoreApiModel() {
  }

  public BlobStoreApiModel(BlobStoreConfiguration configuration) {
    if (configuration == null) {
      throw new IllegalArgumentException(STR."Configuration cannot be null when creating \{this.getClass().getSimpleName()}");
    }
    softQuota = createSoftQuota(configuration);
  }

  public BlobStoreApiSoftQuota getSoftQuota() {
    return softQuota;
  }

  public void setSoftQuota(final BlobStoreApiSoftQuota softQuota) {
    this.softQuota = softQuota;
  }

  public BlobStoreConfiguration toBlobStoreConfiguration(final BlobStoreConfiguration configuration) {
    if (configuration == null) {
      throw new IllegalArgumentException(STR."Configuration cannot be null when converting to BlobStoreConfiguration");
    }
    setSoftQuotaAttributes(configuration);
    return configuration;
  }

  private void setSoftQuotaAttributes(BlobStoreConfiguration configuration) {
    if (configuration == null) {
      throw new IllegalArgumentException(STR."Configuration cannot be null when setting soft quota attributes");
    }
    
    if (softQuota == null) {
      return;
    }

    String quotaType = softQuota.getType();
    if (quotaType == null) {
      throw new IllegalStateException(STR."Soft quota type cannot be null for \{this.getClass().getSimpleName()}");
    }
    
    configuration.attributes(ROOT_KEY).set(TYPE_KEY, quotaType);
    if (softQuota.getLimit() == null) {
      configuration.attributes(ROOT_KEY).set(LIMIT_KEY, -1L);
    }
    else {
      configuration.attributes(ROOT_KEY).set(LIMIT_KEY, softQuota.getLimit());
    }
  }

  private BlobStoreApiSoftQuota createSoftQuota(BlobStoreConfiguration configuration) {
    if (configuration == null) {
      throw new IllegalArgumentException(STR."Configuration cannot be null when creating soft quota");
    }
    
    if (configuration.attributes(BlobStoreQuotaSupport.ROOT_KEY).isEmpty()) {
      return null;
    }

    try {
      BlobStoreApiSoftQuota newSoftQuota = new BlobStoreApiSoftQuota();
      String type = BlobStoreQuotaSupport.getType(configuration);
      if (type == null) {
        throw new IllegalStateException(STR."Quota type is missing in configuration for \{configuration.getName()}");
      }
      newSoftQuota.setType(type);
      newSoftQuota.setLimit(BlobStoreQuotaSupport.getLimit(configuration));
      return newSoftQuota;
    } catch (Exception e) {
      throw new IllegalStateException(STR."Failed to create soft quota from configuration \{configuration.getName()}: \{e.getMessage()}", e);
    }
  }
}