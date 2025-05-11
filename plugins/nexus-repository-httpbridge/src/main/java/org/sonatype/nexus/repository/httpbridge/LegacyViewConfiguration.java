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
package org.sonatype.nexus.repository.httpbridge;

import java.util.regex.Pattern;

/**
 * Legacy format-specific view configuration.
 * <p>
 * Implementations of this interface define how legacy URL formats are matched and processed.
 * When implementing this interface, consider using Java 21 features such as Pattern Matching
 * for switch expressions in your pattern handling logic for more concise and readable code.
 *
 * @since 3.7
 */
public interface LegacyViewConfiguration
{
  /**
   * Returns the repository format identifier that this configuration applies to.
   * 
   * @return the format identifier as a String
   */
  String getFormat();

  /**
   * Returns the pattern used to match against incoming request URLs.
   * <p>
   * The pattern is used to determine if a request should be handled by this legacy view.
   * Implementations can leverage Java 21's enhanced regex capabilities when constructing patterns.
   * 
   * @return the regex pattern for matching request URLs
   */
  Pattern getRequestPattern();
}