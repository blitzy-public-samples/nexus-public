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
package org.sonatype.nexus.audit.internal;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.Executors;

import org.sonatype.nexus.audit.AuditData;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Simple DTO for writing audit data to log file in JSON format.
 * 
 * Updated for Java 21 compatibility with Record Pattern support and
 * improved thread handling awareness.
 *
 * @since 3.16
 */
@JsonInclude(Include.NON_NULL)
public record AuditDTO(
    String timestamp,
    String nodeId,
    String initiator,
    String domain,
    String type,
    String context,
    String thread,
    Map<String, Object> attributes
) {
  /**
   * Date formatter for consistent timestamp formatting.
   * Thread-safe and reusable across instances.
   */
  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSSZ");

  /**
   * Jackson ObjectMapper configured with Java 21 compatible modules.
   * Updated to support Jackson 2.16.1 features.
   */
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
      .registerModule(new Jdk8Module())
      .registerModule(new JavaTimeModule());

  /**
   * Default constructor for deserialization.
   */
  public AuditDTO {
    // Record compact constructor for validation if needed
    // No validation currently required
  }

  /**
   * Constructs an AuditDTO from AuditData.
   * Uses Java 21 features for thread context awareness.
   *
   * @param auditData the audit data to convert
   */
  public AuditDTO(final AuditData auditData) {
    this(
        auditData.getTimestamp() != null
            ? auditData.getTimestamp().toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime().format(DATE_FORMAT)
            : null,
        auditData.getNodeId(),
        auditData.getInitiator(),
        auditData.getDomain(),
        auditData.getType(),
        auditData.getContext(),
        // Enhanced thread name detection for both platform and virtual threads
        Thread.currentThread().isVirtual() 
            ? "virtual-" + Thread.currentThread().getName()
            : Thread.currentThread().getName(),
        auditData.getAttributes()
    );
  }

  /**
   * Returns a string representation of this DTO using Jackson serialization.
   * 
   * @return JSON string representation of this DTO
   */
  @Override
  public String toString() {
    return OBJECT_MAPPER.valueToTree(this).toString();
  }
}