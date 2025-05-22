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
package org.sonatype.nexus.testcommon.validation;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;

import org.sonatype.goodies.httpfixture.validation.HttpValidator;
import org.sonatype.nexus.common.text.Strings2;

import com.google.common.net.HttpHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.junit.Assert.assertTrue;

/**
 * HttpValidator for http headers.
 * 
 * Supports validation of headers from both traditional and virtual thread-based HTTP clients.
 * Compatible with Java 21 and newer runtime environments.
 *
 * @since 3.1
 */
public class HeaderValidator
    implements HttpValidator
{
  private static final Logger log = LoggerFactory.getLogger(HeaderValidator.class);

  // Enhanced regex to properly handle Java 21 version strings
  private static final String EXPECTED_USER_AGENT_BASE_REGEX = String.format(
      "^Nexus/[0-9\\.-]+(-SNAPSHOT)? \\((OSS|PRO){1}; %s; %s; %s; %s(\\+.+)?\\).*", 
      System.getProperty("os.name"),
      System.getProperty("os.version"), 
      System.getProperty("os.arch"), 
      System.getProperty("java.version"));

  // Header for virtual thread detection
  private static final String VIRTUAL_THREAD_HEADER = "X-Virtual-Thread";

  /**
   * Map of header names to expected header regex.
   */
  private final Map<String, Pattern> expectedHeaders = new HashMap<>();

  public HeaderValidator() {
    expectedHeaders.put(HttpHeaders.USER_AGENT, Pattern.compile(EXPECTED_USER_AGENT_BASE_REGEX + ".*"));
  }

  public HeaderValidator(String globalUserAgentSuffix) {
    checkArgument(!Strings2.isBlank(globalUserAgentSuffix), "User agent suffix must be non-blank.");
    expectedHeaders.put(HttpHeaders.USER_AGENT,
        Pattern.compile(EXPECTED_USER_AGENT_BASE_REGEX + " " + Pattern.quote(globalUserAgentSuffix) + ".*"));
  }

  @Override
  public void validate(HttpServletRequest httpRequest) {
    checkNotNull(httpRequest);

    log.info("Performing validation for incoming '{}' request.", httpRequest.getMethod());
    
    // Check if request is from a virtual thread client
    boolean isVirtualThreadClient = isVirtualThreadClient(httpRequest);
    if (isVirtualThreadClient) {
      log.info("Detected request from virtual thread client");
    }
    
    expectedHeaders.forEach((k, v) -> {
      String header = httpRequest.getHeader(k);
      validateHeader(header, v, isVirtualThreadClient);
    });
  }

  /**
   * Validates a header value against the expected pattern using Java 21 pattern matching.
   * 
   * @param header the header value to validate
   * @param pattern the expected pattern
   * @param isVirtualThreadClient whether the request is from a virtual thread client
   */
  private void validateHeader(String header, Pattern pattern, boolean isVirtualThreadClient) {
    // Using pattern matching with switch expression for more efficient validation
    boolean matches = switch (header) {
      case null -> false;
      case String s when pattern.matcher(s).matches() -> true;
      case String s when isVirtualThreadClient && isVirtualThreadUserAgent(s, pattern) -> {
        log.debug("Validated virtual thread client user agent: {}", s);
        yield true;
      }
      default -> false;
    };
    
    assertTrue(String.format("Header value '%s' must match regex '%s'.", header, pattern), matches);
    log.info("Validated header: {}", header);
  }
  
  /**
   * Checks if the request is from a virtual thread client.
   * 
   * @param request the HTTP request
   * @return true if the request is from a virtual thread client
   */
  private boolean isVirtualThreadClient(HttpServletRequest request) {
    String threadHeader = request.getHeader(VIRTUAL_THREAD_HEADER);
    boolean isVirtualThread = "true".equalsIgnoreCase(threadHeader);
    
    // Additional check for thread name pattern typical of virtual threads
    String threadName = Thread.currentThread().getName();
    boolean hasVirtualThreadName = threadName.startsWith("VirtualThread") || threadName.contains("virtual-");
    
    return isVirtualThread || hasVirtualThreadName;
  }
  
  /**
   * Special validation for user agents from virtual thread clients which may have different formatting.
   * 
   * @param userAgent the user agent string
   * @param pattern the expected pattern
   * @return true if the user agent is valid for a virtual thread client
   */
  private boolean isVirtualThreadUserAgent(String userAgent, Pattern pattern) {
    // Virtual thread clients might append additional information
    if (userAgent.contains("VirtualThread") || userAgent.contains("Java-21")) {
      // Extract the base part of the user agent and validate it
      int virtualThreadIndex = userAgent.indexOf("VirtualThread");
      if (virtualThreadIndex == -1) {
        virtualThreadIndex = userAgent.indexOf("Java-21");
      }
      
      if (virtualThreadIndex > 0) {
        String baseUserAgent = userAgent.substring(0, virtualThreadIndex).trim();
        return pattern.matcher(baseUserAgent).matches();
      }
    }
    return false;
  }
}