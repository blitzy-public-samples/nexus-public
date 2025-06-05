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
import java.util.Map;
import java.util.SequencedMap;

import org.sonatype.nexus.capability.CapabilityTypeExists;
import org.sonatype.nexus.validation.group.Create;
import org.sonatype.nexus.validation.group.Update;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

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

  Map<String, String> tags,

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
  
//--- Builder Class ---

 /**
  * Provides a static method to create a new Builder instance for CapabilityXO.
  *
  * @return A new Builder.
  */
 public static Builder builder() {
   return new Builder();
 }

 public static class Builder {
   private String id;
   private String typeId;
   private Boolean enabled;
   private String notes;
   private SequencedMap<String, String> properties;
   private Boolean active;
   private Boolean error;
   private String description;
   private String state;
   private String stateDescription;
   private String status;
   private String typeName;
   private SequencedMap<String, String> tags;
   private String disableWarningMessage;
   private String deleteWarningMessage;

   // Private constructor to enforce usage of CapabilityXO.builder()
   private Builder() {
     // Initialize defaults if any are necessary
     this.enabled = true; // Example: Default to enabled
     this.active = true;   // Example: Default to active
     this.error = false;   // Example: Default to no error
     this.properties = new LinkedHashMap<>(); // Initialize to avoid NullPointerException
     this.tags = new LinkedHashMap<>();       // Initialize to avoid NullPointerException
   }

   public Builder id(String id) {
     this.id = id;
     return this;
   }

   public Builder typeId(String typeId) {
     this.typeId = typeId;
     return this;
   }

   public Builder enabled(Boolean enabled) {
     this.enabled = enabled;
     return this;
   }

   public Builder notes(String notes) {
     this.notes = notes;
     return this;
   }

   /**
    * Sets the entire properties map. Defensively copies the map.
    *
    * @param properties The properties map.
    * @return The builder instance.
    */
   public Builder properties(Map<String, String> properties) {
     // Defensively copy the map to ensure immutability
     this.properties = (properties != null) ? new LinkedHashMap<>(properties) : new LinkedHashMap<>();
     return this;
   }

   /**
    * Adds a single property to the properties map.
    *
    * @param key The key of the property.
    * @param value The value of the property.
    * @return The builder instance.
    */
   public Builder addProperty(String key, String value) {
     if (this.properties == null) {
       this.properties = new LinkedHashMap<>();
     }
     this.properties.put(key, value);
     return this;
   }

   public Builder active(Boolean active) {
     this.active = active;
     return this;
   }

   public Builder error(Boolean error) {
     this.error = error;
     return this;
   }

   public Builder description(String description) {
     this.description = description;
     return this;
   }

   public Builder state(String state) {
     this.state = state;
     return this;
   }

   public Builder stateDescription(String stateDescription) {
     this.stateDescription = stateDescription;
     return this;
   }

   public Builder status(String status) {
     this.status = status;
     return this;
   }

   public Builder typeName(String typeName) {
     this.typeName = typeName;
     return this;
   }

   /**
    * Sets the entire tags map. Defensively copies the map.
    *
    * @param tags The tags map.
    * @return The builder instance.
    */
   public Builder tags(Map<String, String> tags) {
     // Defensively copy the map to ensure immutability
     this.tags = (tags != null) ? new LinkedHashMap<>(tags) : new LinkedHashMap<>();
     return this;
   }

   /**
    * Adds a single tag to the tags map.
    *
    * @param key The key of the tag.
    * @param value The value of the tag.
    * @return The builder instance.
    */
   public Builder addTag(String key, String value) {
     if (this.tags == null) {
       this.tags = new LinkedHashMap<>();
     }
     this.tags.put(key, value);
     return this;
   }

   public Builder disableWarningMessage(String disableWarningMessage) {
     this.disableWarningMessage = disableWarningMessage;
     return this;
   }

   public Builder deleteWarningMessage(String deleteWarningMessage) {
     this.deleteWarningMessage = deleteWarningMessage;
     return this;
   }

   /**
    * Builds the final CapabilityXO instance.
    * The record's compact constructor will handle the internal logic for properties and tags.
    * Bean Validation annotations (@NotEmpty, @NotBlank, @NotNull) will be processed
    * if a validator is configured in your environment.
    *
    * @return A new CapabilityXO instance.
    */
   public CapabilityXO build() {
     // Ensure maps are not null before passing to the record constructor
     if (this.properties == null) {
       this.properties = new LinkedHashMap<>();
     }
     if (this.tags == null) {
       this.tags = new LinkedHashMap<>();
     }

     return new CapabilityXO(
         id,
         typeId,
         enabled,
         notes,
         properties, // Already handled as SequencedMap by builder
         active,
         error,
         description,
         state,
         stateDescription,
         status,
         typeName,
         tags,     // Already handled as SequencedMap by builder
         disableWarningMessage,
         deleteWarningMessage
     );
   }
 }
}