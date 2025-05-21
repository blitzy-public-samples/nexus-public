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

package org.sonatype.nexus.audit;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Event fired after {@link AuditData} had been recorded.
 * <p>
 * This event is designed to be efficiently processed by Virtual Threads in Java 21.
 * It is immutable and thread-safe, making it suitable for concurrent processing
 * in high-throughput event handling scenarios.
 *
 * @since 3.1
 */
public class AuditDataRecordedEvent
{
  private final AuditData data;

  /**
   * Creates a new audit data recorded event.
   * 
   * @param data the audit data that was recorded (not null)
   */
  public AuditDataRecordedEvent(final AuditData data) {
    this.data = checkNotNull(data);
  }

  /**
   * Returns the recorded audit data.
   * 
   * @return the immutable audit data
   */
  public AuditData getData() {
    return data;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{" +
        "data=" + data +
        '}';
  }
}