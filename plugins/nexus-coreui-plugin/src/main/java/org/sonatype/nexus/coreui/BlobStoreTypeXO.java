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
import java.util.List;
import java.util.SequencedCollection;

import javax.annotation.Nullable;
import javax.validation.constraints.NotBlank;

/**
 * BlobStore Type exchange object.
 *
 * @since 3.6
 */
public record BlobStoreTypeXO(
    @NotBlank String id,
    @NotBlank String name,
    @Nullable SequencedCollection<FormFieldXO> formFields,
    @Nullable String customFormName,
    boolean isModifiable,
    boolean isEnabled,
    boolean isConnectionTestable
) {
  /**
   * Default constructor for deserialization.
   */
  public BlobStoreTypeXO {
    // Convert non-sequenced collections to sequenced collections if needed
    if (formFields != null && !(formFields instanceof SequencedCollection)) {
      formFields = new ArrayList<>(formFields);
    }
  }

  /**
   * Constructor that accepts a List for backward compatibility.
   */
  public BlobStoreTypeXO(
      String id,
      String name,
      @Nullable List<FormFieldXO> formFields,
      @Nullable String customFormName,
      boolean isModifiable,
      boolean isEnabled,
      boolean isConnectionTestable
  ) {
    this(id, name, formFields != null ? new ArrayList<>(formFields) : null, customFormName, isModifiable, isEnabled, isConnectionTestable);
  }

  /**
   * @return the form fields as a List for backward compatibility
   */
  @SuppressWarnings("unchecked")
  public List<FormFieldXO> getFormFields() {
    return formFields instanceof List ? (List<FormFieldXO>) formFields : 
           formFields != null ? new ArrayList<>(formFields) : null;
  }

  /**
   * @deprecated Use the constructor or withFormFields() instead
   */
  @Deprecated
  public void setFormFields(List<FormFieldXO> formFields) {
    throw new UnsupportedOperationException("Records are immutable, use the constructor or withFormFields() instead");
  }

  /**
   * @deprecated Use the constructor or withCustomFormName() instead
   */
  @Deprecated
  public void setCustomFormName(String customFormName) {
    throw new UnsupportedOperationException("Records are immutable, use the constructor or withCustomFormName() instead");
  }

  /**
   * @deprecated Use the constructor or withId() instead
   */
  @Deprecated
  public void setId(String id) {
    throw new UnsupportedOperationException("Records are immutable, use the constructor or withId() instead");
  }

  /**
   * @deprecated Use the constructor or withName() instead
   */
  @Deprecated
  public void setName(String name) {
    throw new UnsupportedOperationException("Records are immutable, use the constructor or withName() instead");
  }

  /**
   * @deprecated Use the constructor or withIsModifiable() instead
   */
  @Deprecated
  public void setIsModifiable(boolean isModifiable) {
    throw new UnsupportedOperationException("Records are immutable, use the constructor or withIsModifiable() instead");
  }

  /**
   * @deprecated Use the constructor or withIsEnabled() instead
   */
  @Deprecated
  public void setIsEnabled(boolean isEnabled) {
    throw new UnsupportedOperationException("Records are immutable, use the constructor or withIsEnabled() instead");
  }

  /**
   * @deprecated Use the constructor or withIsConnectionTestable() instead
   */
  @Deprecated
  public void setConnectionTestable(boolean isConnectionTestable) {
    throw new UnsupportedOperationException("Records are immutable, use the constructor or withIsConnectionTestable() instead");
  }

  /**
   * Creates a new instance with the specified form fields.
   */
  public BlobStoreTypeXO withFormFields(SequencedCollection<FormFieldXO> formFields) {
    return new BlobStoreTypeXO(id, name, formFields, customFormName, isModifiable, isEnabled, isConnectionTestable);
  }

  /**
   * Creates a new instance with the specified custom form name.
   */
  public BlobStoreTypeXO withCustomFormName(String customFormName) {
    return new BlobStoreTypeXO(id, name, formFields, customFormName, isModifiable, isEnabled, isConnectionTestable);
  }

  /**
   * Creates a new instance with the specified ID.
   */
  public BlobStoreTypeXO withId(String id) {
    return new BlobStoreTypeXO(id, name, formFields, customFormName, isModifiable, isEnabled, isConnectionTestable);
  }

  /**
   * Creates a new instance with the specified name.
   */
  public BlobStoreTypeXO withName(String name) {
    return new BlobStoreTypeXO(id, name, formFields, customFormName, isModifiable, isEnabled, isConnectionTestable);
  }

  /**
   * Creates a new instance with the specified modifiable flag.
   */
  public BlobStoreTypeXO withIsModifiable(boolean isModifiable) {
    return new BlobStoreTypeXO(id, name, formFields, customFormName, isModifiable, isEnabled, isConnectionTestable);
  }

  /**
   * Creates a new instance with the specified enabled flag.
   */
  public BlobStoreTypeXO withIsEnabled(boolean isEnabled) {
    return new BlobStoreTypeXO(id, name, formFields, customFormName, isModifiable, isEnabled, isConnectionTestable);
  }

  /**
   * Creates a new instance with the specified connection testable flag.
   */
  public BlobStoreTypeXO withIsConnectionTestable(boolean isConnectionTestable) {
    return new BlobStoreTypeXO(id, name, formFields, customFormName, isModifiable, isEnabled, isConnectionTestable);
  }
}
