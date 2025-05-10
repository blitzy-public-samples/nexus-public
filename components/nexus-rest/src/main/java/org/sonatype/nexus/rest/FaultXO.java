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
package org.sonatype.nexus.rest;

import jakarta.xml.bind.annotation.XmlRootElement;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Fault exchange object.
 * 
 * Implemented as a Java 21 record for more concise and efficient error representation.
 * The record pattern provides immutability and automatic implementations of
 * equals(), hashCode(), and canonical constructor.
 *
 * @since 3.0
 */
@XmlRootElement(name = "fault")
public record FaultXO(
    @JsonProperty("id")
    String id,
    
    @JsonProperty("message")
    String message
) {
  /**
   * No-args constructor for deserialization support.
   * Required by some frameworks like Jackson and JAXB.
   */
  public FaultXO() {
    this(null, null);
  }
  
  /**
   * Static factory method for creating an empty instance.
   * 
   * @return A new FaultXO instance with null values
   */
  public static FaultXO empty() {
    return new FaultXO(null, null);
  }

  /**
   * Constructor with Throwable as the message source.
   */
  public FaultXO(final String id, final Throwable cause) {
    this(id, cause.toString());
  }
  
  /**
   * Static factory method with Throwable as the message source.
   * 
   * @param id The fault identifier
   * @param cause The throwable cause
   * @return A new FaultXO instance
   */
  public static FaultXO fromThrowable(final String id, final Throwable cause) {
    return new FaultXO(id, cause.toString());
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{"
        + "id='" + id + '\''
        + ", message='" + message + '\''
        + '}';
  }
}