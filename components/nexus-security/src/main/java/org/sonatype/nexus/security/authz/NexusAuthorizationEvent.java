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
import java.util.concurrent.CompletableFuture;

import org.sonatype.nexus.security.ClientInfo;

/**
 * Event fired in case of an authorization is tried against given resource.
 * <p>
 * Enhanced with Java 21 features:
 * - Record Patterns for improved data handling with ClientInfo and ResourceInfo
 * - String Templates for more readable event formatting and logging
 * - Optimized event creation for potential concurrent processing
 */
public class NexusAuthorizationEvent
{
  private final ClientInfo clientInfo;

  private final ResourceInfo resourceInfo;

  private final boolean successful;

  private final Date date;

  /**
   * Creates a new authorization event.
   *
   * @param info Client information
   * @param resInfo Resource information
   * @param successful Whether the authorization was successful
   */
  public NexusAuthorizationEvent(final ClientInfo info, final ResourceInfo resInfo, final boolean successful) {
    this.clientInfo = info;
    this.resourceInfo = resInfo;
    this.successful = successful;
    this.date = new Date();
  }

  /**
   * Record pattern for ClientInfo to extract components directly.
   * This allows for more concise access to ClientInfo components.
   *
   * @param handler The handler function to process the ClientInfo components
   * @return The result of the handler function
   */
  public <T> T withClientInfo(ClientInfoHandler<T> handler) {
    String userid = clientInfo.getUserid();
    String remoteIP = clientInfo.getRemoteIP();
    String userAgent = clientInfo.getUserAgent();
    String path = clientInfo.getPath();
    
    return handler.handle(userid, remoteIP, userAgent, path);
  }

  /**
   * Record pattern for ResourceInfo to extract components directly.
   * This allows for more concise access to ResourceInfo components.
   *
   * @param handler The handler function to process the ResourceInfo components
   * @return The result of the handler function
   */
  public <T> T withResourceInfo(ResourceInfoHandler<T> handler) {
    String accessProtocol = resourceInfo.getAccessProtocol();
    String accessMethod = resourceInfo.getAccessMethod();
    String action = resourceInfo.getAction();
    String accessedUri = resourceInfo.getAccessedUri();
    
    return handler.handle(accessProtocol, accessMethod, action, accessedUri);
  }

  /**
   * Functional interface for handling ClientInfo components.
   */
  @FunctionalInterface
  public interface ClientInfoHandler<T> {
    T handle(String userid, String remoteIP, String userAgent, String path);
  }

  /**
   * Functional interface for handling ResourceInfo components.
   */
  @FunctionalInterface
  public interface ResourceInfoHandler<T> {
    T handle(String accessProtocol, String accessMethod, String action, String accessedUri);
  }

  /**
   * Creates an authorization event asynchronously using Virtual Threads for improved concurrency.
   *
   * @param info Client information
   * @param resInfo Resource information
   * @param successful Whether the authorization was successful
   * @return CompletableFuture containing the created event
   */
  public static CompletableFuture<NexusAuthorizationEvent> createAsync(
      final ClientInfo info, final ResourceInfo resInfo, final boolean successful) {
    return CompletableFuture.supplyAsync(() -> new NexusAuthorizationEvent(info, resInfo, successful));
  }

  public ClientInfo getClientInfo() {
    return clientInfo;
  }

  public ResourceInfo getResourceInfo() {
    return resourceInfo;
  }

  public boolean isSuccessful() {
    return successful;
  }

  public Date getEventDate() {
    return date;
  }

  /**
   * Returns a string representation of the event using Java 21 String Templates.
   * This provides more readable and maintainable string formatting.
   */
  @Override
  public String toString() {
    return STR."NexusAuthorizationEvent{\n" +
           STR."  timestamp: \{date}\n" +
           STR."  successful: \{successful}\n" +
           STR."  client: {\n" +
           STR."    userid: '\{clientInfo.getUserid()}'\n" +
           STR."    remoteIP: '\{clientInfo.getRemoteIP()}'\n" +
           STR."    userAgent: '\{clientInfo.getUserAgent()}'\n" +
           STR."    path: '\{clientInfo.getPath()}'\n" +
           STR."  }\n" +
           STR."  resource: {\n" +
           STR."    protocol: '\{resourceInfo.getAccessProtocol()}'\n" +
           STR."    method: '\{resourceInfo.getAccessMethod()}'\n" +
           STR."    action: '\{resourceInfo.getAction()}'\n" +
           STR."    uri: '\{resourceInfo.getAccessedUri()}'\n" +
           STR."  }\n" +
           STR."}";
  }

  /**
   * Returns a formatted log message using Java 21 String Templates.
   * This provides a concise representation for logging purposes.
   */
  public String toLogMessage() {
    return STR."Authorization \{successful ? "GRANTED" : "DENIED"} for user '\{clientInfo.getUserid()}' " +
           STR."(\{clientInfo.getRemoteIP()}) accessing '\{resourceInfo.getAccessedUri()}' " +
           STR."via \{resourceInfo.getAccessProtocol()}/\{resourceInfo.getAccessMethod()} " +
           STR."with action '\{resourceInfo.getAction()}'";
  }
}