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
package org.sonatype.nexus.repository.rest.internal;

import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.repository.upload.UploadDefinition;
import org.sonatype.nexus.repository.upload.UploadFieldDefinition;
import org.sonatype.nexus.repository.upload.UploadManager;
import org.sonatype.nexus.swagger.ParameterContributor;

import com.google.common.collect.ImmutableList;
import io.swagger.models.HttpMethod;
import io.swagger.models.parameters.FormParameter;

import static io.swagger.models.HttpMethod.POST;
import static java.lang.StringTemplate.STR;
import static org.sonatype.nexus.rest.APIConstants.V1_API_PREFIX;

/**
 * @since 3.8
 */
@Named
@Singleton
public class ComponentUploadParameterContributor
    extends ParameterContributor<FormParameter>
{
  private static final List<HttpMethod> HTTP_METHODS = ImmutableList.of(POST);

  private static final List<String> PATHS = ImmutableList.of(V1_API_PREFIX + "/components");

  @Inject
  public ComponentUploadParameterContributor(final UploadManager uploadManager) {
    super(HTTP_METHODS, PATHS, transformUploadDefinitions(uploadManager.getAvailableDefinitions()));
  }

  /**
   * Transforms upload definitions into form parameters for Swagger documentation.
   * Uses Java 21 Stream API features and String Templates for improved readability.
   */
  private static Collection<FormParameter> transformUploadDefinitions(final Collection<UploadDefinition> uploadDefinitions) {
    return uploadDefinitions.stream()
        .flatMap(uploadDefinition -> {
          String format = uploadDefinition.getFormat();
          
          // Process component fields using Stream API
          var componentFields = uploadDefinition.getComponentFields().stream()
              .map(field -> createFormParameter(format, field, ""))
              .toList();
          
          // Process asset fields for each asset index using IntStream
          var assetFields = IntStream.rangeClosed(1, uploadDefinition.isMultipleUpload() ? 3 : 1)
              .boxed()
              .flatMap(i -> {
                // Use String Templates for more readable string construction
                String assetIndex = uploadDefinition.isMultipleUpload() ? Integer.toString(i) : "";
                String assetName = STR."{format}.asset{assetIndex}";
                String assetDisplayName = STR."{format} Asset {assetIndex}";
                
                // Create asset file parameter using lambda instead of anonymous inner class
                FormParameter assetFileParam = new FormParameter()
                    .name(assetName)
                    .type("file")
                    .description(assetDisplayName);
                
                // Create asset field parameters using Stream API
                var fieldParams = uploadDefinition.getAssetFields().stream()
                    .map(field -> createFormParameter(assetName, field, assetDisplayName))
                    .toList();
                
                // Combine the asset file parameter with the field parameters
                return Stream.concat(Stream.of(assetFileParam), fieldParams.stream());
              })
              .toList();
          
          // Combine component fields and asset fields
          return Stream.concat(componentFields.stream(), assetFields.stream());
        })
        .toList(); // Use toList() from Java 21 to create an unmodifiable list
  }
  
  /**
   * Creates a FormParameter for a field with appropriate name and description based on field type.
   * Uses pattern matching for switch to handle different field types elegantly.
   */
  private static FormParameter createFormParameter(String prefix, UploadFieldDefinition field, String displayPrefix) {
    // Construct the parameter name using String Templates
    String paramName = STR."{prefix}.{field.getName()}";
    
    // Construct the description using String Templates
    String description = displayPrefix.isEmpty() ?
        STR."{prefix} {field.getDisplayName()}" :
        STR."{displayPrefix} {field.getDisplayName()}";
    
    // Use pattern matching for switch to handle different field types
    return switch (field.getType()) {
      // Handle string fields
      case STRING -> new FormParameter()
          .name(paramName)
          .type("string")
          .description(description);
      
      // Handle boolean fields
      case BOOLEAN -> new FormParameter()
          .name(paramName)
          .type("boolean")
          .description(description);
      
      // Handle all other field types
      default -> new FormParameter()
          .name(paramName)
          .type(field.getType().name().toLowerCase())
          .description(description);
    };
  }
}