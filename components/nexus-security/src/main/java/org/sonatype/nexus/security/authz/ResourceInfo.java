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

import java.io.Serializable;

/**
 * Resource info collects the description of HOW and WHAT has been accessed.
 * <p>
 * Implemented as a Java 21 Record for improved immutability and reduced boilerplate.
 */
public record ResourceInfo(
    String accessProtocol,
    String accessMethod,
    String action,
    String accessedUri
) implements Serializable
{
  /**
   * Returns the access protocol.
   * 
   * @return the access protocol
   * @deprecated Use {@link #accessProtocol()} instead (record accessor method)
   */
  @Deprecated
  public String getAccessProtocol() {
    return accessProtocol;
  }

  /**
   * Returns the access method.
   * 
   * @return the access method
   * @deprecated Use {@link #accessMethod()} instead (record accessor method)
   */
  @Deprecated
  public String getAccessMethod() {
    return accessMethod;
  }

  /**
   * Returns the action.
   * 
   * @return the action
   * @deprecated Use {@link #action()} instead (record accessor method)
   */
  @Deprecated
  public String getAction() {
    return action;
  }

  /**
   * Returns the accessed URI.
   * 
   * @return the accessed URI
   * @deprecated Use {@link #accessedUri()} instead (record accessor method)
   */
  @Deprecated
  public String getAccessedUri() {
    return accessedUri;
  }
}