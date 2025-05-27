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
package org.sonatype.nexus.formfields;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Set of checkboxes field.
 * 
 * This implementation has been updated for Java 21 compatibility with enhanced
 * type checking, Pattern Matching for switch, and String Templates.
 * 
 * @since 3.0
 */
public class SetOfCheckboxesFormField
    extends AbstractFormField<Boolean>
{
  /**
   * Virtual thread executor for asynchronous validation operations.
   *
   * @since 3.60
   */
  private static final Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  
  public SetOfCheckboxesFormField(String id, String label, String helpText, boolean required) {
    super(id, label, helpText, required);
  }

  public String getType() {
    return "setOfCheckboxes";
  }
  
  /**
   * Sets the initial value and returns this instance for fluent API usage.
   *
   * @param initialValue the initial value to set
   * @return this instance for fluent API usage
   * @since 3.60
   */
  public SetOfCheckboxesFormField withInitialValue(final Boolean initialValue) {
    setInitialValue(initialValue);
    return this;
  }
  
  /**
   * Validates the input value using Pattern Matching for switch.
   * 
   * @param value the value to validate
   * @return a validation result containing success status and optional error message
   * @since 3.60
   */
  public ValidationResult validateValue(Object value) {
    return switch (value) {
      case Boolean b -> new ValidationResult(true, null);
      case String s when "true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s) -> 
          new ValidationResult(true, null);
      case Number n when n.intValue() == 0 || n.intValue() == 1 -> 
          new ValidationResult(true, null);
      case Collection<?> c when c.isEmpty() -> 
          new ValidationResult(false, STR."Value cannot be an empty collection for field '{getId()}'.");
      case null -> 
          new ValidationResult(isRequired() ? false : true, 
              isRequired() ? STR."Field '{getId()}' is required." : null);
      default -> 
          new ValidationResult(false, STR."Invalid value type {value.getClass().getSimpleName()} for checkbox field '{getId()}'. Expected Boolean.");
    };
  }
  
  /**
   * Asynchronously validates a collection of values using Virtual Threads.
   * 
   * @param values the collection of values to validate
   * @return a CompletableFuture that will complete with the validation results
   * @since 3.60
   */
  public CompletableFuture<List<ValidationResult>> validateValuesAsync(Collection<?> values) {
    return CompletableFuture.supplyAsync(() -> 
        values.stream()
            .map(this::validateValue)
            .toList(),
        virtualThreadExecutor);
  }
  
  /**
   * Record class for validation results.
   * 
   * @param valid whether the validation was successful
   * @param errorMessage the error message if validation failed, null otherwise
   * @since 3.60
   */
  public record ValidationResult(boolean valid, String errorMessage) {}
  
  /**
   * Returns a string representation of this set of checkboxes form field.
   * 
   * @return a string representation of this set of checkboxes form field
   * @since 3.60
   */
  @Override
  public String toString() {
    return STR."SetOfCheckboxesFormField{id={getId()}, label={getLabel()}, required={isRequired()}, initialValue={getInitialValue()}}";
  }
}
