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
package org.sonatype.nexus.extdirect.model;

/**
 * Ext.Direct response.
 * 
 * This class is designed to be compatible with Java 21 features including pattern matching
 * and virtual threads. It maintains backward compatibility with existing code while
 * supporting modern Java features.
 *
 * @since 3.0
 */
public class Response<T>
{
  private boolean success;

  private T data;

  /**
   * Constructor for creating a response with success status and data.
   * 
   * @param success whether the operation was successful
   * @param data the data to include in the response
   */
  public Response(boolean success, T data) {
    this.success = success;
    this.data = data;
  }

  /**
   * @return whether the operation was successful
   */
  public boolean isSuccess() {
    return success;
  }

  /**
   * @return the data included in the response
   */
  public T getData() {
    return data;
  }
}