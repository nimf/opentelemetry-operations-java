/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.opentelemetry.metric;

import static com.google.cloud.opentelemetry.metric.AggregateByLabelMetricTimeSeriesBuilder.LABEL_INSTRUMENTATION_SOURCE;
import static com.google.cloud.opentelemetry.metric.AggregateByLabelMetricTimeSeriesBuilder.LABEL_INSTRUMENTATION_VERSION;
import static com.google.cloud.opentelemetry.metric.FakeData.aCloudZone;
import static com.google.cloud.opentelemetry.metric.FakeData.aDoubleSummaryPoint;
import static com.google.cloud.opentelemetry.metric.FakeData.aFakeCredential;
import static com.google.cloud.opentelemetry.metric.FakeData.aGceResource;
import static com.google.cloud.opentelemetry.metric.FakeData.aHistogram;
import static com.google.cloud.opentelemetry.metric.FakeData.aHistogramPoint;
import static com.google.cloud.opentelemetry.metric.FakeData.aHostId;
import static com.google.cloud.opentelemetry.metric.FakeData.aLongPoint;
import static com.google.cloud.opentelemetry.metric.FakeData.aMetricData;
import static com.google.cloud.opentelemetry.metric.FakeData.aProjectId;
import static com.google.cloud.opentelemetry.metric.FakeData.aSpanId;
import static com.google.cloud.opentelemetry.metric.FakeData.aTraceId;
import static com.google.cloud.opentelemetry.metric.FakeData.anInstrumentationLibraryInfo;
import static com.google.cloud.opentelemetry.metric.MetricConfiguration.DEFAULT_PREFIX;
import static com.google.cloud.opentelemetry.metric.MetricTranslator.METRIC_DESCRIPTOR_TIME_UNIT;
import static com.google.cloud.opentelemetry.metric.MetricTranslator.NANO_PER_SECOND;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.Distribution;
import com.google.api.Distribution.BucketOptions;
import com.google.api.Distribution.BucketOptions.Explicit;
import com.google.api.Distribution.Exemplar;
import com.google.api.LabelDescriptor;
import com.google.api.LabelDescriptor.ValueType;
import com.google.api.Metric;
import com.google.api.MetricDescriptor;
import com.google.api.MetricDescriptor.MetricKind;
import com.google.api.MonitoredResource;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.ServiceOptions;
import com.google.cloud.monitoring.v3.MetricServiceClient;
import com.google.cloud.monitoring.v3.MetricServiceSettings;
import com.google.common.collect.ImmutableList;
import com.google.monitoring.v3.CreateMetricDescriptorRequest;
import com.google.monitoring.v3.DroppedLabels;
import com.google.monitoring.v3.Point;
import com.google.monitoring.v3.ProjectName;
import com.google.monitoring.v3.SpanContext;
import com.google.monitoring.v3.TimeInterval;
import com.google.monitoring.v3.TimeSeries;
import com.google.monitoring.v3.TypedValue;
import com.google.protobuf.Any;
import com.google.protobuf.Timestamp;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableMetricData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableSummaryData;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class GoogleCloudMetricExporterTest {

  private static final String PROJECT_ID = "test-id";

  @Mock private CloudMetricClient mockClient;

  @Mock private MetricServiceClient mockMetricServiceClient;

  @Captor private ArgumentCaptor<ArrayList<TimeSeries>> timeSeriesArgCaptor;

  @Captor private ArgumentCaptor<CreateMetricDescriptorRequest> metricDescriptorCaptor;

  @Captor private ArgumentCaptor<ProjectName> projectNameArgCaptor;

  @Before
  public void setUp() {
    when(mockClient.createMetricDescriptor(any())).thenReturn(null);
  }

  @Test
  public void testCreateWithConfigurationSucceeds() throws IOException {
    MetricConfiguration configuration =
        MetricConfiguration.builder()
            .setProjectId(aProjectId)
            .setCredentials(aFakeCredential)
            .build();
    MetricExporter exporter = GoogleCloudMetricExporter.createWithConfiguration(configuration);
    assertNotNull(exporter);
  }

  @Test
  public void testExportSendsAllDescriptorsOnce() {
    MetricExporter exporter =
        InternalMetricExporter.createWithClient(
            aProjectId, DEFAULT_PREFIX, mockClient, MetricDescriptorStrategy.SEND_ONCE);
    CompletableResultCode result = exporter.export(ImmutableList.of(aMetricData, aHistogram));
    assertTrue(result.isSuccess());
    CompletableResultCode result2 = exporter.export(ImmutableList.of(aMetricData, aHistogram));
    assertTrue(result2.isSuccess());
    CompletableResultCode result3 = exporter.export(ImmutableList.of(aMetricData, aHistogram));
    assertTrue(result3.isSuccess());
    verify(mockClient, times(2)).createMetricDescriptor(metricDescriptorCaptor.capture());
    verify(mockClient, times(3))
        .createTimeSeries(projectNameArgCaptor.capture(), timeSeriesArgCaptor.capture());

    // We know two metrics were created, let's verify we got both we sent.
    Set<String> metricDescriptorTypes =
        metricDescriptorCaptor.getAllValues().stream()
            .map(d -> d.getMetricDescriptor().getType())
            .collect(Collectors.toSet());
    assertTrue(metricDescriptorTypes.contains(DEFAULT_PREFIX + "/" + aMetricData.getName()));
    assertTrue(metricDescriptorTypes.contains(DEFAULT_PREFIX + "/" + aHistogram.getName()));
  }

  @Test
  public void testExportSucceeds() {
    MetricDescriptor expectedDescriptor =
        MetricDescriptor.newBuilder()
            .setDisplayName(aMetricData.getName())
            .setType(DEFAULT_PREFIX + "/" + aMetricData.getName())
            .addLabels(
                LabelDescriptor.newBuilder()
                    .setKey("label1")
                    .setValueType(ValueType.STRING)
                    .build())
            .addLabels(
                LabelDescriptor.newBuilder()
                    .setKey("label2")
                    .setValueType(ValueType.STRING)
                    .build())
            .setMetricKind(MetricKind.CUMULATIVE)
            .setValueType(MetricDescriptor.ValueType.INT64)
            .setUnit(METRIC_DESCRIPTOR_TIME_UNIT)
            .setDescription(aMetricData.getDescription())
            .build();
    TimeInterval expectedTimeInterval =
        TimeInterval.newBuilder()
            .setStartTime(
                Timestamp.newBuilder()
                    .setSeconds(aLongPoint.getStartEpochNanos() / NANO_PER_SECOND)
                    .setNanos(0)
                    .build())
            .setEndTime(
                Timestamp.newBuilder()
                    .setSeconds(aLongPoint.getEpochNanos() / NANO_PER_SECOND)
                    .setNanos(0)
                    .build())
            .build();
    Point expectedPoint =
        Point.newBuilder()
            .setValue(TypedValue.newBuilder().setInt64Value(aLongPoint.getValue()))
            .setInterval(expectedTimeInterval)
            .build();
    TimeSeries expectedTimeSeries =
        TimeSeries.newBuilder()
            .setMetric(
                Metric.newBuilder()
                    .setType(expectedDescriptor.getType())
                    .putLabels("label1", "value1")
                    .putLabels("label2", "false")
                    .putLabels(LABEL_INSTRUMENTATION_SOURCE, "instrumentName")
                    .putLabels(LABEL_INSTRUMENTATION_VERSION, "0")
                    .build())
            .addPoints(expectedPoint)
            .setMetricKind(expectedDescriptor.getMetricKind())
            .setResource(
                MonitoredResource.newBuilder()
                    .setType("gce_instance")
                    .putLabels("instance_id", aHostId)
                    .putLabels("zone", aCloudZone)
                    .build())
            .build();
    CreateMetricDescriptorRequest expectedRequest =
        CreateMetricDescriptorRequest.newBuilder()
            .setName("projects/" + aProjectId)
            .setMetricDescriptor(expectedDescriptor)
            .build();
    ProjectName expectedProjectName = ProjectName.of(aProjectId);

    MetricExporter exporter =
        InternalMetricExporter.createWithClient(
            aProjectId, DEFAULT_PREFIX, mockClient, MetricDescriptorStrategy.ALWAYS_SEND);

    CompletableResultCode result = exporter.export(ImmutableList.of(aMetricData));
    verify(mockClient, times(1)).createMetricDescriptor(metricDescriptorCaptor.capture());
    verify(mockClient, times(1))
        .createTimeSeries(projectNameArgCaptor.capture(), timeSeriesArgCaptor.capture());

    assertTrue(result.isSuccess());
    assertEquals(expectedRequest, metricDescriptorCaptor.getValue());
    assertEquals(expectedProjectName, projectNameArgCaptor.getValue());
    assertEquals(1, timeSeriesArgCaptor.getValue().size());
    assertEquals(expectedTimeSeries, timeSeriesArgCaptor.getValue().get(0));
  }

  @Test
  public void testExportWithHistogram_Succeeds() {
    MetricDescriptor expectedDescriptor =
        MetricDescriptor.newBuilder()
            .setDisplayName(aHistogram.getName())
            .setType(DEFAULT_PREFIX + "/" + aHistogram.getName())
            .addLabels(
                LabelDescriptor.newBuilder().setKey("test").setValueType(ValueType.STRING).build())
            .setMetricKind(MetricKind.CUMULATIVE)
            .setValueType(MetricDescriptor.ValueType.DISTRIBUTION)
            .setUnit(aHistogram.getUnit())
            .setDescription(aHistogram.getDescription())
            .build();
    ProjectName expectedProjectName = ProjectName.of(aProjectId);
    TimeInterval expectedTimeInterval =
        TimeInterval.newBuilder()
            .setStartTime(
                Timestamp.newBuilder()
                    .setSeconds(aHistogramPoint.getStartEpochNanos() / NANO_PER_SECOND)
                    .setNanos(0)
                    .build())
            .setEndTime(
                Timestamp.newBuilder()
                    .setSeconds(aHistogramPoint.getEpochNanos() / NANO_PER_SECOND)
                    .setNanos(1)
                    .build())
            .build();
    Point expectedPoint =
        Point.newBuilder()
            .setValue(
                TypedValue.newBuilder()
                    .setDistributionValue(
                        Distribution.newBuilder()
                            .setCount(3)
                            .setMean(1)
                            .addAllBucketCounts(ImmutableList.of(1L, 2L))
                            .setBucketOptions(
                                BucketOptions.newBuilder()
                                    .setExplicitBuckets(Explicit.newBuilder().addBounds(1).build())
                                    .build())
                            .addExemplars(
                                Exemplar.newBuilder()
                                    .setValue(3)
                                    .setTimestamp(
                                        Timestamp.newBuilder().setSeconds(0).setNanos(2).build())
                                    .addAttachments(
                                        Any.pack(
                                            SpanContext.newBuilder()
                                                .setSpanName(
                                                    "projects/"
                                                        + aProjectId
                                                        + "/traces/"
                                                        + aTraceId
                                                        + "/spans/"
                                                        + aSpanId)
                                                .build()))
                                    .addAttachments(
                                        Any.pack(
                                            DroppedLabels.newBuilder()
                                                .putLabel("test2", "two")
                                                .build()))
                                    .build())
                            .build()))
            .setInterval(expectedTimeInterval)
            .build();
    TimeSeries expectedTimeSeries =
        TimeSeries.newBuilder()
            .setMetric(
                Metric.newBuilder()
                    .setType(expectedDescriptor.getType())
                    .putLabels("test", "one")
                    .putLabels("instrumentation_source", "instrumentName")
                    .putLabels("instrumentation_version", "0")
                    .build())
            .addPoints(expectedPoint)
            .setMetricKind(expectedDescriptor.getMetricKind())
            .setResource(
                MonitoredResource.newBuilder()
                    .setType("gce_instance")
                    .putLabels("instance_id", aHostId)
                    .putLabels("zone", aCloudZone)
                    .build())
            .build();
    MetricExporter exporter =
        InternalMetricExporter.createWithClient(
            aProjectId, DEFAULT_PREFIX, mockClient, MetricDescriptorStrategy.ALWAYS_SEND);
    CompletableResultCode result = exporter.export(ImmutableList.of(aHistogram));
    verify(mockClient, times(1)).createMetricDescriptor(metricDescriptorCaptor.capture());
    verify(mockClient, times(1))
        .createTimeSeries(projectNameArgCaptor.capture(), timeSeriesArgCaptor.capture());
    assertTrue(result.isSuccess());
    assertEquals(expectedDescriptor, metricDescriptorCaptor.getValue().getMetricDescriptor());
    assertEquals(expectedProjectName, projectNameArgCaptor.getValue());
    assertEquals(1, timeSeriesArgCaptor.getValue().size());
    assertEquals(expectedTimeSeries, timeSeriesArgCaptor.getValue().get(0));
  }

  @Test
  public void testExportWithNonSupportedMetricTypeReturnsFailure() {
    MetricExporter exporter =
        InternalMetricExporter.createWithClient(
            aProjectId, DEFAULT_PREFIX, mockClient, MetricDescriptorStrategy.ALWAYS_SEND);

    MetricData metricData =
        ImmutableMetricData.createDoubleSummary(
            aGceResource,
            anInstrumentationLibraryInfo,
            "Metric Name",
            "description",
            "ns",
            ImmutableSummaryData.create(ImmutableList.of(aDoubleSummaryPoint)));

    CompletableResultCode result = exporter.export(ImmutableList.of(metricData));
    verify(mockClient, times(0)).createMetricDescriptor(any());
    verify(mockClient, times(0)).createTimeSeries(any(ProjectName.class), any());

    assertFalse(result.isSuccess());
  }

  @Test
  public void verifyExporterWorksWithDefaultConfiguration() {
    try (MockedStatic<ServiceOptions> mockedServiceOptions =
            Mockito.mockStatic(ServiceOptions.class);
        MockedStatic<MetricServiceClient> mockedMetricServiceClient =
            Mockito.mockStatic(MetricServiceClient.class);
        MockedStatic<GoogleCredentials> mockedGoogleCredentials =
            Mockito.mockStatic(GoogleCredentials.class)) {
      mockedServiceOptions.when(ServiceOptions::getDefaultProjectId).thenReturn(PROJECT_ID);
      mockedMetricServiceClient
          .when(() -> MetricServiceClient.create(Mockito.any(MetricServiceSettings.class)))
          .thenReturn(this.mockMetricServiceClient);
      mockedGoogleCredentials
          .when(GoogleCredentials::getApplicationDefault)
          .thenReturn(Mockito.mock(GoogleCredentials.class));

      MetricExporter metricExporter = GoogleCloudMetricExporter.createWithDefaultConfiguration();
      assertNotNull(metricExporter);
      generateOpenTelemetryUsingGoogleCloudMetricExporter(metricExporter);
      simulateExport(metricExporter);

      mockedMetricServiceClient.verify(
          Mockito.times(1),
          () -> MetricServiceClient.create((MetricServiceSettings) Mockito.any()));
      mockedServiceOptions.verify(Mockito.times(1), ServiceOptions::getDefaultProjectId);
      Mockito.verify(this.mockMetricServiceClient)
          .createTimeSeries((ProjectName) Mockito.any(), Mockito.anyList());
    } finally {
      GlobalOpenTelemetry.resetForTest();
    }
  }

  @Test
  public void verifyExporterCreationErrorDoesNotBreakMetricExporter() {
    try (MockedStatic<InternalMetricExporter> mockedInternalTraceExporter =
        Mockito.mockStatic(InternalMetricExporter.class)) {
      mockedInternalTraceExporter
          .when(() -> InternalMetricExporter.createWithConfiguration(Mockito.any()))
          .thenThrow(IOException.class);

      MetricExporter metricExporter = GoogleCloudMetricExporter.createWithDefaultConfiguration();
      assertNotNull(metricExporter);

      // verify trace exporter still works without any additional exceptions
      assertEquals(
          CompletableResultCode.ofSuccess(),
          metricExporter.export(Collections.singleton(aMetricData)));
      assertEquals(CompletableResultCode.ofSuccess(), metricExporter.flush());
      assertEquals(CompletableResultCode.ofSuccess(), metricExporter.shutdown());
    }
  }

  private void generateOpenTelemetryUsingGoogleCloudMetricExporter(MetricExporter metricExporter) {
    SdkMeterProvider meterProvider =
        SdkMeterProvider.builder()
            .registerMetricReader(
                PeriodicMetricReader.builder(metricExporter)
                    .setInterval(java.time.Duration.ofSeconds(5))
                    .build())
            .build();

    OpenTelemetrySdk.builder().setMeterProvider(meterProvider).buildAndRegisterGlobal();
  }

  private void simulateExport(MetricExporter exporter) {
    exporter.export(Collections.singleton(aMetricData));
  }
}
