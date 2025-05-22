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

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Indicates that 'Support Zip' should be created in some node.
 * Contains the 'request' object which holds all properties for 'support zip' creation and nodeID of target node
 */
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
   * Process the request using pattern matching to extract relevant information.
   * This demonstrates the use of pattern matching with instanceof for non-record classes.
   *
   * @param obj The object to process
   * @return A description of the request if it's a SupportZipGeneratorRequest, or null otherwise
   */
  public String processRequestWithPatternMatching(Object obj) {
    if (obj instanceof SupportZipGeneratorRequest req) {
      return STR."Processing support zip request for host \{req.getHostname()} with system info: \{req.isSystemInformation()}";
    }
    return null;
  }

  @Override
  public String toString() {
    return STR."SupportZipGenerationEvent{recipientNodeId='\{recipientNodeId}', request=\{request}}";
  }
}