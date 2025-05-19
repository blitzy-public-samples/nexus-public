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

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Data transfer object for blob store connection information.
 * <p>
 * Implemented as a Java 21 record for improved memory efficiency and data handling.
 * Compatible with Jackson 2.16.1 for JSON serialization/deserialization.
 * </p>
 */
public record BlobStoreConnectionXO(
    @JsonProperty("name") String name,
    @JsonProperty("type") String type,
    @JsonProperty("attributes") Map<String, Map<String, Object>> attributes)
{
  /**
   * Constructor with explicit JsonCreator annotation to ensure proper deserialization with Jackson 2.16.1.
   *
   * @param name The name of the blob store connection
   * @param type The type of the blob store connection
   * @param attributes The attributes of the blob store connection
   */
  @JsonCreator
  public BlobStoreConnectionXO {
    // Validate inputs if needed
    if (name == null) {
      throw new IllegalArgumentException("name cannot be null");
    }
    if (type == null) {
      throw new IllegalArgumentException("type cannot be null");
    }
    // attributes can be null
  }
}
