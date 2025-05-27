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
package org.sonatype.nexus.repository.httpbridge.internal.describe;

/**
 * Renders {@link Description} into HTML or JSON.
 *
 * <p>This interface is designed to be compatible with Java 21 Virtual Threads.
 * Implementations may leverage Virtual Threads for improved performance,
 * especially for I/O-bound rendering operations.</p>
 *
 * @since 3.0
 */
public interface DescriptionRenderer
{
  /**
   * Renders the description as HTML.
   * 
   * <p>This method is suitable for execution in a Virtual Thread when
   * the implementation performs I/O operations.</p>
   *
   * @param description the description to render
   * @return the HTML representation
   */
  String renderHtml(Description description);

  /**
   * Renders the description as JSON.
   * 
   * <p>This method is suitable for execution in a Virtual Thread when
   * the implementation performs I/O operations.</p>
   *
   * @param description the description to render
   * @return the JSON representation
   */
  String renderJson(Description description);
}