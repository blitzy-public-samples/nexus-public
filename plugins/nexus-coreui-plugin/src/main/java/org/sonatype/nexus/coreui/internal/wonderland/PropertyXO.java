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
 * Property exchange object.
 *
 * @since 3.0
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "property", propOrder = {
    "key",
    "value"
})
@XmlRootElement(name = "property")
public record PropertyXO(
    @XmlElement(required = true)
    @JsonProperty("key")
    String key,
    
    @JsonProperty("value")
    String value
) {
  /**
   * Creates a new instance with the specified key.
   *
   * @param key the key value
   * @return a new instance with the updated key
   */
  public PropertyXO withKey(String key) {
    return new PropertyXO(key, this.value);
  }

  /**
   * Creates a new instance with the specified value.
   *
   * @param value the value
   * @return a new instance with the updated value
   */
  public PropertyXO withValue(String value) {
    return new PropertyXO(this.key, value);
  }
}