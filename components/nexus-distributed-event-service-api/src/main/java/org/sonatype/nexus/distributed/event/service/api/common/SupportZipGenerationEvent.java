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
package org.sonatype.nexus.distributed.event.service.api.common;

import org.sonatype.nexus.common.log.SupportZipGeneratorRequest;
import org.sonatype.nexus.distributed.event.service.api.EventType;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Indicates that 'Support Zip' should be created in some node.
 * Contains the 'request' object which holds all properties for 'support zip' creation and nodeID of target node
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SupportZipGenerationEvent
    extends DistributedEventSupport
{
  public static final String NAME = "SupportZipGenerationEvent";

  private final String recipientNodeId;

  private final SupportZipGeneratorRequest request;

  @JsonCreator
  public SupportZipGenerationEvent(
      @JsonProperty("recipientNodeId") final String recipientNodeId,
      @JsonProperty("request") final SupportZipGeneratorRequest request)
  {
    super(EventType.CREATED);
    this.recipientNodeId = checkNotNull(recipientNodeId);
    this.request = checkNotNull(request);
  }

  public String getRecipientNodeId() {
    return recipientNodeId;
  }

  public SupportZipGeneratorRequest getRequest() {
    return request;
  }
  
  /**
   * Processes the request using Record Patterns to extract and validate properties.
   * This method demonstrates the use of Java 21 Pattern Matching for instanceof.
   *
   * @param obj The object to process, expected to be a SupportZipGeneratorRequest
   * @return A description of the request or error message
   */
  public String processRequestWithPatterns(Object obj) {
    if (obj instanceof SupportZipGeneratorRequest request) {
      // Using pattern matching to check properties of the request
      boolean includesLogs = request.isLog();
      boolean includesAuditLogs = request.isAuditLog();
      boolean includesTaskLogs = request.isTaskLog();
      
      // Using String Templates for the result message
      return STR."Request includes: logs=\{includesLogs}, audit logs=\{includesAuditLogs}, task logs=\{includesTaskLogs}";
    }
    return "Not a valid SupportZipGeneratorRequest";
  }

  @Override
  public String toString() {
    // Using Java 21 String Templates for improved readability and performance
    return STR."SupportZipGenerationEvent{recipientNodeId='\{recipientNodeId}', request=\{request}}";
  }
}
