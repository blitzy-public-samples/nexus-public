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
package org.sonatype.nexus.coreui;

import java.util.ArrayList;
import java.util.Objects;
import java.util.SequencedCollection;
import java.util.List;
import javax.validation.constraints.NotBlank;

/**
 * BlobStore Type exchange object.
 *
 * @since 3.6
 */
public class BlobStoreTypeXO
{
  @NotBlank
  private String id;

  @NotBlank
  private String name;

  private SequencedCollection<FormFieldXO> formFields;

  private String customFormName;

  private boolean isModifiable;

  private boolean isEnabled;

  private boolean isConnectionTestable;

  /**
   * Default constructor for serialization and direct instantiation.
   */
  public BlobStoreTypeXO() {
    // Empty constructor for serialization and direct instantiation
  }

  /**
   * Constructor with all fields.
   *
   * @param id the ID of the blob store type
   * @param name the name of the blob store type
   * @param formFields the form fields of the blob store type
   * @param customFormName the custom form name of the blob store type
   * @param isModifiable whether the blob store type is modifiable
   * @param isEnabled whether the blob store type is enabled
   * @param isConnectionTestable whether the blob store type is connection testable
   */
  public BlobStoreTypeXO(
      String id,
      String name,
      SequencedCollection<FormFieldXO> formFields,
      String customFormName,
      boolean isModifiable,
      boolean isEnabled,
      boolean isConnectionTestable)
  {
    this.id = id;
    this.name = name;
    this.formFields = formFields;
    this.customFormName = customFormName;
    this.isModifiable = isModifiable;
    this.isEnabled = isEnabled;
    this.isConnectionTestable = isConnectionTestable;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public SequencedCollection<FormFieldXO> getFormFields() {
    return formFields;
  }

  public void setFormFields(List<FormFieldXO> formFields) {
    if (formFields instanceof SequencedCollection<FormFieldXO> sequencedFormFields) {
      this.formFields = sequencedFormFields;
    } else if (formFields != null) {
      this.formFields = new ArrayList<>(formFields);
    } else {
      this.formFields = null;
    }
  }

  public String getCustomFormName() {
    return customFormName;
  }

  public void setCustomFormName(String customFormName) {
    this.customFormName = customFormName;
  }

  public boolean isModifiable() {
    return isModifiable;
  }

  public void setIsModifiable(boolean isModifiable) {
    this.isModifiable = isModifiable;
  }

  public boolean isEnabled() {
    return isEnabled;
  }

  public void setIsEnabled(boolean isEnabled) {
    this.isEnabled = isEnabled;
  }

  public boolean isConnectionTestable() {
    return isConnectionTestable;
  }

  public void setConnectionTestable(boolean isConnectionTestable) {
    this.isConnectionTestable = isConnectionTestable;
  }

  /**
   * Creates a new builder for BlobStoreTypeXO.
   *
   * @return a new builder instance
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder for creating BlobStoreTypeXO instances.
   */
  public static class Builder {
    private final BlobStoreTypeXO instance = new BlobStoreTypeXO();

    /**
     * Sets the ID for the blob store type.
     *
     * @param id the ID
     * @return this builder
     */
    public Builder id(String id) {
      instance.setId(id);
      return this;
    }

    /**
     * Sets the name for the blob store type.
     *
     * @param name the name
     * @return this builder
     */
    public Builder name(String name) {
      instance.setName(name);
      return this;
    }

    /**
     * Sets the form fields for the blob store type.
     *
     * @param formFields the form fields
     * @return this builder
     */
    public Builder formFields(List<FormFieldXO> formFields) {
      instance.setFormFields(formFields);
      return this;
    }

    /**
     * Sets the custom form name for the blob store type.
     *
     * @param customFormName the custom form name
     * @return this builder
     */
    public Builder customFormName(String customFormName) {
      instance.setCustomFormName(customFormName);
      return this;
    }

    /**
     * Sets whether the blob store type is modifiable.
     *
     * @param isModifiable whether the blob store type is modifiable
     * @return this builder
     */
    public Builder modifiable(boolean isModifiable) {
      instance.setIsModifiable(isModifiable);
      return this;
    }

    /**
     * Sets whether the blob store type is enabled.
     *
     * @param isEnabled whether the blob store type is enabled
     * @return this builder
     */
    public Builder enabled(boolean isEnabled) {
      instance.setIsEnabled(isEnabled);
      return this;
    }

    /**
     * Sets whether the blob store type is connection testable.
     *
     * @param isConnectionTestable whether the blob store type is connection testable
     * @return this builder
     */
    public Builder connectionTestable(boolean isConnectionTestable) {
      instance.setConnectionTestable(isConnectionTestable);
      return this;
    }

    /**
     * Builds a new BlobStoreTypeXO instance.
     *
     * @return the new instance
     */
    public BlobStoreTypeXO build() {
      return instance;
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    BlobStoreTypeXO that = (BlobStoreTypeXO) o;
    return isModifiable == that.isModifiable &&
        isEnabled == that.isEnabled &&
        isConnectionTestable == that.isConnectionTestable &&
        Objects.equals(id, that.id) &&
        Objects.equals(name, that.name) &&
        Objects.equals(formFields, that.formFields) &&
        Objects.equals(customFormName, that.customFormName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, name, formFields, customFormName, isModifiable, isEnabled, isConnectionTestable);
  }

  @Override
  public String toString() {
    return "BlobStoreTypeXO{" +
        "id='" + id + '\'' +
        ", name='" + name + '\'' +
        ", formFields=" + formFields +
        ", customFormName='" + customFormName + '\'' +
        ", isModifiable=" + isModifiable +
        ", isEnabled=" + isEnabled +
        ", isConnectionTestable=" + isConnectionTestable +
        '}';
  }
}