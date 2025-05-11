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
package org.sonatype.nexus.coreui.internal.wonderland;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Auth-token exchange object.
 *
 * @since 3.0
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "authToken", propOrder = {
    "u",
    "p"
})
@XmlRootElement(name = "authToken")
public record AuthTokenXO(
  @XmlElement(required = true)
  @JsonProperty("u")
  String u,
  
  @XmlElement(required = true)
  @JsonProperty("p")
  String p
) {
  /**
   * Returns a new AuthTokenXO with the specified username.
   *
   * @param value the username value
   * @return a new AuthTokenXO with the updated username
   */
  public AuthTokenXO withU(String value) {
    return new AuthTokenXO(value, p);
  }

  /**
   * Returns a new AuthTokenXO with the specified password.
   *
   * @param value the password value
   * @return a new AuthTokenXO with the updated password
   */
  public AuthTokenXO withP(String value) {
    return new AuthTokenXO(u, value);
  }
}
