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
package org.sonatype.nexus.coreui.internal.capability;

import java.util.LinkedHashMap;
import java.util.SequencedMap;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.sonatype.nexus.capability.CapabilityTypeExists;
import org.sonatype.nexus.validation.group.Create;
import org.sonatype.nexus.validation.group.Update;

/**
 * Data transfer object for capability information.
 * Implemented as a record for Java 21 compatibility.
 */
public record CapabilityXO(
  @NotEmpty(groups = Update.class)
  String id,

  @NotBlank(groups = Create.class)
  @CapabilityTypeExists(groups = Create.class)
  String typeId,

  @NotNull
  Boolean enabled,

  String notes,

  SequencedMap<String, String> properties,

  Boolean active,

  Boolean error,

  String description,

  String state,

  String stateDescription,

  String status,

  String typeName,

  SequencedMap<String, String> tags,

  String disableWarningMessage,

  String deleteWarningMessage
) {
  /**
   * Constructor with default values for optional fields.
   */
  public CapabilityXO {
    // Convert regular Maps to SequencedMaps if needed
    if (properties != null && !(properties instanceof SequencedMap)) {
      properties = new LinkedHashMap<>(properties);
    }
    
    if (tags != null && !(tags instanceof SequencedMap)) {
      tags = new LinkedHashMap<>(tags);
    }
  }
  
  /**
   * Custom toString implementation to maintain compatibility with previous format.
   */
  @Override
  public String toString() {
    return "CapabilityXO(" +
        "id:" + id + '\'' +
        ", typeId:" + typeId +
        ", enabled:" + enabled +
        ", notes:" + notes +
        ", properties:" + properties +
        ", active:" + active +
        ", error:" + error +
        ", description:" + description +
        ", state:" + state +
        ", stateDescription:" + stateDescription +
        ", status:" + status +
        ", typeName:" + typeName +
        ", tags:" + tags +
        ", disableWarningMessage:" + disableWarningMessage +
        ", deleteWarningMessage:" + deleteWarningMessage +
        ")";
  }
}