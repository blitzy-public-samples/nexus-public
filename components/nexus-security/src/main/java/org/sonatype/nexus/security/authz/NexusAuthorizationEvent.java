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
package org.sonatype.nexus.security.authz;

import java.util.Date;

import org.sonatype.nexus.security.ClientInfo;

/**
 * Event fired in case of an authorization is tried against given resource.
 * <p>
 * This class uses Java 21 features like Record Patterns and String Templates for improved data handling and formatting.
 */
public class NexusAuthorizationEvent
{
  private final ClientInfo clientInfo;

  private final ResourceInfo resourceInfo;

  private final boolean successful;

  private final Date date;

  /**
   * Creates a new authorization event with the given client and resource information.
   * 
   * @param info the client information
   * @param resInfo the resource information
   * @param successful whether the authorization was successful
   */
  public NexusAuthorizationEvent(final ClientInfo info, final ResourceInfo resInfo, final boolean successful) {
    this.clientInfo = info;
    this.resourceInfo = resInfo;
    this.successful = successful;
    this.date = new Date();
  }

  /**
   * Returns the client information for this event.
   */
  public ClientInfo getClientInfo() {
    return clientInfo;
  }

  /**
   * Returns the resource information for this event.
   */
  public ResourceInfo getResourceInfo() {
    return resourceInfo;
  }

  /**
   * Returns whether the authorization was successful.
   */
  public boolean isSuccessful() {
    return successful;
  }

  /**
   * Returns the date when this event occurred.
   */
  public Date getEventDate() {
    return date;
  }
  
  /**
   * Returns a formatted string representation of this event using String Templates.
   * 
   * @return a formatted string with event details
   */
  @Override
  public String toString() {
    return STR."NexusAuthorizationEvent{successful=\{successful}, date=\{date}, \{formatClientInfo()}, \{formatResourceInfo()}}";
  }
  
  /**
   * Formats the client information using String Templates and Record Patterns.
   * 
   * @return a formatted string with client information
   */
  private String formatClientInfo() {
    if (clientInfo instanceof ClientInfo client) {
      String userId = client.getUserid();
      String remoteIP = client.getRemoteIP();
      String userAgent = client.getUserAgent();
      String path = client.getPath();
      
      return STR."client=[userId=\{userId}, remoteIP=\{remoteIP}, userAgent=\{userAgent}, path=\{path}]";
    }
    return "client=null";
  }
  
  /**
   * Formats the resource information using String Templates and Record Patterns.
   * 
   * @return a formatted string with resource information
   */
  private String formatResourceInfo() {
    if (resourceInfo instanceof ResourceInfo resource) {
      String protocol = resource.getAccessProtocol();
      String method = resource.getAccessMethod();
      String action = resource.getAction();
      String uri = resource.getAccessedUri();
      
      return STR."resource=[protocol=\{protocol}, method=\{method}, action=\{action}, uri=\{uri}]";
    }
    return "resource=null";
  }
  
  /**
   * Creates a formatted log message for this authorization event using String Templates.
   * 
   * @return a formatted log message
   */
  public String toLogMessage() {
    String result = successful ? "ALLOWED" : "DENIED";
    String userId = clientInfo != null ? clientInfo.getUserid() : "unknown";
    String action = resourceInfo != null ? resourceInfo.getAction() : "unknown";
    String uri = resourceInfo != null ? resourceInfo.getAccessedUri() : "unknown";
    
    return STR."Authorization \{result} for user '\{userId}' performing '\{action}' on '\{uri}'";
  }
}