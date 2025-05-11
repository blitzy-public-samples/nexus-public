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

import org.sonatype.nexus.audit.AuditData;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Simple DTO for writing audit data to log file in JSON format.
 * Implemented as a Java 21 Record for improved data handling and immutability.
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
   * DateTimeFormatter for consistent timestamp formatting.
   * Pattern: yyyy-MM-dd HH:mm:ss,SSSZ
   */
  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSSZ");

  /**
   * ObjectMapper configured with Java 21 compatible modules for JSON serialization.
   * Uses Jdk8Module and JavaTimeModule for handling modern Java types.
   */
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
      .registerModule(new Jdk8Module())
      .registerModule(new JavaTimeModule());

  /**
   * No-args constructor for deserialization.
   */
  public AuditDTO() {
    this(null, null, null, null, null, null, null, null);
  }

  /**
   * Constructs an AuditDTO from AuditData.
   * Formats timestamp using the system default timezone and captures thread information.
   * Compatible with Java 21 Virtual Threads.
   *
   * @param auditData The audit data to convert
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
        // Capture thread information in a way that's compatible with both platform and virtual threads
        Thread.currentThread().getName() + " (" + Thread.currentThread().threadId() + ")",
        auditData.getAttributes()
    );
  }

  /**
   * Returns a JSON string representation of this record.
   * Uses the configured ObjectMapper to convert the record to a JSON tree and then to a string.
   *
   * @return JSON string representation
   */
  @Override
  public String toString() {
    return OBJECT_MAPPER.valueToTree(this).toString();
  }
}