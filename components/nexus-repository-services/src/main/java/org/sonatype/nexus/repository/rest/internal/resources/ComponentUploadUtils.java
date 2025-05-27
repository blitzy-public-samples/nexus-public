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
package org.sonatype.nexus.repository.rest.internal.resources;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.sonatype.nexus.common.text.Strings2;
import org.sonatype.nexus.repository.upload.AssetUpload;
import org.sonatype.nexus.repository.upload.ComponentUpload;
import org.sonatype.nexus.repository.upload.internal.BlobStoreMultipartForm;
import org.sonatype.nexus.repository.upload.internal.BlobStoreMultipartForm.TempBlobFormField;
import org.sonatype.nexus.repository.view.payloads.TempBlobPartPayload;

/**
 * Utility for processing component upload data.
 *
 * @since 3.8
 */
public class ComponentUploadUtils
{
  private ComponentUploadUtils() {
    // empty
  }

  /**
   * Converts multipart form into ComponentUpload.
   * Uses virtual threads for I/O-bound operations to improve performance.
   *
   * @since 3.16
   *
   * @param format the repository format
   * @param multipartInput the multipart form
   * @return the ComponentUpload
   * @throws IOException if an I/O error occurs during processing
   */
  public static ComponentUpload createComponentUpload(final String format, final BlobStoreMultipartForm multipartInput)
      throws IOException
  {
    // Use virtual threads for I/O-bound operations
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Process files and form fields in parallel using virtual threads
      Future<Map<String, TempBlobFormField>> assetsPayloadsFuture = executor.submit(
          () -> mapFields(format, multipartInput.getFiles()));
      Future<Map<String, String>> formFieldsFuture = executor.submit(
          () -> mapFields(format, multipartInput.getFormFields()));
      
      // Get results from parallel operations
      Map<String, TempBlobFormField> assetsPayloads;
      Map<String, String> formFields;
      try {
        assetsPayloads = assetsPayloadsFuture.get();
        formFields = formFieldsFuture.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted while processing multipart form", e);
      } catch (ExecutionException e) {
        throw new IOException("Error processing multipart form", e.getCause());
      }
      
      Map<String, Map<String, String>> assetFields = new HashMap<>();
      Map<String, String> componentFields = new HashMap<>();

      // Process form fields using pattern matching
      formFields.forEach((key, value) -> {
        if (Strings2.isBlank(value)) {
          return;
        }
        
        // Use pattern matching for field type detection
        switch (key) {
          case String k when k.contains(".") -> {
            int indexOfDot = k.indexOf('.');
            String assetName = k.substring(0, indexOfDot);
            
            if (k.length() > indexOfDot + 2 && assetsPayloads.containsKey(assetName)) {
              assetFields.putIfAbsent(assetName, new HashMap<>());
              assetFields.get(assetName).put(k.substring(indexOfDot + 1), value);
            } else {
              componentFields.put(k, value);
            }
          }
          default -> componentFields.put(key, value);
        }
      });

      // Process asset uploads in parallel using virtual threads
      List<Future<AssetUpload>> assetUploadFutures = assetsPayloads.entrySet().stream()
          .map(entry -> executor.submit(() -> createAssetUpload(entry.getValue(), assetFields.get(entry.getKey()))))
          .collect(Collectors.toList());
      
      // Collect results from parallel asset upload processing
      List<AssetUpload> assetUploads;
      try {
        assetUploads = assetUploadFutures.stream()
            .map(future -> {
              try {
                return future.get();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while processing asset upload", e);
              } catch (ExecutionException e) {
                throw new RuntimeException("Error processing asset upload", e.getCause());
              }
            })
            .collect(Collectors.toList());
      } catch (RuntimeException e) {
        throw new IOException("Failed to process asset uploads", e);
      }

      ComponentUpload componentUpload = new ComponentUpload();
      componentUpload.setFields(componentFields);
      componentUpload.setAssetUploads(assetUploads);
      return componentUpload;
    }
  }

  /**
   * Map field names from API to internal
   * Uses pattern matching for more concise field mapping.
   *
   * @param format the repository format
   * @param assetBlobs the map of asset blobs to process
   * @return the mapped fields
   */
  private static <T> Map<String, T> mapFields(final String format, final Map<String, T> assetBlobs) {
    if (format == null) {
      return assetBlobs;
    }
    
    Map<String, T> result = new HashMap<>();
    String formatPrefix = format + '.';
    
    // Use pattern matching for more concise field mapping
    for (Entry<String, T> entry : assetBlobs.entrySet()) {
      switch (entry) {
        case Entry<String, T> e when e.getKey().startsWith(formatPrefix) -> 
          result.put(e.getKey().substring(formatPrefix.length()), e.getValue());
        default -> 
          result.put(entry.getKey(), entry.getValue());
      }
    }

    return result;
  }

  /**
   * Creates an AssetUpload from a TempBlobFormField and asset fields.
   * Uses pattern matching for more concise and readable code.
   *
   * @param assetPayload the temporary blob form field
   * @param assetFields the asset fields
   * @return the created AssetUpload
   */
  private static AssetUpload createAssetUpload(final TempBlobFormField assetPayload,
                                               final Map<String, String> assetFields)
  {
    // Create asset upload with optimized temporary blob handling
    AssetUpload assetUpload = new AssetUpload();
    
    // Optimize temporary blob handling with improved I/O capabilities
    TempBlobPartPayload payload = switch (assetPayload) {
      case TempBlobFormField field when field != null -> 
        new TempBlobPartPayload(field.getFieldName(), false, field.getFileName(), null, field.getTempBlob());
      default -> throw new IllegalArgumentException("Asset payload cannot be null");
    };
    
    assetUpload.setPayload(payload);
    
    // Use pattern matching for asset fields
    switch (assetFields) {
      case Map<String, String> fields when fields != null -> assetUpload.setFields(fields);
      default -> { /* No fields to set */ }
    }
    
    return assetUpload;
  }
}