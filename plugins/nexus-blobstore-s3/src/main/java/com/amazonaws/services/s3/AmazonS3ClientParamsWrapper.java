package com.amazonaws.services.s3;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.client.AwsSyncClientParams;
import com.amazonaws.client.builder.AdvancedConfig;
import com.amazonaws.handlers.RequestHandler2;
import com.amazonaws.metrics.RequestMetricCollector;
import com.amazonaws.monitoring.CsmConfigurationProvider;
import com.amazonaws.monitoring.MonitoringListener;
import com.amazonaws.retry.RetryPolicy;

import java.net.URI;
import java.util.Collections;
import java.util.List;

public class AmazonS3ClientParamsWrapper extends AwsSyncClientParams {

  private final AWSCredentialsProvider credentialsProvider;
  private final ClientConfiguration clientConfiguration;

  public AmazonS3ClientParamsWrapper(AWSCredentialsProvider credentialsProvider,
                                     ClientConfiguration clientConfiguration) {
    this.credentialsProvider = credentialsProvider;
    this.clientConfiguration = clientConfiguration;
  }

  @Override
  public AWSCredentialsProvider getCredentialsProvider() {
    return credentialsProvider;
  }

  @Override
  public ClientConfiguration getClientConfiguration() {
    return clientConfiguration;
  }

  @Override
  public RequestMetricCollector getRequestMetricCollector() {
    return null; // Return a collector if needed
  }

  @Override
  public List<RequestHandler2> getRequestHandlers() {
    return Collections.emptyList(); // Add handlers if you have any
  }

  @Override
  public CsmConfigurationProvider getClientSideMonitoringConfigurationProvider() {
    return null;
  }

  @Override
  public MonitoringListener getMonitoringListener() {
    return null;
  }
}
