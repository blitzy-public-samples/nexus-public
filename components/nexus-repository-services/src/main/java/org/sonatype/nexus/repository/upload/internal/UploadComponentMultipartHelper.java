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
package org.sonatype.nexus.repository.upload.internal;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.upload.TempBlobFactory;
import org.sonatype.nexus.repository.upload.internal.BlobStoreMultipartForm.TempBlobFormField;
import org.sonatype.nexus.security.authc.AntiCsrfHelper;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.ParameterParser;
import org.apache.commons.fileupload.RequestContext;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.Charsets;
import org.apache.commons.io.IOUtils;

import static java.lang.StringTemplate.STR;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Extracts fields from a multipart form storing files in the appropriate blob store.
 *
 * @since 3.16
 */
@Named
@Singleton
public class UploadComponentMultipartHelper
    extends ComponentSupport
{
  private final AntiCsrfHelper antiCsrfHelper;

  private final TempBlobFactory tempBlobFactory;

  @Inject
  public UploadComponentMultipartHelper(final AntiCsrfHelper antiCsrfHelper, final TempBlobFactory tempBlobFactory) {
    this.antiCsrfHelper = antiCsrfHelper;
    this.tempBlobFactory = checkNotNull(tempBlobFactory);
  }

  /**
   * Parse a multipart-form submission creating file uploads in the blob store of the repository. Reminder, callers must
   * call {@code close} on {@code TempBlobs} returned from this method.
   */
  public BlobStoreMultipartForm parse(final Repository repository, final HttpServletRequest request)
      throws FileUploadException
  {
    BlobStoreMultipartForm multipartForm = new BlobStoreMultipartForm();
    TempBlobServletFileUpload upload = new TempBlobServletFileUpload(repository, multipartForm);

    upload.parseRequest(request);

    // ExtJs results in fields with the upload name for some reason
    multipartForm.getFiles().keySet().forEach(assetName -> multipartForm.getFormFields().remove(assetName));

    String token = multipartForm.getFormFields().remove(AntiCsrfHelper.ANTI_CSRF_TOKEN_NAME);
    antiCsrfHelper.requireValidToken(request, token);

    return multipartForm;
  }

  private class TempBlobServletFileUpload extends ServletFileUpload
  {
    private final Repository repository;

    private final BlobStoreMultipartForm multipartForm;

    private final Predicate<String> assetPattern = Pattern.compile("^(\\w+\\.)?asset\\d*$").asPredicate();

    private Field field;

    TempBlobServletFileUpload(final Repository repository, final BlobStoreMultipartForm multipartForm)
        throws FileUploadException
    {
      this.repository = repository;
      this.multipartForm = multipartForm;
      try {
        // Use reflection to access the name field in FileItemStreamImpl
        // This is needed because getName() can throw InvalidFileNameException
        field = Class.forName("org.apache.commons.fileupload.FileUploadBase$FileItemIteratorImpl$FileItemStreamImpl")
            .getDeclaredField("name");
        field.setAccessible(true);
      }
      catch (Exception e) {
        throw new FileUploadException(STR."Unable to initialize multipart parsing: \{e.getMessage()}", e);
      }
    }

    @Override
    public List<FileItem> parseRequest(final RequestContext ctx) throws FileUploadException {
      boolean successful = false;
      try {
        FileItemIterator iter = getItemIterator(ctx);
        
        // Use virtual threads for concurrent processing of file parts
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
          List<Future<?>> futures = Collections.synchronizedList(new java.util.ArrayList<>());
          
          while (iter.hasNext()) {
            FileItemStream item = iter.next();
            futures.add(executor.submit(() -> {
              try {
                createField(item);
              } catch (FileUploadException e) {
                throw new RuntimeException(e);
              }
              return null;
            }));
          }
          
          // Wait for all processing to complete
          for (Future<?> future : futures) {
            try {
              future.get();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new FileUploadException(STR."File processing interrupted: \{e.getMessage()}", e);
            } catch (ExecutionException e) {
              Throwable cause = e.getCause();
              if (cause instanceof RuntimeException && cause.getCause() instanceof FileUploadException) {
                throw (FileUploadException) cause.getCause();
              }
              throw new FileUploadException(STR."Error processing file part: \{e.getMessage()}", e);
            }
          }
        }
        
        successful = true;
        return Collections.emptyList();
      }
      catch (FileUploadIOException e) { // NOSONAR
        throw (FileUploadException) e.getCause();
      }
      catch (IOException e) {
        throw new FileUploadException(STR."IO error processing multipart request: \{e.getMessage()}", e);
      }
      finally {
        if (!successful) {
          for (TempBlobFormField tempBlob : multipartForm.getFiles().values()) {
            tempBlob.getTempBlob().close();
          }
        }
      }
    }

    private void createField(final FileItemStream item) throws FileUploadException {
      try (InputStream in = item.openStream()) {
        // Use pattern matching for switch to determine the type of item
        switch (item) {
          // Case for file uploads (non-form fields or asset patterns with content type)
          case FileItemStream s when !s.isFormField() || (assetPattern.test(s.getFieldName()) && s.getContentType() != null) -> {
            // Get the filename safely using reflection instead of getName()
            String fileName = getFileNameSafely(item);
            multipartForm.putFile(item.getFieldName(), new TempBlobFormField(item.getFieldName(), fileName,
                tempBlobFactory.create(repository, in, HashAlgorithm.ALL_HASH_ALGORITHMS.values())));
          }
          // Case for regular form fields
          case FileItemStream s -> {
            multipartForm.putFormField(s.getFieldName(), IOUtils.toString(in, getCharSet(s.getContentType())));
          }
        }
      }
      catch (FileUploadIOException e) { // NOSONAR
        throw (FileUploadException) e.getCause();
      }
      catch (IOException e) {
        throw new IOFileUploadException(
            STR."Processing of multipart/form-data request failed: \{e.getMessage()}", e);
      }
    }
    
    /**
     * Safely get the filename using reflection to avoid InvalidFileNameException
     */
    private String getFileNameSafely(FileItemStream item) throws FileUploadException {
      try {
        return (String) field.get(item);
      } catch (IllegalAccessException e) {
        log.error(STR."Unable to access filename field: \{e.getMessage()}", e);
        throw new FileUploadException(STR."Unable to access filename field: \{e.getMessage()}", e);
      }
    }

    private String getCharSet(final String contentType) {
      ParameterParser parser = new ParameterParser();
      parser.setLowerCaseNames(true);
      // Parameter parser can handle null input
      Map<String, String> params = parser.parse(contentType, ';');
      return params.getOrDefault("charset", Charsets.UTF_8.name());
    }
  }
}