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
package org.sonatype.nexus.coreui.internal.content;

import org.sonatype.nexus.repository.security.RepositorySelector;
import org.sonatype.nexus.selector.CselSelector;

/**
 * Request object for content selector preview operations.
 * 
 * Implemented as an immutable record with default values for repository and type fields.
 * This implementation leverages Java 21 record patterns for improved immutability and concise syntax.
 *
 * @since 3.29
 */
public record SelectorPreviewRequest(
    String repository,
    String type,
    String expression
) {
  /**
   * Default constructor with all fields.
   * Provides default values for repository and type when not explicitly specified.
   */
  public SelectorPreviewRequest {
    // Apply defaults if null values are provided
    if (repository == null) {
      repository = RepositorySelector.ALL;
    }
    
    if (type == null) {
      type = CselSelector.TYPE;
    }
  }
  
  /**
   * Convenience constructor that only requires the expression.
   * Uses default values for repository and type.
   *
   * @param expression the selector expression
   */
  public SelectorPreviewRequest(String expression) {
    this(RepositorySelector.ALL, CselSelector.TYPE, expression);
  }
}
