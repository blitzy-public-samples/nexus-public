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
package org.sonatype.nexus.internal.security.rest;

import static java.lang.StringTemplate.STR;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Status object for EULA acceptance tracking.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EulaStatus
{
  private static final Logger log = LoggerFactory.getLogger(EulaStatus.class);
  
  public static final String EXPECTED_DISCLAIMER =
      "Use of Sonatype Nexus Repository - Community Edition is governed by the End User License Agreement at https://links.sonatype.com/products/nxrm/ce-eula. By returning the value from 'accepted:false' to 'accepted:true', you acknowledge that you have read and agree to the End User License Agreement at https://links.sonatype.com/products/nxrm/ce-eula.";

  @JsonProperty("accepted")
  private boolean accepted;

  @JsonProperty("disclaimer")
  private String disclaimer;

  public boolean isAccepted() {
    return accepted;
  }

  public void setAccepted(boolean accepted) {
    this.accepted = accepted;
  }

  public String getDisclaimer() {
    return disclaimer;
  }

  public void setDisclaimer(String disclaimer) {
    this.disclaimer = disclaimer;
  }

  /**
   * Checks if the disclaimer matches the expected value.
   * Uses String Templates for improved validation and error reporting.
   * 
   * @return true if the disclaimer is valid, false otherwise
   */
  @JsonIgnore
  public boolean hasExpectedDisclaimer() {
    if (disclaimer == null || disclaimer.isEmpty()) {
      log.warn(STR."Disclaimer validation failed: disclaimer is \{disclaimer == null ? "null" : "empty"}.");
      return false;
    }
    
    // Using String Templates for clearer comparison logic
    boolean matches = EXPECTED_DISCLAIMER.equals(disclaimer);
    if (!matches) {
      // Improved error reporting with String Templates
      log.warn(STR."Disclaimer validation failed: expected \{EXPECTED_DISCLAIMER.length()} characters but got \{disclaimer.length()} characters.");
      
      // More detailed validation to help identify the difference
      if (disclaimer.length() > 20) {
        String expectedStart = EXPECTED_DISCLAIMER.substring(0, 20);
        String actualStart = disclaimer.substring(0, 20);
        
        if (!expectedStart.equals(actualStart)) {
          log.warn(STR."Disclaimer beginning mismatch: expected '\{expectedStart}...' but got '\{actualStart}...'");
        } else {
          // Find the first point of difference
          int minLength = Math.min(EXPECTED_DISCLAIMER.length(), disclaimer.length());
          for (int i = 0; i < minLength; i++) {
            if (EXPECTED_DISCLAIMER.charAt(i) != disclaimer.charAt(i)) {
              int contextStart = Math.max(0, i - 10);
              int contextEnd = Math.min(minLength, i + 10);
              String expectedContext = EXPECTED_DISCLAIMER.substring(contextStart, contextEnd);
              String actualContext = disclaimer.substring(contextStart, contextEnd);
              log.warn(STR."Disclaimer difference at position \{i}: expected '...\{expectedContext}...' but got '...\{actualContext}...'");
              break;
            }
          }
        }
      }
    }
    
    return matches;
  }
}