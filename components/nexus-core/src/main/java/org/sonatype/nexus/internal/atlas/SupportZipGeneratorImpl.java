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
package org.sonatype.nexus.internal.atlas;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ByteSize;
import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.log.SupportZipGeneratorRequest;
import org.sonatype.nexus.common.wonderland.DownloadService;
import org.sonatype.nexus.supportzip.SupportBundle;
import org.sonatype.nexus.supportzip.SupportBundle.ContentSource;
import org.sonatype.nexus.supportzip.SupportBundle.ContentSource.Type;
import org.sonatype.nexus.supportzip.SupportBundleCustomizer;
import org.sonatype.nexus.supportzip.SupportZipGenerator;

import com.google.common.io.CountingOutputStream;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.StringTemplate.STR;
import static org.sonatype.nexus.supportzip.SupportBundle.ContentSource.Type.*;

/**
 * Default {@link SupportZipGenerator}.
 *
 * @since 2.7
 */
@Named
@Singleton
public class SupportZipGeneratorImpl
    extends ComponentSupport
    implements SupportZipGenerator
{
  private final List<SupportBundleCustomizer> bundleCustomizers;

  private final DownloadService downloadService;

  /**
   * The maximum (uncompressed) size of any one file that is included into the ZIP file when limit files is enabled.
   */
  private final ByteSize maxFileSize;

  /**
   * The maximum (compressed) size of the entire ZIP file when limit ZIP size is enabled.
   */
  private final ByteSize maxZipFileSize;

  private static int archivedLogSize;

  @Inject
  SupportZipGeneratorImpl(
      final DownloadService downloadService,
      final List<SupportBundleCustomizer> bundleCustomizers,
      final @Named("${atlas.supportZipGenerator.maxFileSize:-30mb}") ByteSize maxFileSize,
      final @Named("${atlas.supportZipGenerator.maxZipFileSize:-50mb}") ByteSize maxZipFileSize)
  {
    this.bundleCustomizers = checkNotNull(bundleCustomizers);
    this.downloadService = checkNotNull(downloadService);

    this.maxFileSize = maxFileSize;
    log.info(STR."Maximum included file size: \{maxFileSize}");

    this.maxZipFileSize = maxZipFileSize;
    log.info(STR."Maximum ZIP file size: \{maxZipFileSize}");
  }

  @Override
  public Result generate(final SupportZipGeneratorRequest request) {
    return generate(request, "support-");
  }

  @Override
  public Result generate(final SupportZipGeneratorRequest request, final String prefix) {
    checkNotNull(request);
    setArchivedLogSize(request);

    log.info(STR."Generating support ZIP: \{request}");

    String uniquePrefix = downloadService.uniqueName(prefix);

    try {
      // Write zip to temporary file first;
      Path file = Files.createTempFile(uniquePrefix, "zip");
      log.debug(STR."Writing ZIP file: \{file}");

      boolean truncated;
      try (OutputStream output = Files.newOutputStream(file, StandardOpenOption.WRITE)) {
        truncated = generate(request, uniquePrefix, output);
      }
      long length = Files.size(file);

      // move the file into place;
      String targetFileName = uniquePrefix + ".zip";
      String path = downloadService.move(file.toFile(), targetFileName);
      log.info(STR."Created support ZIP file: \{path}");

      return new Result(truncated, targetFileName, path, length);
    }
    catch (IOException e) {
      log.error("Support zip generation failed", e);
      return null;
    }
  }

  @Override
  public boolean generate(
      final SupportZipGeneratorRequest request,
      final String prefix,
      final OutputStream outputStream)
  {
    List<ContentSource> sources = null;
    SupportBundle bundle = new SupportBundle();
    try {
      // customize the bundle
      bundleCustomizers.forEach(customizer -> {
        log.debug(STR."Customizing bundle with: \{customizer}");
        customizer.customize(bundle);
      });
      checkState(!bundle.getSources().isEmpty(), "At least one bundle source must be configured");

      // filter only sources which user requested;
      sources = filterSources(request, bundle);
      checkState(!sources.isEmpty(), "At least one content source must be configured");

      // prepare bundle sources using virtual threads for parallel processing
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<ContentSource> preparedSources = sources.stream()
            .map(source -> {
              return executor.submit(() -> {
                log.debug(STR."Preparing bundle source: \{source}");
                try {
                  source.prepare();
                  return source;
                }
                catch (Exception e) {
                  log.error(STR."Failed to prepare source \{source.getClass()}", e);
                  return null;
                }
              });
            })
            .map(future -> {
              try {
                return future.get();
              }
              catch (Exception e) {
                log.error("Failed to get prepared source", e);
                return null;
              }
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        return new ZipCreator(outputStream, preparedSources, prefix, request.isLimitFileSizes(), request.isLimitZipSize())
            .createZip();
      }
    }
    catch (Exception e) {
      log.error("Failed to create support ZIP", e);
      return false;
    }
    finally {
      if (sources != null) {
        // cleanup bundle sources
        sources.forEach(source -> {
          log.debug(STR."Cleaning bundle source: \{source}");
          try {
            source.cleanup();
          }
          catch (Exception e) {
            log.warn("Bundle source cleanup failed", e);
          }
        });
      }
    }
  }

  /**
   * Return set of included content source types.
   */
  private static Set<Type> includedTypes(final SupportZipGeneratorRequest request) {
    Set<Type> types = new HashSet<>();
    
    switch (request) {
      case SupportZipGeneratorRequest r when r.isSystemInformation() -> {
        types.add(SYSINFO);
        types.add(DBINFO); // including this in sys information unless we decide to make it it's own front end toggle
      }
      case SupportZipGeneratorRequest r when r.isThreadDump() -> types.add(THREAD);
      case SupportZipGeneratorRequest r when r.isMetrics() -> types.add(METRICS);
      case SupportZipGeneratorRequest r when r.isConfiguration() -> types.add(CONFIG);
      case SupportZipGeneratorRequest r when r.isSecurity() -> types.add(SECURITY);
      case SupportZipGeneratorRequest r when r.isLog() -> types.add(LOG);
      case SupportZipGeneratorRequest r when r.isTaskLog() -> types.add(TASKLOG);
      case SupportZipGeneratorRequest r when r.isAuditLog() -> types.add(AUDITLOG);
      case SupportZipGeneratorRequest r when r.isJmx() -> types.add(JMX);
      case SupportZipGeneratorRequest r when r.isReplication() -> types.add(REPLICATIONLOG);
    }
    
    // included by default, if not selected it will default to 0 days of archived logs which means it includes nothing
    types.add(ARCHIVEDLOG);
    return types;
  }

  /**
   * Filter only included content sources.
   */
  private List<ContentSource> filterSources(
      final SupportZipGeneratorRequest request,
      final SupportBundle supportBundle)
  {
    Set<Type> include = includedTypes(request);

    return supportBundle.getSources()
        .stream()
        .filter(source -> include.contains(source.getType()))
        .peek(source -> log.debug(STR."Including content source: \{source}"))
        .collect(Collectors.toList());
  }

  private static int percentCompressed(final long compressed, final long uncompressed) {
    return (int) (100 - ((compressed / uncompressed) * 100));
  }

  // helper to get archived log request size
  public void setArchivedLogSize(SupportZipGeneratorRequest request) {
    archivedLogSize = request.getArchivedLog();
  }

  public static int getArchivedLogSize() {
    return archivedLogSize;
  }

  private class ZipCreator
  {
    private final OutputStream outputStream;

    private final List<ContentSource> sources;

    private final String prefix;

    private final boolean limitFileSizes;

    private final boolean limitZipSize;

    private long totalUncompressed;

    public ZipCreator(
        final OutputStream outputStream,
        final List<ContentSource> sources,
        final String prefix,
        final boolean limitFileSizes,
        final boolean limitZipSize)
    {
      this.outputStream = outputStream;
      this.sources = sources;
      this.prefix = prefix;
      this.limitFileSizes = limitFileSizes;
      this.limitZipSize = limitZipSize;
    }

    /**
     * Creates the zip file.
     * 
     * @return true if the zipfile was truncated
     */
    boolean createZip() {
      CountingOutputStream stream = new CountingOutputStream(outputStream);
      // maximum size of included content
      final int maxContentSize = (int) maxFileSize.toBytes();

      // size of chunks for appending source content and detecting max ZIP size
      final int chunkSize = 4 * 1024;

      // leave some fudge room so we can close the zip file and write marker tokens if needed
      final int maxZipSize = (int) (maxZipFileSize.toBytes() - (chunkSize * 2));

      // token added to files to indicate truncation has occurred
      final String TRUNCATED_TOKEN = "** TRUNCATED **";

      // flag to indicate if any content was truncated
      AtomicBoolean truncated = new AtomicBoolean(false);

      try (FlushableZipOutputStream zip = new FlushableZipOutputStream(stream);
           ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        // setup zip too sync-flush so we can detect compressed size for partially written files
        zip.setLevel(Deflater.DEFAULT_COMPRESSION);
        zip.setSyncFlush(true);

        // add directory entries
        addDirectoryEntries(zip);

        // add content entries, sorted so highest priority are processed first
        sources.sort(Comparator.naturalOrder());
        
        // Process sources in parallel using virtual threads
        List<Future<Void>> futures = sources.stream()
            .map(source -> executor.submit(() -> {
              // skipping over archived files that cause the zip to be too large or are past the file size limit
              // TODO: figure out how to handle .gz file truncation gracefully
              if (source.getType() == ARCHIVEDLOG
                  && (limitFileSizes && source.getSize() > maxContentSize || limitZipSize && source.getSize() +
                      stream.getCount() > maxZipSize)) {
                log.warn(STR."Skipping \{source.getPath()} due to size limit");
                return null;
              }

              log.debug(STR."Adding content entry: \{source} \{source.getSize()} bytes");
              ZipEntry entry = addEntry(zip, source.getPath());

              try (InputStream input = source.getContent()) {
                // determine if the current file is a log file
                boolean isLogFile = switch (source.getType()) {
                  case LOG, TASKLOG, AUDITLOG, ARCHIVEDLOG -> true;
                  default -> false;
                };
                
                // only apply truncation logic to log files
                byte[] buff = new byte[chunkSize];
                int len;
                long writtenBytes = 0;
                while ((len = input.read(buff)) != -1) {
                  // truncate content if max file size or max ZIP size reached
                  if ((isLogFile && limitFileSizes && writtenBytes + len > maxContentSize) ||
                      (limitZipSize && stream.getCount() + len > maxZipSize)) {
                    log.warn(STR."Truncating source contents; limit reached: \{source.getPath()}");
                    zip.write(TRUNCATED_TOKEN.getBytes());
                    truncated.set(true);
                    break;
                  }

                  zip.write(buff, 0, len);
                  writtenBytes += len;

                  // flush so we can detect compressed size for partially written files
                  zip.flush();
                }
              }
              catch (Exception e) { // NOSONAR - catching all exceptions so that a bad file of any sort won't cause us to
                // stop
                log.warn(STR."Unable to include \{source.getPath()} in bundle, moving onto next file.", e);
              }

              closeEntry(zip, entry);
              return null;
            }))
            .collect(Collectors.toList());

        // Wait for all tasks to complete
        for (Future<Void> future : futures) {
          try {
            future.get();
          } catch (Exception e) {
            log.error("Error processing content source", e);
          }
        }

        // add truncated marker if we truncated anything
        if (truncated.get()) {
          addEntry(zip, "truncated");
        }
      }
      catch (IOException e) {
        throw new UncheckedIOException(e);
      }

      if (log.isDebugEnabled()) {
        log.debug(STR."ZIP file (in=\{totalUncompressed} out=\{stream.getCount()}) bytes, compressed: \{percentCompressed(stream.getCount(), totalUncompressed)}%");
      }

      return truncated.get();
    }

    // helper to create normalized entry with prefix
    private ZipEntry addEntry(final ZipOutputStream zip, String path) {
      if (!path.startsWith("/")) {
        path = "/" + path;
      }

      try {
        ZipEntry entry = new ZipEntry(prefix + path);
        zip.putNextEntry(entry);
        return entry;
      }
      catch (IOException e) {
        log.debug(STR."Failed to create path \{path}");
        throw new UncheckedIOException(e);
      }
    }

    // helper to close entry
    private void closeEntry(final ZipOutputStream zip, final ZipEntry entry) {
      try {
        zip.closeEntry();

        // not all entries have a size
        if (entry.getSize() > 0) {
          if (log.isDebugEnabled()) {
            log.debug(STR."Entry (in=\{entry.getSize()} out=\{entry.getCompressedSize()}) bytes, compressed: \{percentCompressed(entry.getCompressedSize(), entry.getSize())}%");
          }
          totalUncompressed += entry.getSize();
        }
      }
      catch (IOException e) {
        log.debug(STR."Failed to close entry \{entry}");
        throw new UncheckedIOException(e);
      }
    }

    // helper to add entries for each directory
    private void addDirectoryEntries(final ZipOutputStream zip) {
      // include entry for top-level directory
      addEntry(zip, "/");

      // add unique directory entries
      Set<String> dirs = new TreeSet<>();
      sources.forEach(it -> {
        List<String> path = Arrays.asList(it.getPath().split("/"));
        if (path.size() > 1) {
          // eg. "foo/bar/baz" -> [ "foo", "foo/bar" ]
          for (int i = 1; i < path.size(); i++) {
            dirs.add(path.subList(0, i).stream().collect(Collectors.joining("/")));
          }
        }
      });

      dirs.forEach(it -> {
        log.debug(STR."Adding directory entry: \{it}");
        ZipEntry entry = addEntry(zip, it + "/");
        // must end with "/"
        closeEntry(zip, entry);
      });
    }
  }
}